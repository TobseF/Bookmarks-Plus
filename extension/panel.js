// Side panel and manager tab: folder tree, list, tiles and open tabs – data comes from the app.
import { call, faviconUrl, isSavable, mediaUrl, pairStart, pairWait, status } from './api.js';
import { applyI18n, ready, t as tr } from './i18n.js';

const $ = (id) => document.getElementById(id);
const params = new URLSearchParams(location.search);
const WIDE = params.get('mode') === 'tab';
const PAGE = 300;

const state = {
  view: 'tree',
  scopeId: null,
  sort: 'added',
  query: params.get('q') || '',
  limit: PAGE,
  tree: { rootId: null, inboxId: null, folders: [] },
  folderById: new Map(),
  kids: new Map(), // parentId → [folder]
  expanded: new Set(),
  items: new Map(), // folderId → bookmarks directly in the folder
  tagColors: {},
  tagNames: [],
  results: [],
};

if (WIDE) document.body.classList.add('wide');

// ------------------------------------------------------------------ Helpers

function el(tag, cls, text) {
  const e = document.createElement(tag);
  if (cls) e.className = cls;
  if (text != null) e.textContent = text;
  return e;
}

function folderPath(id) {
  const names = [];
  let f = state.folderById.get(id);
  while (f) {
    names.unshift(f.title);
    f = state.folderById.get(f.parentId);
  }
  return names.join(' / ');
}

function formatStars(n) {
  if (n == null) return '';
  return '★ ' + (n >= 1000 ? (n / 1000).toFixed(n >= 10000 ? 0 : 1).replace('.0', '') + 'k' : n);
}

async function persist() {
  await chrome.storage.local.set({
    panelView: state.view,
    panelSort: state.sort,
    panelScope: state.scopeId,
    panelExpanded: [...state.expanded],
  });
}

let toastTimer = null;
function toast(text, action) {
  $('toastText').textContent = text;
  const b = $('toastAction');
  b.hidden = !action;
  if (action) {
    b.textContent = action[0];
    b.onclick = () => {
      $('toast').hidden = true;
      action[1]();
    };
  }
  $('toast').hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => ($('toast').hidden = true), action ? 6000 : 2500);
}

// images from the app: at most 6 at a time, result stays in memory
const imageCache = new Map();
const imageQueue = [];
let imageRunning = 0;
function loadImage(id) {
  if (imageCache.has(id)) return imageCache.get(id);
  const p = new Promise((resolve) => imageQueue.push({ id, resolve }));
  imageCache.set(id, p);
  pumpImages();
  return p;
}
function pumpImages() {
  while (imageRunning < 6 && imageQueue.length) {
    const { id, resolve } = imageQueue.shift();
    imageRunning++;
    mediaUrl(id, 'preview')
      .then(resolve)
      .finally(() => {
        imageRunning--;
        pumpImages();
      });
  }
}
const imageObserver = new IntersectionObserver(
  (entries) => {
    entries.forEach((e) => {
      if (!e.isIntersecting) return;
      imageObserver.unobserve(e.target);
      const box = e.target;
      loadImage(Number(box.dataset.id)).then((url) => {
        if (!url) return;
        box.style.backgroundImage = `url("${url}")`;
        box.classList.add('loaded');
      });
    });
  },
  { root: $('content'), rootMargin: '300px' },
);

// ------------------------------------------------------------------ Start

async function init() {
  const saved = await chrome.storage.local.get(['panelView', 'panelSort', 'panelScope', 'panelExpanded']);
  state.view = saved.panelView || 'tree';
  state.sort = saved.panelSort || 'added';
  state.scopeId = saved.panelScope ?? null;
  state.expanded = new Set(saved.panelExpanded || []);
  if (state.query) {
    $('search').value = state.query;
    if (state.view === 'tree' || state.view === 'tabs') state.view = 'list';
  }
  $('sort').value = state.sort;

  const s = await status();
  if (!s.reachable) return showState('viewOffline');
  if (!s.authorized) return showState('viewPair');
  showState(null);
  await loadMeta();
  await render();
  $('search').focus();
}

function showState(id) {
  ['viewOffline', 'viewPair'].forEach((v) => ($(v).hidden = v !== id));
  $('main').hidden = id != null;
  $('toolbar').hidden = id != null;
}

async function loadMeta() {
  const [tree, tags] = await Promise.all([call('/tree'), call('/tags')]);
  state.tree = tree;
  state.folderById = new Map(tree.folders.map((f) => [f.id, f]));
  state.kids = new Map();
  tree.folders.forEach((f) => {
    if (!state.kids.has(f.parentId)) state.kids.set(f.parentId, []);
    state.kids.get(f.parentId).push(f);
  });
  if (state.scopeId != null && !state.folderById.has(state.scopeId)) state.scopeId = null;
  state.tagColors = Object.fromEntries(tags.map((t) => [t.name.toLowerCase(), t.color]));
  state.tagNames = tags.map((t) => t.name);
  $('tagList').innerHTML = '';
  tags.forEach((t) => $('tagList').appendChild(Object.assign(el('option'), { value: t.name })));
}

/** After changes: reload tree and counters, redraw the view. */
async function refresh() {
  state.items.clear();
  await loadMeta();
  await render();
}

// ------------------------------------------------------------------ View

async function render() {
  const v = state.view;
  document.querySelectorAll('#viewSeg button').forEach((b) => b.classList.toggle('on', b.dataset.view === v));
  const content = v === 'list' || v === 'grid';
  $('scopeBar').hidden = !content;
  document.body.classList.toggle('withSide', content);
  $('scopeName').textContent = state.scopeId != null ? folderPath(state.scopeId) || tr('folder') : tr('all_bookmarks');
  $('scopeClear').hidden = state.scopeId == null;
  ['tree', 'list', 'grid', 'tabs'].forEach((id) => ($(id).hidden = id !== v));
  $('empty').hidden = true;
  $('more').hidden = true;

  if (v === 'tree') {
    if (state.query) await renderContent();
    else renderTree($('tree'), true);
  } else if (v === 'tabs') {
    await renderTabs();
  } else {
    if (WIDE) renderTree($('side'), false);
    await renderContent();
  }
}

document.querySelectorAll('#viewSeg button').forEach((b) =>
  b.addEventListener('click', async () => {
    state.view = b.dataset.view;
    state.limit = PAGE;
    persist();
    await render();
  }),
);

$('sort').addEventListener('change', async () => {
  state.sort = $('sort').value;
  state.limit = PAGE;
  persist();
  await renderContent();
});

$('scope').addEventListener('click', async () => {
  const id = await pickFolder(tr('pan_show_folder'), true);
  if (id === undefined) return;
  await setScope(id);
});
$('scopeClear').addEventListener('click', () => setScope(null));

async function setScope(id) {
  state.scopeId = id;
  state.limit = PAGE;
  if (state.view === 'tree' || state.view === 'tabs') state.view = 'list';
  persist();
  await render();
}

// ------------------------------------------------------------------ Tree

/** Draws the folder tree. withItems: bookmarks in the tree ("Tree" view), otherwise folders only (sidebar). */
function renderTree(container, withItems) {
  container.innerHTML = '';
  const root = state.tree.rootId;
  const top = state.kids.get(root) || [];
  if (!withItems) {
    const all = el('div', 'node' + (state.scopeId == null ? ' sel' : ''));
    all.append(el('span', 'caret'), el('span', 'fico', '⭐'), el('span', 'name', tr('all_bookmarks')));
    all.addEventListener('click', () => setScope(null));
    container.appendChild(all);
  }
  top.forEach((f) => container.appendChild(folderNode(f, withItems)));
  if (withItems && root != null) appendItems(container, root, true);
}

function folderNode(f, withItems) {
  const wrap = el('div');
  const hasKids = (state.kids.get(f.id) || []).length > 0 || (withItems && f.direct > 0);
  const open = state.expanded.has(f.id);
  const node = el('div', 'node folder' + (open ? ' open' : '') + (!withItems && state.scopeId === f.id ? ' sel' : ''));
  node.tabIndex = 0;
  const caret = el('span', 'caret', hasKids ? '▶' : '');
  const icon = el('span', 'fico', f.id === state.tree.inboxId ? '📥' : '📁');
  node.append(caret, icon, el('span', 'name', f.title || '(ohne Namen)'), el('span', 'cnt', String(f.count || '')));
  node.title = folderPath(f.id);
  if (withItems) {
    const act = el('button', 'act', '☰');
    act.title = tr('pan_show_as');
    act.addEventListener('click', (e) => {
      e.stopPropagation();
      setScope(f.id);
    });
    node.appendChild(act);
  }
  const kids = el('div', 'kids');
  kids.hidden = !open;
  wrap.append(node, kids);

  const fill = () => {
    if (kids.dataset.filled) return;
    kids.dataset.filled = '1';
    (state.kids.get(f.id) || []).forEach((c) => kids.appendChild(folderNode(c, withItems)));
    if (withItems) appendItems(kids, f.id, false);
  };
  if (open) fill();

  const toggle = () => {
    const now = !state.expanded.has(f.id);
    if (now) state.expanded.add(f.id);
    else state.expanded.delete(f.id);
    node.classList.toggle('open', now);
    kids.hidden = !now;
    if (now) fill();
    persist();
  };
  caret.addEventListener('click', (e) => {
    e.stopPropagation();
    toggle();
  });
  node.addEventListener('click', () => (withItems ? toggle() : setScope(f.id)));
  node.addEventListener('dblclick', () => !withItems && toggle());
  node.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' || e.key === ' ') {
      e.preventDefault();
      withItems ? toggle() : setScope(f.id);
    }
    if (e.key === 'ArrowRight' && !state.expanded.has(f.id)) toggle();
    if (e.key === 'ArrowLeft' && state.expanded.has(f.id)) toggle();
    if (e.key === 'ArrowDown' || e.key === 'ArrowUp') moveFocus(e, '.node');
  });
  node.addEventListener('contextmenu', (e) => {
    e.preventDefault();
    folderMenu(f, e.clientX, e.clientY);
  });
  dropTarget(node, f.id);
  return wrap;
}

/** Load and append the bookmarks of a folder. */
async function appendItems(container, folderId, rootLevel) {
  const holder = el('div');
  container.appendChild(holder);
  let items = state.items.get(folderId);
  if (!items) {
    if (!rootLevel) holder.appendChild(el('div', 'hint', tr('loading')));
    try {
      items = await call(`/folders/${folderId}/items`);
    } catch (e) {
      holder.innerHTML = '';
      holder.appendChild(el('div', 'hint', e.message));
      return;
    }
    state.items.set(folderId, items);
  }
  holder.innerHTML = '';
  items.forEach((it) => holder.appendChild(itemNode(it)));
}

function itemNode(it) {
  const node = el('div', 'node item' + (it.linked ? ' link' : ''));
  node.tabIndex = 0;
  const img = el('img', 'ico');
  img.src = faviconUrl(it.url, 16);
  img.alt = '';
  node.append(el('span', 'caret'), img, el('span', 'name', it.title));
  if (it.linked) node.appendChild(Object.assign(el('span', 'lk', '↪'), { title: tr('pan_link') }));
  if (it.stars != null) node.appendChild(el('span', 'stars', formatStars(it.stars)));
  node.title = [it.title, it.url, it.summary || it.description].filter(Boolean).join('\n');
  bindItem(node, it);
  return node;
}

// ------------------------------------------------------------------ List and tiles

async function renderContent() {
  const target = state.view === 'grid' ? $('grid') : state.view === 'list' ? $('list') : $('tree');
  const q = new URLSearchParams({ q: state.query, limit: String(state.limit), sort: state.sort });
  if (state.scopeId != null) q.set('folder', String(state.scopeId));
  let items = [];
  try {
    items = await call(`/search?${q}`);
  } catch (e) {
    target.innerHTML = '';
    $('empty').textContent = e.message;
    $('empty').hidden = false;
    return;
  }
  state.results = items;
  target.innerHTML = '';
  $('empty').textContent = tr('nothing_found');
  $('empty').hidden = items.length > 0;
  $('more').hidden = items.length < state.limit;
  if (state.view === 'grid') items.forEach((it) => target.appendChild(cardNode(it)));
  else items.forEach((it) => target.appendChild(rowNode(it)));
}

$('more').addEventListener('click', async () => {
  const top = $('content').scrollTop;
  state.limit += PAGE;
  await renderContent();
  $('content').scrollTop = top;
});

function tagsNode(tags) {
  const box = el('div', 'tags');
  tags.slice(0, 6).forEach((t) => {
    const s = el('span');
    const dot = el('i');
    dot.style.background = state.tagColors[t.toLowerCase()] || '#6b6b78';
    s.append(dot, document.createTextNode(t));
    box.appendChild(s);
  });
  return box;
}

function rowNode(it) {
  const row = el('div', 'row2');
  row.tabIndex = 0;
  const img = el('img', 'ico');
  img.src = faviconUrl(it.url, 16);
  img.alt = '';
  const body = el('div', 'body');
  body.appendChild(el('div', 't', it.title));
  const where = state.scopeId == null || it.folders.length > 1 ? it.folders.map((f) => f.path).join(', ') : '';
  body.appendChild(el('div', 'd', [it.domain, where].filter(Boolean).join(' · ')));
  if (it.summary || it.description) body.appendChild(el('div', 's', it.summary || it.description));
  if (it.tags.length) body.appendChild(tagsNode(it.tags));
  row.append(img, body);
  if (it.stars != null) row.appendChild(el('span', 'stars', formatStars(it.stars)));
  row.title = [it.url, it.summary || it.description].filter(Boolean).join('\n');
  bindItem(row, it);
  return row;
}

function cardNode(it) {
  const card = el('div', 'card2');
  card.tabIndex = 0;
  const box = el('div', 'img');
  box.dataset.id = it.id;
  const big = el('img', 'big');
  big.src = faviconUrl(it.url, 32);
  big.alt = '';
  box.appendChild(big);
  if (it.hasCard || it.hasThumbnail) imageObserver.observe(box);
  const info = el('div', 'info');
  info.appendChild(el('div', 't', it.title));
  const d = el('div', 'd');
  const fav = el('img');
  fav.src = faviconUrl(it.url, 16);
  fav.alt = '';
  d.append(fav, el('span', null, it.domain || ''));
  if (it.stars != null) d.appendChild(el('span', 'stars', formatStars(it.stars)));
  info.appendChild(d);
  if (it.tags.length) info.appendChild(tagsNode(it.tags));
  card.append(box, info);
  card.title = [it.title, it.url, it.summary || it.description].filter(Boolean).join('\n');
  bindItem(card, it);
  return card;
}

// ------------------------------------------------------------------ Actions for bookmarks

function bindItem(node, it) {
  node.draggable = true;
  node.addEventListener('dragstart', (e) => {
    e.dataTransfer.setData('application/x-bookmarks-plus', String(it.id));
    e.dataTransfer.setData('text/uri-list', it.url);
    e.dataTransfer.setData('text/plain', it.url);
    e.dataTransfer.effectAllowed = 'copyMove';
  });
  node.addEventListener('click', (e) => openItem(it, e));
  node.addEventListener('auxclick', (e) => {
    if (e.button === 1) {
      e.preventDefault();
      openItem(it, { ctrlKey: true });
    }
  });
  node.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') openItem(it, e);
    if (e.key === 'Delete') trashItem(it);
    if (e.key === 'ArrowDown' || e.key === 'ArrowUp') moveFocus(e, node.classList.contains('node') ? '.node' : '.row2, .card2');
    if (e.key === 'ContextMenu' || (e.key === 'F10' && e.shiftKey)) {
      e.preventDefault();
      const r = node.getBoundingClientRect();
      itemMenu(it, r.left + 20, r.bottom);
    }
  });
  node.addEventListener('contextmenu', (e) => {
    e.preventDefault();
    itemMenu(it, e.clientX, e.clientY);
  });
}

function moveFocus(e, selector) {
  e.preventDefault();
  const all = [...document.querySelectorAll(selector)].filter((n) => n.offsetParent !== null);
  const i = all.indexOf(document.activeElement);
  const next = all[Math.max(0, Math.min(all.length - 1, i + (e.key === 'ArrowDown' ? 1 : -1)))];
  if (next) next.focus();
}

/** Click: in the panel in the current tab, in the manager tab in a new tab. Ctrl/middle click: background tab. */
async function openItem(it, e = {}) {
  if (e.ctrlKey || e.metaKey) return chrome.tabs.create({ url: it.url, active: false });
  if (e.shiftKey || WIDE) return chrome.tabs.create({ url: it.url, active: true });
  const [tab] = await chrome.tabs.query({ active: true, lastFocusedWindow: true });
  if (tab) chrome.tabs.update(tab.id, { url: it.url });
  else chrome.tabs.create({ url: it.url });
}

function showMenu(entries, x, y) {
  const m = $('menu');
  m.innerHTML = '';
  entries.forEach((entry) => {
    if (entry === '-') return m.appendChild(el('hr'));
    const b = el('button', entry.danger ? 'danger' : null, entry.label);
    b.addEventListener('click', () => {
      m.hidden = true;
      entry.run();
    });
    m.appendChild(b);
  });
  m.hidden = false;
  const w = m.offsetWidth;
  const h = m.offsetHeight;
  m.style.left = Math.max(4, Math.min(x, innerWidth - w - 4)) + 'px';
  m.style.top = Math.max(4, Math.min(y, innerHeight - h - 4)) + 'px';
  m.querySelector('button')?.focus();
}
document.addEventListener('click', (e) => {
  if (!$('menu').contains(e.target)) $('menu').hidden = true;
});
document.addEventListener('keydown', (e) => {
  if (e.key === 'Escape') $('menu').hidden = true;
});
$('content').addEventListener('scroll', () => ($('menu').hidden = true));

function itemMenu(it, x, y) {
  showMenu(
    [
      { label: tr('m_open'), run: () => openItem(it) },
      { label: tr('m_open_tab'), run: () => chrome.tabs.create({ url: it.url, active: true }) },
      { label: tr('m_open_window'), run: () => chrome.windows.create({ url: it.url }) },
      { label: tr('m_copy_url'), run: () => navigator.clipboard.writeText(it.url).then(() => toast(tr('pan_url_copied'))) },
      it.repoUrl && it.repoUrl !== it.url ? { label: tr('m_open_repo'), run: () => chrome.tabs.create({ url: it.repoUrl }) } : null,
      it.websiteUrl && it.websiteUrl !== it.url ? { label: tr('m_open_website'), run: () => chrome.tabs.create({ url: it.websiteUrl }) } : null,
      '-',
      { label: tr('m_rename'), run: () => renameItem(it) },
      { label: tr('m_edit_tags'), run: () => editTags(it) },
      { label: tr('m_move_to'), run: () => moveItem(it) },
      { label: tr('m_link_with'), run: () => linkItem(it) },
      '-',
      { label: tr('m_trash'), danger: true, run: () => trashItem(it) },
    ].filter(Boolean),
    x,
    y,
  );
}

function folderMenu(f, x, y) {
  showMenu(
    [
      { label: tr('m_show_list'), run: () => ((state.view = 'list'), setScope(f.id)) },
      { label: tr('m_show_grid'), run: () => ((state.view = 'grid'), setScope(f.id)) },
      { label: tr('m_open_all_window'), run: () => openFolder(f) },
    ],
    x,
    y,
  );
}

async function openFolder(f) {
  const items = state.items.get(f.id) || (await call(`/folders/${f.id}/items`));
  if (!items.length) return toast(tr('pan_folder_empty'));
  if (items.length > 20 && !confirm(tr('pan_open_tabs', items.length))) return;
  chrome.windows.create({ url: items.map((i) => i.url) });
}

async function renameItem(it) {
  const title = prompt(tr('title'), it.title);
  if (title == null || !title.trim() || title === it.title) return;
  await mutate(() => call(`/bookmarks/${it.id}`, { method: 'PATCH', body: { title } }), tr('pan_renamed'));
}

async function moveItem(it) {
  const id = await pickFolder(tr('pan_move_title', it.title));
  if (id == null) return;
  await mutate(() => call(`/bookmarks/${it.id}`, { method: 'PATCH', body: { folderId: id } }), tr('pan_moved_to', folderPath(id)));
}

async function linkItem(it) {
  const id = await pickFolder(tr('pan_link_title', it.title));
  if (id == null) return;
  await mutate(() => call(`/bookmarks/${it.id}/link`, { method: 'POST', body: { folderId: id } }), tr('pan_linked_in', folderPath(id)));
}

async function trashItem(it) {
  await mutate(() => call(`/bookmarks/${it.id}`, { method: 'DELETE' }), tr('pan_trashed'), [
    tr('undo'),
    () => mutate(() => call(`/bookmarks/${it.id}/restore`, { method: 'POST' }), tr('pan_restored')),
  ]);
}

async function mutate(fn, okText, action) {
  try {
    await fn();
    toast(okText, action);
    await refresh();
  } catch (e) {
    toast(e.message);
  }
}

/** Drag bookmark onto a folder: move, with Ctrl/Alt link. */
function dropTarget(node, folderId) {
  node.addEventListener('dragover', (e) => {
    if (!e.dataTransfer.types.includes('application/x-bookmarks-plus')) return;
    e.preventDefault();
    e.dataTransfer.dropEffect = e.ctrlKey || e.altKey ? 'copy' : 'move';
    node.classList.add('drop');
  });
  node.addEventListener('dragleave', () => node.classList.remove('drop'));
  node.addEventListener('drop', async (e) => {
    node.classList.remove('drop');
    const id = Number(e.dataTransfer.getData('application/x-bookmarks-plus'));
    if (!id) return;
    e.preventDefault();
    const where = folderPath(folderId);
    if (e.ctrlKey || e.altKey) {
      await mutate(() => call(`/bookmarks/${id}/link`, { method: 'POST', body: { folderId } }), tr('pan_linked_in', where));
    } else {
      await mutate(() => call(`/bookmarks/${id}`, { method: 'PATCH', body: { folderId } }), tr('pan_moved_to', where));
    }
  });
}

// ------------------------------------------------------------------ Folder selection

/** Returns the folder ID, null for "All" (only with allowAll) or undefined on cancel. */
function pickFolder(title, allowAll = false) {
  const dlg = $('picker');
  $('pickerTitle').textContent = title;
  $('pickerFilter').value = '';
  const all = state.tree.folders.map((f) => ({ id: f.id, path: folderPath(f.id), title: f.title }));
  let shown = [];
  let active = 0;
  let result;

  const draw = () => {
    const q = $('pickerFilter').value.trim().toLowerCase();
    shown = (q ? all.filter((f) => f.path.toLowerCase().includes(q)) : all).slice(0, 200);
    if (allowAll && !q) shown = [{ id: null, path: tr('all_bookmarks'), title: tr('all_bookmarks') }, ...shown];
    active = Math.min(active, Math.max(0, shown.length - 1));
    const ul = $('pickerList');
    ul.innerHTML = '';
    shown.forEach((f, i) => {
      const li = el('li', i === active ? 'active' : null);
      const parent = f.path.slice(0, Math.max(0, f.path.length - f.title.length));
      if (parent) li.appendChild(el('span', 'p', parent));
      li.appendChild(document.createTextNode(f.title));
      li.addEventListener('click', () => {
        result = f.id;
        dlg.close('ok');
      });
      ul.appendChild(li);
    });
    ul.children[active]?.scrollIntoView({ block: 'nearest' });
  };
  $('pickerFilter').oninput = () => ((active = 0), draw());
  $('pickerFilter').onkeydown = (e) => {
    if (e.key === 'ArrowDown') ((active = Math.min(active + 1, shown.length - 1)), draw(), e.preventDefault());
    if (e.key === 'ArrowUp') ((active = Math.max(active - 1, 0)), draw(), e.preventDefault());
    if (e.key === 'Enter') {
      e.preventDefault();
      if (shown[active]) {
        result = shown[active].id;
        dlg.close('ok');
      }
    }
  };
  draw();
  return new Promise((resolve) => {
    dlg.onclose = () => resolve(dlg.returnValue === 'ok' ? result : undefined);
    dlg.returnValue = '';
    dlg.showModal();
    $('pickerFilter').focus();
  });
}

// ------------------------------------------------------------------ Edit tags

let editTagsList = [];
function drawTags() {
  const box = $('tagBox');
  box.querySelectorAll('.tag').forEach((n) => n.remove());
  editTagsList.forEach((name, i) => {
    const t = el('span', 'tag');
    const dot = el('span', 'dot');
    dot.style.background = state.tagColors[name.toLowerCase()] || '#6b6b78';
    const x = el('button', null, '×');
    x.type = 'button';
    x.title = tr('remove');
    x.addEventListener('click', () => {
      editTagsList.splice(i, 1);
      drawTags();
    });
    t.append(dot, el('span', null, name), x);
    box.insertBefore(t, $('tagInput'));
  });
}
function takeTagInput() {
  $('tagInput').value
    .split(',')
    .map((s) => s.trim().replace(/^#/, ''))
    .filter(Boolean)
    .forEach((n) => {
      if (!editTagsList.some((t) => t.toLowerCase() === n.toLowerCase())) editTagsList.push(n);
    });
  $('tagInput').value = '';
  drawTags();
}
$('tagInput').addEventListener('keydown', (e) => {
  if (e.key === 'Enter' || e.key === ',') {
    e.preventDefault();
    if (e.key === 'Enter' && !$('tagInput').value.trim()) return $('tagSave').click();
    takeTagInput();
  } else if (e.key === 'Backspace' && !$('tagInput').value && editTagsList.length) {
    editTagsList.pop();
    drawTags();
  }
});
$('tagInput').addEventListener('change', takeTagInput);

function editTags(it) {
  editTagsList = [...it.tags];
  $('tagDlgTitle').textContent = tr('pan_tags_title', it.title);
  drawTags();
  const dlg = $('tagDlg');
  dlg.returnValue = '';
  dlg.onclose = () => {
    if (dlg.returnValue !== 'ok') return;
    takeTagInput();
    mutate(() => call(`/bookmarks/${it.id}`, { method: 'PATCH', body: { tags: editTagsList } }), tr('pan_tags_saved'));
  };
  dlg.showModal();
  $('tagInput').focus();
}

// ------------------------------------------------------------------ Open tabs

const savedCache = new Map(); // URL → bookmark or null

async function renderTabs() {
  const box = $('tabs');
  const q = state.query.toLowerCase();
  const wins = await chrome.windows.getAll({ populate: true, windowTypes: ['normal'] });
  const current = await chrome.windows.getLastFocused().catch(() => null);
  wins.sort((a, b) => (b.id === current?.id) - (a.id === current?.id));
  box.innerHTML = '';
  let shown = 0;
  wins.forEach((w, wi) => {
    const tabs = (w.tabs || []).filter((t) => !q || (t.title || '').toLowerCase().includes(q) || (t.url || '').toLowerCase().includes(q));
    if (!tabs.length) return;
    shown += tabs.length;
    const key = 'w' + w.id;
    const open = !state.expanded.has(key); // windows are open by default
    const node = el('div', 'node folder' + (open ? ' open' : ''));
    node.tabIndex = 0;
    const label = w.id === current?.id ? tr('pan_this_window') : tr('pan_window_n', wi + 1);
    node.append(el('span', 'caret', '▶'), el('span', 'fico', '🪟'), el('span', 'name', label), el('span', 'cnt', String(tabs.length)));
    const save = el('button', 'act', tr('pan_plus_all'));
    save.title = tr('pan_save_window');
    save.addEventListener('click', (e) => {
      e.stopPropagation();
      saveTabs(tabs);
    });
    node.appendChild(save);
    const kids = el('div', 'kids');
    kids.hidden = !open;
    node.addEventListener('click', () => {
      const now = kids.hidden;
      kids.hidden = !now;
      node.classList.toggle('open', now);
      if (now) state.expanded.delete(key);
      else state.expanded.add(key);
    });
    tabs.forEach((t) => kids.appendChild(tabNode(t)));
    box.append(node, kids);
  });
  $('empty').textContent = tr('pan_no_tabs');
  $('empty').hidden = shown > 0;
}

function tabNode(t) {
  const node = el('div', 'node item' + (t.active ? ' activeTab' : ''));
  node.tabIndex = 0;
  const img = el('img', 'ico');
  img.src = t.favIconUrl && !t.favIconUrl.startsWith('chrome://') ? t.favIconUrl : faviconUrl(t.url || '', 16);
  img.alt = '';
  img.onerror = () => {
    img.onerror = null; // only fall back once, otherwise endless loop
    img.src = faviconUrl(t.url || '', 16);
  };
  node.append(el('span', 'caret'), img, el('span', 'name', t.title || t.url));
  if (t.pinned) node.appendChild(el('span', 'lk', '📌'));
  node.title = t.url || '';
  const mark = el('span', 'ok');
  node.appendChild(mark);
  const save = el('button', 'act', '＋');
  save.title = tr('pan_save_inbox');
  save.addEventListener('click', (e) => {
    e.stopPropagation();
    saveTabs([t]);
  });
  const close = el('button', 'act', '×');
  close.title = tr('pan_close_tab');
  close.addEventListener('click', (e) => {
    e.stopPropagation();
    chrome.tabs.remove(t.id);
  });
  node.append(save, close);
  if (isSavable(t.url)) {
    const hit = savedCache.get(t.url);
    const apply = (item) => {
      if (!item) return;
      node.classList.add('isSaved');
      mark.textContent = '✓';
      mark.title = tr('badge_saved_in', item.folders.map((f) => f.path).join(', '));
      save.remove();
    };
    if (hit !== undefined) apply(hit);
    else
      call(`/lookup?url=${encodeURIComponent(t.url)}`)
        .then((r) => {
          const item = r.exists && !r.item.trashed ? r.item : null;
          savedCache.set(t.url, item);
          apply(item);
        })
        .catch(() => {});
  } else {
    save.remove();
  }
  node.addEventListener('click', () => {
    chrome.tabs.update(t.id, { active: true });
    chrome.windows.update(t.windowId, { focused: true });
  });
  node.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') node.click();
    if (e.key === 'ArrowDown' || e.key === 'ArrowUp') moveFocus(e, '.node');
  });
  return node;
}

async function saveTabs(tabs) {
  const list = tabs.filter((t) => isSavable(t.url));
  let created = 0;
  for (const t of list) {
    try {
      const r = await call('/bookmarks', { method: 'POST', body: { url: t.url, title: t.title || '' } });
      if (r.created) created++;
      savedCache.set(t.url, r.item);
      chrome.runtime.sendMessage({ type: 'refresh-badge', tabId: t.id, url: t.url });
    } catch (e) {
      return toast(e.message);
    }
  }
  toast(list.length === 1 ? (created ? tr('pan_saved_inbox') : tr('already_saved')) : tr('pan_tabs_saved', created, list.length));
  state.items.clear();
  await loadMeta();
  if (state.view === 'tabs') renderTabs();
}

let tabsTimer = null;
function tabsChanged() {
  if (state.view !== 'tabs') return;
  clearTimeout(tabsTimer);
  tabsTimer = setTimeout(renderTabs, 250);
}
chrome.tabs.onCreated.addListener(tabsChanged);
chrome.tabs.onRemoved.addListener(tabsChanged);
chrome.tabs.onActivated.addListener(tabsChanged);
chrome.tabs.onMoved.addListener(tabsChanged);
chrome.tabs.onUpdated.addListener((id, change) => {
  if (change.title || change.status === 'complete' || change.pinned !== undefined) tabsChanged();
});

// ------------------------------------------------------------------ Search, header

let searchTimer = null;
$('search').addEventListener('input', () => {
  clearTimeout(searchTimer);
  searchTimer = setTimeout(async () => {
    const q = $('search').value.trim();
    if (q === state.query) return;
    state.query = q;
    state.limit = PAGE;
    if (state.view === 'tabs') return renderTabs();
    if (q && state.view === 'tree') state.view = 'list';
    await render();
  }, 160);
});
$('search').addEventListener('keydown', (e) => {
  if (e.key === 'Escape' && $('search').value) {
    e.preventDefault();
    $('search').value = '';
    $('search').dispatchEvent(new Event('input'));
  }
  if (e.key === 'ArrowDown') {
    e.preventDefault();
    document.querySelector('#content :is(.node, .row2, .card2)')?.focus();
  }
  if (e.key === 'Enter' && state.results.length && (state.view === 'list' || state.view === 'grid')) openItem(state.results[0], e);
});

$('savePage').addEventListener('click', async () => {
  const [tab] = await chrome.tabs.query({ active: true, lastFocusedWindow: true });
  if (!tab || !isSavable(tab.url)) return toast(tr('pan_not_savable'));
  await saveTabs([tab]);
  if (state.view !== 'tabs') await render();
});
$('openTab').addEventListener('click', () => chrome.runtime.sendMessage({ type: 'open-manager', query: state.query }));
$('openOptions').addEventListener('click', () => chrome.runtime.openOptionsPage());
$('retry').addEventListener('click', () => init());

$('pair').addEventListener('click', async () => {
  $('pair').disabled = true;
  try {
    const { requestId, code } = await pairStart();
    $('code').textContent = code;
    $('pairCode').hidden = false;
    const ok = await pairWait(requestId, (i) => ($('pairWait').textContent = tr('pair_waiting_s', 120 - i)));
    if (ok) await init();
    else {
      $('pairWait').textContent = tr('pair_rejected');
      $('pair').disabled = false;
    }
  } catch (e) {
    $('pairWait').textContent = e.message;
    $('pairCode').hidden = false;
    $('pair').disabled = false;
  }
});

// Adopt changes from the app as soon as the panel becomes visible again
document.addEventListener('visibilitychange', () => {
  if (document.visibilityState === 'visible' && !$('main').hidden) refresh().catch(() => {});
});

ready.then(() => { applyI18n(); return init(); }).catch((e) => {
  console.error(e);
  showState('viewOffline');
});
