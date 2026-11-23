// Background: context menu, keyboard shortcuts, checkmark on the icon, address bar search (keyword "b"), side panel.
import { call, getConfig, isSavable, pageInfo } from './api.js';
import { installTaskPolling } from './tasks.js';
import { ready, t as tr } from './i18n.js';

const GREEN = '#30A46C';
const RED = '#E5484D';

function createMenus() {
  chrome.contextMenus.removeAll(() => {
    chrome.contextMenus.create({ id: 'save-page', title: tr('menu_save_page'), contexts: ['page'] });
    chrome.contextMenus.create({ id: 'save-link', title: tr('menu_save_link'), contexts: ['link'] });
    chrome.contextMenus.create({ id: 'open-panel', title: tr('menu_open_panel'), contexts: ['action'] });
    chrome.contextMenus.create({ id: 'open-manager', title: tr('menu_open_manager'), contexts: ['action'] });
  });
}

chrome.runtime.onInstalled.addListener(() => ready.then(createMenus));
// language changed in the options → relabel menu and notice
chrome.storage.onChanged.addListener((changes, area) => {
  if (area === 'local' && changes.uiLang) ready.then(() => { createMenus(); chrome.omnibox.setDefaultSuggestion({ description: DEFAULT_HINT() }); });
});

// ------------------------------------------------------------------ Side panel / manager

function openPanel(tab) {
  // must be called synchronously in the user action
  if (!chrome.sidePanel || !chrome.sidePanel.open) return openManager();
  chrome.sidePanel.open({ windowId: tab.windowId }).catch(() => openManager());
}

function openManager(query) {
  const url = chrome.runtime.getURL('panel.html') + '?mode=tab' + (query ? '&q=' + encodeURIComponent(query) : '');
  chrome.tabs.create({ url });
}

// ------------------------------------------------------------------ Address bar: "b" + space + search term

const DEFAULT_HINT = () => tr('omni_hint');
ready.then(() => chrome.omnibox.setDefaultSuggestion({ description: DEFAULT_HINT() }));

/** Text for the address bar: only &, <, > and quotation marks are special characters there (XML). */
function xml(s) {
  return String(s || '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&apos;' })[c]);
}

let omniTimer = null;
let omniSeq = 0;
const omniCache = new Map(); // search term → first hit

chrome.omnibox.onInputStarted.addListener(async () => {
  await ready;
  const { token } = await getConfig();
  chrome.omnibox.setDefaultSuggestion({
    description: token ? DEFAULT_HINT() : tr('omni_not_connected'),
  });
});

chrome.omnibox.onInputChanged.addListener((text, suggest) => {
  clearTimeout(omniTimer);
  const q = text.trim();
  if (!q) return;
  const seq = ++omniSeq;
  omniTimer = setTimeout(async () => {
    await ready;
    try {
      const items = await call(`/search?q=${encodeURIComponent(q)}&limit=8`, { timeout: 2500 });
      if (seq !== omniSeq) return;
      omniCache.set(q, items[0] || null);
      if (omniCache.size > 50) omniCache.delete(omniCache.keys().next().value);
      chrome.omnibox.setDefaultSuggestion({
        description: items.length
          ? `<match>${xml(items[0].title)}</match> <dim>– ${xml(items[0].domain || '')}</dim> <url>${xml(items[0].url)}</url>`
          : tr('omni_nothing', `<match>${xml(q)}</match>`),
      });
      const seen = new Set();
      suggest(
        items.slice(1).filter((it) => !seen.has(it.url) && seen.add(it.url)).map((it) => ({
          content: it.url,
          description:
            `${xml(it.title)}` +
            (it.tags.length ? ` <dim>${xml(it.tags.map((t) => '#' + t).join(' '))}</dim>` : '') +
            ` <url>${xml(it.domain || it.url)}</url>`,
          deletable: false,
        })),
      );
    } catch (e) {
      if (seq !== omniSeq) return;
      chrome.omnibox.setDefaultSuggestion({
        description: e.status === 401 ? tr('omni_401') : xml(e.message),
      });
      suggest([]);
    }
  }, 120);
});

chrome.omnibox.onInputEntered.addListener(async (text, disposition) => {
  const q = text.trim();
  let url = /^https?:\/\//i.test(q) ? q : null; // selected suggestion
  if (!url) {
    let first = omniCache.get(q);
    if (first === undefined) first = (await call(`/search?q=${encodeURIComponent(q)}&limit=1`).catch(() => []))[0] || null;
    if (!first) return openManager(q);
    url = first.url;
  }
  if (disposition === 'currentTab') chrome.tabs.update({ url });
  else chrome.tabs.create({ url, active: disposition === 'newForegroundTab' });
});

async function setBadge(tabId, text, color, title) {
  await ready;
  try {
    await chrome.action.setBadgeText({ tabId, text });
    if (color) await chrome.action.setBadgeBackgroundColor({ tabId, color });
    await chrome.action.setTitle({ tabId, title: title || 'Bookmarks Plus' });
  } catch (_) {
    /* tab closed */
  }
}

/** Checkmark if the page is already saved. */
async function refreshBadge(tabId, url) {
  const { token } = await getConfig();
  if (!token || !isSavable(url)) return setBadge(tabId, '', null);
  try {
    const r = await call(`/lookup?url=${encodeURIComponent(url)}`, { timeout: 2000 });
    if (r.exists) {
      const where = (r.item.folders || []).map((f) => f.path).join(', ');
      await setBadge(tabId, '✓', GREEN, where ? tr('badge_saved_in', where) : tr('badge_saved'));
    } else {
      await setBadge(tabId, '', null);
    }
  } catch (_) {
    await setBadge(tabId, '', null);
  }
}

async function save(tab, url, title) {
  await ready;
  if (!isSavable(url)) return setBadge(tab.id, '!', RED, tr('badge_http_only'));
  const info = url === tab.url ? await pageInfo(tab.id) : {};
  try {
    const r = await call('/bookmarks', {
      method: 'POST',
      body: { url, title: title || '', description: info.selection || info.description || null },
    });
    if (url === tab.url) {
      await setBadge(tab.id, '✓', GREEN, r.created ? tr('badge_saved') : tr('already_saved'));
    } else {
      await setBadge(tab.id, '+', GREEN, tr('badge_link_saved', r.item.title));
      setTimeout(() => refreshBadge(tab.id, tab.url), 2500);
    }
  } catch (e) {
    await setBadge(tab.id, '!', RED, e.status === 401 ? tr('badge_401') : e.message);
  }
}

chrome.contextMenus.onClicked.addListener((info, tab) => {
  if (!tab) return;
  if (info.menuItemId === 'save-page') save(tab, tab.url, tab.title);
  if (info.menuItemId === 'save-link') save(tab, info.linkUrl, info.selectionText || '');
  if (info.menuItemId === 'open-panel') openPanel(tab);
  if (info.menuItemId === 'open-manager') openManager();
});

chrome.commands.onCommand.addListener(async (command, tab) => {
  if (command === 'open-panel') return tab ? openPanel(tab) : openManager();
  if (command === 'open-manager') return openManager();
  if (command !== 'save-page') return;
  const t = tab || (await chrome.tabs.query({ active: true, currentWindow: true }))[0];
  if (t) save(t, t.url, t.title);
});

chrome.tabs.onActivated.addListener(async ({ tabId }) => {
  const tab = await chrome.tabs.get(tabId).catch(() => null);
  if (tab) refreshBadge(tabId, tab.url);
});

chrome.tabs.onUpdated.addListener((tabId, change, tab) => {
  if (change.status === 'complete') refreshBadge(tabId, tab.url);
});

// Messages from the popup (e.g. set checkmark after saving)
chrome.runtime.onMessage.addListener((msg) => {
  if (msg && msg.type === 'refresh-badge') refreshBadge(msg.tabId, msg.url);
  if (msg && msg.type === 'open-manager') openManager(msg.query);
});

// Page jobs from the app (e.g. load AlternativeTo via this browser)
installTaskPolling();
