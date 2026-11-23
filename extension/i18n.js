// Translations of the extension: _locales/<lang>/messages.json (Chrome format), language in chrome.storage.local
// ("uiLang", default English) – independent of the browser language so that it can be chosen in the options.

export const LANGS = [
  { code: 'en', label: 'English' },
  { code: 'de', label: 'Deutsch' },
];

let messages = {};
let fallback = {};
let current = 'en';

async function loadFile(lang) {
  try {
    const r = await fetch(chrome.runtime.getURL(`_locales/${lang}/messages.json`));
    const json = await r.json();
    return Object.fromEntries(Object.entries(json).map(([k, v]) => [k, v.message]));
  } catch {
    return {};
  }
}

async function load() {
  const { uiLang } = await chrome.storage.local.get('uiLang');
  current = LANGS.some((l) => l.code === uiLang) ? uiLang : 'en';
  fallback = await loadFile('en');
  messages = current === 'en' ? fallback : await loadFile(current);
  if (typeof document !== 'undefined') document.documentElement.setAttribute('lang', current);
}

/** Started when the module is loaded; wait for it before the first t(). */
export let ready = load();

// changed in another page (options) → reload here
chrome.storage.onChanged.addListener((changes, area) => {
  if (area === 'local' && changes.uiLang) ready = load();
});

export function lang() {
  return current;
}

export async function setLang(code) {
  await chrome.storage.local.set({ uiLang: code });
  ready = load();
  await ready;
}

/** Text for the key; {0}, {1} … are replaced by the arguments. */
export function t(key, ...args) {
  const s = messages[key] ?? fallback[key] ?? key;
  return args.length ? s.replace(/\{(\d+)\}/g, (m, i) => (args[i] !== undefined ? String(args[i]) : m)) : s;
}

/** Translates data-i18n (text), data-i18n-html, data-i18n-title and data-i18n-placeholder in the document. */
export function applyI18n(root = document) {
  root.querySelectorAll('[data-i18n]').forEach((el) => (el.textContent = t(el.dataset.i18n)));
  root.querySelectorAll('[data-i18n-html]').forEach((el) => (el.innerHTML = t(el.dataset.i18nHtml)));
  root.querySelectorAll('[data-i18n-title]').forEach((el) => (el.title = t(el.dataset.i18nTitle)));
  root.querySelectorAll('[data-i18n-placeholder]').forEach((el) => (el.placeholder = t(el.dataset.i18nPlaceholder)));
}
