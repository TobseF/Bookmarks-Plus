import { call, captureThumbnail, uploadThumbnail, faviconUrl, isSavable, pageInfo, pairStart, pairWait, status } from './api.js';
import { applyI18n, ready, t as tr } from './i18n.js';

const $ = (id) => document.getElementById(id);
$('version').textContent = 'v' + chrome.runtime.getManifest().version;
const views = ['viewOffline', 'viewPair', 'viewPage', 'viewNotSavable', 'viewSearch'];
let tab = null;
let existing = null;
let folders = [];
let tagColors = {};
let tags = [];
let pageView = 'viewPage';

function show(id) {
  views.forEach((v) => ($(v).hidden = v !== id));
}

function message(text, error = false) {
  $('message').textContent = text || '';
  $('message').style.color = error ? 'var(--accent)' : '';
}

// ------------------------------------------------------------------ Start

async function init() {
  [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
  const s = await status();
  if (!s.reachable) return show('viewOffline');
  if (!s.authorized) return show('viewPair');
  $('search').focus();
  await loadPage();
}

async function loadPage() {
  if (!tab || !isSavable(tab.url)) {
    pageView = 'viewNotSavable';
    return show(pageView);
  }
  pageView = 'viewPage';
  show(pageView);
  $('favicon').src = tab.favIconUrl || faviconUrl(tab.url);
  $('title').value = tab.title || '';
  $('url').textContent = tab.url;

  const [lookup, f, t] = await Promise.all([
    call(`/lookup?url=${encodeURIComponent(tab.url)}&domain=1`),
    call('/folders'),
    call('/tags'),
  ]);
  folders = f;
  tagColors = Object.fromEntries(t.map((x) => [x.name.toLowerCase(), x.color]));
  $('folders').innerHTML = folders.map((x) => `<option value="${escapeHtml(x.path)}">`).join('');
  $('tagList').innerHTML = t.map((x) => `<option value="${escapeHtml(x.name)}">`).join('');

  existing = lookup.exists ? lookup.item : null;
  hideDomainBox();
  $('thumbBox').hidden = true;
  $('thumbFrame').hidden = true;
  if (existing) {
    $('title').value = existing.title;
    $('folder').value = existing.folders[0]?.path || '';
    $('note').value = existing.description || '';
    tags = [...existing.tags];
    $('thumbBox').hidden = existing.trashed;
    $('savedInfo').hidden = false;
    $('savedInfo').textContent =
      (existing.trashed ? tr('pop_in_trash') : tr('pop_saved')) +
      (existing.folders.length ? tr('pop_in_folders', existing.folders.map((x) => x.path).join(', ')) : '') +
      (existing.summary ? ' — ' + existing.summary : '');
    $('save').textContent = tr('pop_save_changes');
  } else {
    const info = await pageInfo(tab.id);
    $('note').value = info.selection || '';
    $('save').textContent = tr('save');
    renderDomainBox(lookup);
  }
  renderTags();
  loadAppBox();
}

// ------------------------------------------------------------------ Same domain (URL itself not stored)

let domainIds = [];

function hideDomainBox() {
  $('domainBox').hidden = true;
  domainIds = [];
}

function tagList(extraFrom) {
  // keep existing tags of the entry and add those entered in the form
  const out = [...(extraFrom || [])];
  tags.forEach((t) => { if (!out.some((x) => x.toLowerCase() === t.toLowerCase())) out.push(t); });
  return out;
}

/** Details from the form for an existing bookmark. The stored address remains unchanged (main domain without parameters). */
function updateBody(item, withTitle) {
  addTagFromInput();
  const fid = folderId();
  const body = {};
  if (withTitle && $('title').value.trim()) body.title = $('title').value;
  if ($('note').value.trim()) body.description = $('note').value;
  if (fid) body.folderId = fid;
  if (tags.length) body.tags = tagList(item && item.tags);
  return body;
}

function renderDomainBox(lookup) {
  hideDomainBox();
  if (!lookup.domainIds || !lookup.domainIds.length) return;
  domainIds = lookup.domainIds;
  const total = domainIds.length;
  const shown = lookup.domainMatches;
  $('domainBox').hidden = false;
  $('domainHint').textContent =
    tr('pop_domain_hint', total === 1 ? tr('pop_one_bookmark') : tr('n_bookmarks', total), lookup.domain);
  $('domainMatches').innerHTML = '';
  for (const m of shown) {
    const li = document.createElement('li');
    li.className = 'domainRow';
    li.innerHTML = `<div class="t"><div>${escapeHtml(m.title)}</div><div class="r">${escapeHtml(m.url)}</div></div>`;
    const thumb = document.createElement('button');
    thumb.type = 'button';
    thumb.textContent = tr('pop_thumb_btn');
    thumb.title = tr('pop_thumb_tip');
    thumb.addEventListener('click', () => applyTo(thumb, m, { thumb: true, fields: false }));
    const fields = document.createElement('button');
    fields.type = 'button';
    fields.textContent = tr('pop_fields_btn');
    fields.title = tr('pop_fields_tip');
    fields.addEventListener('click', () => applyTo(fields, m, { thumb: false, fields: true, title: true }));
    li.append(thumb, fields);
    $('domainMatches').appendChild(li);
  }
  $('domainAll').hidden = total <= 3;
  if (total > 3) {
    $('domainAllInfo').textContent = tr('pop_more_domain', total - shown.length, lookup.domain);
    $('domainAllWarn').hidden = true;
    $('domainAllStart').hidden = false;
    $('domainAllStart').textContent = tr('pop_apply_all', total);
    $('domainAllStart').onclick = () => {
      $('domainAllStart').hidden = true;
      $('domainAllWarn').hidden = false;
      $('domainAllWarnText').textContent =
        tr('pop_domain_warn', total, lookup.domain);
      $('domainAllYes').textContent = tr('pop_yes_change', total);
      $('domainAllYes').onclick = () => applyAll(total);
      $('domainAllNo').onclick = () => {
        $('domainAllWarn').hidden = true;
        $('domainAllStart').hidden = false;
      };
    };
  }
  $('save').textContent = tr('pop_create_inbox');
}

/** Capture thumbnail of the displayed page (once) and send form details to a bookmark; the address stays. */
async function applyOne(id, item, opts, shot) {
  if (opts.thumb && shot) await uploadThumbnail(id, shot.blob);
  const body = opts.fields ? updateBody(item, !!opts.title) : null;
  if (body && Object.keys(body).length) await call(`/bookmarks/${id}`, { method: 'PATCH', body, timeout: 30000 });
}

async function applyTo(button, m, opts) {
  const buttons = [...document.querySelectorAll('#domainBox button')];
  buttons.forEach((x) => (x.disabled = true));
  const old = button.textContent;
  button.textContent = '…';
  let shot = null;
  try {
    if (opts.thumb) shot = await captureThumbnail(tab.windowId);
    await applyOne(m.id, m, opts, shot);
    chrome.runtime.sendMessage({ type: 'refresh-badge', tabId: tab.id, url: tab.url });
    button.textContent = '✓';
    $('domainHint').textContent = tr('pop_updated_one', m.title);
  } catch (e) {
    button.textContent = old;
    $('domainHint').textContent = e.message || tr('failed');
  } finally {
    if (shot) URL.revokeObjectURL(shot.url);
    buttons.forEach((x) => (x.disabled = false));
  }
}

async function applyAll(total) {
  const buttons = [...document.querySelectorAll('#domainBox button')];
  buttons.forEach((x) => (x.disabled = true));
  let ok = 0;
  let shot = null;
  try {
    shot = await captureThumbnail(tab.windowId);
    for (const id of domainIds) {
      await applyOne(id, null, { thumb: true, fields: true, title: false }, shot);
      ok++;
    }
    $('domainHint').textContent = tr('pop_updated_many', ok);
    $('domainMatches').innerHTML = '';
    $('domainAll').hidden = true;
  } catch (e) {
    $('domainHint').textContent = tr('pop_updated_partial', ok, total, e.message);
    buttons.forEach((x) => (x.disabled = false));
  } finally {
    if (shot) URL.revokeObjectURL(shot.url);
  }
}


// ------------------------------------------------------------------ Program pages (Flathub / AlternativeTo)

const APP_PAGE = /^https:\/\/(?:www\.)?(?:flathub\.org\/(?:[a-z]{2}(?:-[A-Za-z]{2,4})?\/)?apps\/(?:details\/)?[\w-]+(?:\.[\w-]+)+|alternativeto\.net\/software\/[a-z0-9-]+)/i;
let appHtml = null;

/** Source text of the opened page (AlternativeTo: the app needs no fetch of its own, no throttle, no bot check). */
async function pageHtml() {
  const [r] = await chrome.scripting.executeScript({ target: { tabId: tab.id }, func: () => document.documentElement.outerHTML });
  return r && r.result;
}

async function loadAppBox() {
  $('appBox').hidden = true;
  if (!tab || !APP_PAGE.test(tab.url)) return;
  const at = /alternativeto\.net/i.test(tab.url);
  $('appBox').hidden = false;
  $('appSource').className = 'appSource ' + (at ? 'alternativeto' : 'flathub');
  $('appSource').textContent = at ? 'AlternativeTo' : 'Flathub';
  $('appName').textContent = '';
  $('appHint').textContent = tr('pop_app_reading');
  $('appMatches').innerHTML = '';
  $('appCreate').hidden = true;
  try {
    appHtml = at ? await pageHtml().catch(() => null) : null;
    const r = await call('/apps/page', { method: 'POST', body: { url: tab.url, html: appHtml }, timeout: 20000 });
    renderApp(r);
  } catch (e) {
    $('appHint').textContent = e.status === 404 ? tr('pop_app_too_old') : e.message;
  }
}

function renderApp(r) {
  $('appName').textContent = r.name;
  const linked = r.matches.filter((m) => m.linked);
  const domainShown = r.matches.filter((m) => m.group === 'domain').length;
  $('appHint').textContent = r.exact
    ? (linked.length ? tr('pop_app_linked') : tr('pop_app_found'))
    : r.matches.length
      ? (r.domain ? tr('pop_app_inexact_domain', r.domain) : tr('pop_app_inexact'))
      : tr('pop_app_none');
  $('appMatches').innerHTML = '';
  for (const m of r.matches) {
    const li = document.createElement('li');
    li.innerHTML = `<div class="t"><div>${escapeHtml(m.item.title)}</div><div class="r">${escapeHtml(m.reason)} · ${escapeHtml(m.item.domain || '')}</div></div>`;
    const b = document.createElement('button');
    b.type = 'button';
    b.textContent = m.linked ? tr('update') : m.group === 'exact' ? tr('apply') : tr('pop_update_this');
    b.addEventListener('click', () => applyApp(b, { apply: m.item.id }));
    li.appendChild(b);
    $('appMatches').appendChild(li);
  }
  // More bookmarks of the same domain than displayed: adopt for all – only after explicit confirmation
  const more = r.domainTotal - domainShown;
  $('appAll').hidden = true;
  if (!r.exact && r.domainTotal > 3) {
    $('appAll').hidden = false;
    $('appAllInfo').textContent = tr('pop_more_bookmarks_domain', more, r.domain);
    $('appAllWarn').hidden = true;
    $('appAllStart').hidden = false;
    $('appAllStart').textContent = tr('pop_apply_all', r.domainTotal);
    $('appAllStart').onclick = () => {
      $('appAllStart').hidden = true;
      $('appAllWarn').hidden = false;
      $('appAllWarnText').textContent =
        tr('pop_app_warn', r.domainTotal, r.domain);
      $('appAllYes').textContent = tr('pop_yes_change', r.domainTotal);
      $('appAllYes').onclick = () => applyApp($('appAllYes'), { applyDomain: r.domainTotal });
      $('appAllNo').onclick = () => {
        $('appAllWarn').hidden = true;
        $('appAllStart').hidden = false;
      };
    };
  }
  $('appCreate').textContent = tr('pop_app_create');
  $('appCreate').hidden = r.exact;
}

async function applyApp(button, extra) {
  const buttons = [...document.querySelectorAll('#appBox button')];
  buttons.forEach((x) => (x.disabled = true));
  const old = button.textContent;
  button.textContent = '…';
  try {
    const r = await call('/apps/page', { method: 'POST', body: { url: tab.url, html: appHtml, ...extra }, timeout: 60000 });
    if (r.message) throw new Error(r.message);
    button.textContent = tr('pop_applied');
    button.classList.add('done');
    $('appHint').textContent = r.appliedCount
      ? tr('pop_app_applied_many', r.name, r.appliedCount)
      : `✓ ${r.name} → ${r.applied ? r.applied.title : ''}`;
    if (r.appliedCount) {
      $('appAll').hidden = true;
      $('appMatches').innerHTML = '';
      $('appCreate').hidden = true;
    }
    if (extra.create) $('appCreate').hidden = true;
  } catch (e) {
    button.textContent = old;
    $('appHint').textContent = e.message;
    buttons.forEach((x) => (x.disabled = false));
  }
}

$('appCreate').addEventListener('click', () => applyApp($('appCreate'), { create: true }));

// ------------------------------------------------------------------ Pairing

$('pair').addEventListener('click', async () => {
  $('pair').disabled = true;
  try {
    const { requestId, code } = await pairStart();
    $('code').textContent = code;
    $('pairCode').hidden = false;
    const ok = await pairWait(requestId, (i) => ($('pairWait').textContent = tr('pair_waiting_s', 120 - i)));
    if (ok) {
      await loadPage();
    } else {
      $('pairWait').textContent = tr('pair_rejected');
      $('pair').disabled = false;
    }
  } catch (e) {
    $('pairWait').textContent = e.message;
    $('pairCode').hidden = false;
    $('pair').disabled = false;
  }
});

$('retry').addEventListener('click', init);
$('openOptions').addEventListener('click', () => chrome.runtime.openOptionsPage());

// ------------------------------------------------------------------ Tags

function renderTags() {
  const box = $('tagBox');
  box.querySelectorAll('.tag').forEach((el) => el.remove());
  tags.forEach((name, i) => {
    const el = document.createElement('span');
    el.className = 'tag';
    el.innerHTML = `<span class="dot"></span><span></span><button title="${tr('remove')}">×</button>`;
    el.querySelector('.dot').style.background = tagColors[name.toLowerCase()] || '#6b6b78';
    el.children[1].textContent = name;
    el.querySelector('button').addEventListener('click', () => {
      tags.splice(i, 1);
      renderTags();
    });
    box.insertBefore(el, $('tagInput'));
  });
}

function addTagFromInput() {
  const v = $('tagInput').value.trim().replace(/^#/, '');
  v.split(',').map((s) => s.trim()).filter(Boolean).forEach((name) => {
    if (!tags.some((t) => t.toLowerCase() === name.toLowerCase())) tags.push(name);
  });
  $('tagInput').value = '';
  renderTags();
}

$('tagInput').addEventListener('keydown', (e) => {
  if (e.key === 'Enter' || e.key === ',') {
    e.preventDefault();
    addTagFromInput();
  } else if (e.key === 'Backspace' && !$('tagInput').value && tags.length) {
    tags.pop();
    renderTags();
  }
});
$('tagInput').addEventListener('change', addTagFromInput);

// ------------------------------------------------------------------ Thumbnail

$('thumb').addEventListener('click', async () => {
  if (!existing) return message(tr('pop_thumb_not_saved'), true);
  $('thumb').disabled = true;
  message('');
  let previewUrl = null;
  try {
    const shot = await captureThumbnail(tab.windowId);
    previewUrl = shot.url;
    // briefly show preview in the matching aspect ratio
    $('thumbPreview').src = previewUrl;
    $('thumbFrame').hidden = false;
    await uploadThumbnail(existing.id, shot.blob);
    message(tr('pop_thumb_updated'));
    await new Promise((ok) => setTimeout(ok, 2200));
    $('thumbFrame').hidden = true;
  } catch (e) {
    $('thumbFrame').hidden = true;
    message(e.message || tr('pop_capture_failed'), true);
  } finally {
    if (previewUrl) URL.revokeObjectURL(previewUrl);
    $('thumb').disabled = false;
  }
});

// ------------------------------------------------------------------ Save

function folderId() {
  const path = $('folder').value.trim();
  if (!path) return null;
  const hit = folders.find((f) => f.path.toLowerCase() === path.toLowerCase());
  return hit ? hit.id : undefined;
}

async function save() {
  addTagFromInput();
  const fid = folderId();
  if (fid === undefined) return message(tr('pop_folder_not_found'), true);
  $('save').disabled = true;
  message('');
  try {
    let item;
    if (existing) {
      item = await call(`/bookmarks/${existing.id}`, {
        method: 'PATCH',
        body: {
          title: $('title').value !== existing.title ? $('title').value : null,
          description: $('note').value !== (existing.description || '') ? $('note').value : null,
          folderId: fid ?? null,
          tags,
        },
      });
      message(tr('saved_check'));
    } else {
      const r = await call('/bookmarks', {
        method: 'POST',
        body: { url: tab.url, title: $('title').value, description: $('note').value || null, folderId: fid ?? null, tags },
      });
      item = r.item;
      message(r.created ? tr('saved_check') : tr('already_saved'));
    }
    existing = item;
    chrome.runtime.sendMessage({ type: 'refresh-badge', tabId: tab.id, url: tab.url });
    setTimeout(() => window.close(), 700);
  } catch (e) {
    message(e.message, true);
  } finally {
    $('save').disabled = false;
  }
}

$('save').addEventListener('click', save);
document.addEventListener('keydown', (e) => {
  if (e.key === 'Enter' && (e.ctrlKey || e.metaKey) && !$('viewPage').hidden) save();
});

// ------------------------------------------------------------------ Search

let searchTimer = null;
let results = [];
let active = -1;

$('search').addEventListener('input', () => {
  clearTimeout(searchTimer);
  const q = $('search').value.trim();
  if (!q) return show(pageView);
  searchTimer = setTimeout(() => runSearch(q), 150);
});

async function runSearch(q) {
  try {
    results = await call(`/search?q=${encodeURIComponent(q)}&limit=40`);
  } catch (e) {
    results = [];
  }
  active = results.length ? 0 : -1;
  renderResults();
  show('viewSearch');
}

function renderResults() {
  const ul = $('results');
  ul.innerHTML = '';
  $('noResults').hidden = results.length > 0;
  results.forEach((r, i) => {
    const li = document.createElement('li');
    if (i === active) li.className = 'active';
    li.innerHTML = `<img alt=""><div class="body"><div class="t"></div><div class="d"></div></div>`;
    li.querySelector('img').src = faviconUrl(r.url, 16);
    li.querySelector('.t').textContent = r.title;
    li.querySelector('.d').textContent =
      [r.domain, r.folders[0]?.path, r.tags.map((t) => '#' + t).join(' ')].filter(Boolean).join(' · ');
    li.title = r.summary || r.description || r.url;
    li.addEventListener('click', (e) => open(r, e.ctrlKey || e.metaKey || e.button === 1));
    ul.appendChild(li);
  });
}

function open(r, background) {
  chrome.tabs.create({ url: r.url, active: !background });
  if (!background) window.close();
}

$('search').addEventListener('keydown', (e) => {
  if ($('viewSearch').hidden || !results.length) return;
  if (e.key === 'ArrowDown') { active = Math.min(active + 1, results.length - 1); renderResults(); e.preventDefault(); }
  if (e.key === 'ArrowUp') { active = Math.max(active - 1, 0); renderResults(); e.preventDefault(); }
  if (e.key === 'Enter' && active >= 0) open(results[active], e.ctrlKey);
  if (e.key === 'Escape') { $('search').value = ''; show(pageView); e.preventDefault(); }
});

function escapeHtml(s) {
  return String(s).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' })[c]);
}

ready.then(() => { applyI18n(); return init(); }).catch((e) => {
  show('viewOffline');
  console.error(e);
});
