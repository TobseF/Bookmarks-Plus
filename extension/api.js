import { t as tr } from './i18n.js';
// Connection to the Bookmarks Plus app (local interface on 127.0.0.1).

export const DEFAULT_PORT = 47811;

export class ApiError extends Error {
  constructor(message, status) {
    super(message);
    this.status = status;
  }
}

export async function getConfig() {
  const { port, token } = await chrome.storage.local.get(['port', 'token']);
  return { port: port || DEFAULT_PORT, token: token || null };
}

export async function setConfig(values) {
  await chrome.storage.local.set(values);
}

/** Name of the browser for the pairing request. */
export function clientName() {
  const brands = (navigator.userAgentData && navigator.userAgentData.brands) || [];
  const known = ['Vivaldi', 'Microsoft Edge', 'Brave', 'Opera', 'Google Chrome'];
  const hit = known.find((k) => brands.some((b) => b.brand === k));
  return hit || 'Chromium-Browser';
}

async function request(port, path, { method = 'GET', body, token, timeout = 8000, contentType = 'application/json' } = {}) {
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), timeout);
  try {
    const headers = { 'Content-Type': contentType };
    if (token) headers.Authorization = `Bearer ${token}`;
    const res = await fetch(`http://127.0.0.1:${port}/api${path}`, {
      method,
      headers,
      body: body === undefined ? undefined : body instanceof Blob ? body : JSON.stringify(body),
      signal: ctrl.signal,
    });
    const text = await res.text();
    const data = text ? JSON.parse(text) : null;
    if (!res.ok) throw new ApiError((data && data.error) || `HTTP ${res.status}`, res.status);
    return data;
  } catch (e) {
    if (e instanceof ApiError) throw e;
    throw new ApiError(tr('err_unreachable'), 0);
  } finally {
    clearTimeout(timer);
  }
}

/**
 * Finds the running app (saved port, otherwise the next four) and reports whether this browser is paired.
 * Result: { reachable, authorized, port }
 */
export async function status() {
  const { port, token } = await getConfig();
  const candidates = [port, ...[0, 1, 2, 3, 4].map((i) => DEFAULT_PORT + i).filter((p) => p !== port)];
  for (const p of candidates) {
    try {
      const s = await request(p, '/status', { token, timeout: 1500 });
      if (s && s.app === 'bookmarks-plus') {
        if (p !== port) await setConfig({ port: p });
        return { reachable: true, authorized: !!s.authorized, port: p };
      }
    } catch (_) {
      /* next port */
    }
  }
  return { reachable: false, authorized: false, port };
}

/** Authenticated call. */
export async function call(path, options = {}) {
  const { port, token } = await getConfig();
  return request(port, path, { ...options, token });
}

/** Start pairing: returns { requestId, code }. */
export async function pairStart() {
  const { port } = await getConfig();
  return request(port, '/pair', { method: 'POST', body: { client: clientName() } });
}

/** Waits for the confirmation in the app; stores the token. */
export async function pairWait(requestId, onTick) {
  const { port } = await getConfig();
  for (let i = 0; i < 120; i++) {
    const r = await request(port, `/pair/${encodeURIComponent(requestId)}`);
    if (r.status === 'approved' && r.token) {
      await setConfig({ token: r.token });
      return true;
    }
    if (r.status === 'denied' || r.status === 'unknown') return false;
    if (onTick) onTick(i);
    await new Promise((ok) => setTimeout(ok, 1000));
  }
  return false;
}

export async function forget() {
  await chrome.storage.local.remove('token');
}

/** Read description and selected text of the page (only after click/shortcut – activeTab). */
export async function pageInfo(tabId) {
  try {
    const [res] = await chrome.scripting.executeScript({
      target: { tabId },
      func: () => {
        const meta = (n) =>
          document.querySelector(`meta[property="${n}"], meta[name="${n}"]`)?.getAttribute('content')?.trim() || '';
        return {
          description: meta('og:description') || meta('description'),
          selection: (window.getSelection && window.getSelection().toString().trim()) || '',
        };
      },
    });
    return res?.result || {};
  } catch (_) {
    return {}; // e.g. chrome:// pages
  }
}

export function isSavable(url) {
  return /^https?:\/\//i.test(url || '');
}

export function faviconUrl(pageUrl, size = 32) {
  const u = new URL(chrome.runtime.getURL('/_favicon/'));
  u.searchParams.set('pageUrl', pageUrl);
  u.searchParams.set('size', String(size));
  return u.toString();
}

/** Aspect ratio of the preview images in the app (width : height). */
export const THUMB_RATIO = 1.9;

/**
 * Captures the visible area of the tab and crops it to the aspect ratio of the app preview
 * (top-aligned, as in the app). Returns { blob, url } – url shows the image as a preview.
 */
export async function captureThumbnail(windowId) {
  const dataUrl = await chrome.tabs.captureVisibleTab(windowId, { format: 'png' });
  const img = await createImageBitmap(await (await fetch(dataUrl)).blob());
  let w = img.width;
  let h = Math.round(w / THUMB_RATIO);
  if (h > img.height) {
    h = img.height;
    w = Math.round(h * THUMB_RATIO);
  }
  const scale = Math.min(1, 1140 / w);
  const canvas = new OffscreenCanvas(Math.round(w * scale), Math.round(h * scale));
  canvas.getContext('2d').drawImage(img, 0, 0, w, h, 0, 0, canvas.width, canvas.height);
  const blob = await canvas.convertToBlob({ type: 'image/jpeg', quality: 0.88 });
  return { blob, url: URL.createObjectURL(blob) };
}

export function uploadThumbnail(id, blob) {
  return call(`/bookmarks/${id}/thumbnail`, { method: 'PUT', body: blob, contentType: 'image/jpeg', timeout: 20000 });
}

/** Image of a bookmark from the app (icon | thumbnail | card | preview) as object URL; null if there is none. */
export async function mediaUrl(id, kind = 'preview') {
  const { port, token } = await getConfig();
  try {
    const res = await fetch(`http://127.0.0.1:${port}/api/media/${id}/${kind}`, {
      headers: token ? { Authorization: `Bearer ${token}` } : {},
    });
    if (!res.ok) return null;
    return URL.createObjectURL(await res.blob());
  } catch (_) {
    return null;
  }
}
