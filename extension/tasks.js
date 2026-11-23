// Page jobs from the app: the app asks the extension to load a page in the real browser (with the cookies
// and passed bot checks of the user), e.g. AlternativeTo, which blocks program fetches.
// Allowed hosts only; the app checks this as well. Bot checks are never solved automatically.
import { call, getConfig } from './api.js';
import { ready, t as tr } from './i18n.js';

const ALLOWED_HOSTS = ['alternativeto.net'];
const CHALLENGE = /just a moment|einen moment|attention required|verifying you are human|are you a robot|security check|access denied/i;

let polling = false;

function allowed(url) {
  try {
    const u = new URL(url);
    return u.protocol === 'https:' && ALLOWED_HOSTS.includes(u.hostname.replace(/^www\./, ''));
  } catch (_) {
    return false;
  }
}

function isChallenge(html) {
  const head = (html || '').slice(0, 6000);
  const title = (head.match(/<title[^>]*>([^<]*)<\/title>/i) || [])[1] || '';
  return CHALLENGE.test(title) || /id="challenge-form"|id="cf-challenge-running"/.test(head);
}

/** First a simple fetch (fast, invisible); on bot check load in a minimized window. */
async function load(url) {
  try {
    const res = await fetch(url, { credentials: 'include', headers: { 'Accept-Language': 'en-US,en;q=0.9' } });
    const html = await res.text();
    if (res.status === 404) return { status: 404, finalUrl: res.url, html: null };
    if (res.ok && !isChallenge(html)) return { status: res.status, finalUrl: res.url, html };
  } catch (_) {
    /* continue with window */
  }
  return loadInWindow(url);
}

async function loadInWindow(url) {
  const win = await chrome.windows.create({ url, focused: false, state: 'minimized', type: 'normal' });
  const tabId = win.tabs && win.tabs[0] && win.tabs[0].id;
  try {
    // wait up to 30 s for a finished page without bot check
    for (let i = 0; i < 30; i++) {
      await new Promise((r) => setTimeout(r, 1000));
      const tab = await chrome.tabs.get(tabId).catch(() => null);
      if (!tab) break;
      if (tab.status !== 'complete' || CHALLENGE.test(tab.title || '')) continue;
      const [res] = await chrome.scripting.executeScript({ target: { tabId }, func: () => document.documentElement.outerHTML });
      const html = res && res.result;
      if (html && !isChallenge(html)) return { status: 200, finalUrl: tab.url, html };
    }
    return { status: 403, finalUrl: url, html: null, error: tr('task_bot_check') };
  } finally {
    chrome.windows.remove(win.id).catch(() => {});
  }
}

async function runTask(task) {
  await ready;
  let result;
  if (!task || !allowed(task.url)) result = { status: 0, error: tr('task_host_denied') };
  else {
    try {
      result = await load(task.url);
    } catch (e) {
      result = { status: 0, error: String(e && e.message || e) };
    }
  }
  await call(`/tasks/${encodeURIComponent(task.id)}`, { method: 'POST', body: result, timeout: 15000 }).catch(() => {});
}

/** Fetches jobs via long polling; runs as long as some arrive (at most ~3 min at a stretch). */
export async function pollTasks() {
  if (polling) return;
  const { token } = await getConfig();
  if (!token) return;
  polling = true;
  try {
    for (let i = 0; i < 8; i++) {
      const task = await call('/tasks/next?wait=20', { timeout: 25000 });
      if (!task || !task.id) {
        if (i > 0) break; // after a round without a job sleep until the next alarm
        continue;
      }
      await runTask(task);
      i = 0;
    }
  } catch (_) {
    /* app not reachable or older version without jobs */
  } finally {
    polling = false;
  }
}

export function installTaskPolling() {
  chrome.alarms.create('bp-tasks', { periodInMinutes: 0.5 });
  chrome.alarms.onAlarm.addListener((a) => {
    if (a.name === 'bp-tasks') pollTasks();
  });
  pollTasks();
}
