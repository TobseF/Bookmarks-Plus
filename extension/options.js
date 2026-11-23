import { call, clientName, forget, getConfig, setConfig, status } from './api.js';
import { LANGS, applyI18n, lang, ready, setLang, t as tr } from './i18n.js';

const $ = (id) => document.getElementById(id);

async function refresh() {
  const { port } = await getConfig();
  $('port').value = port;
  $('state').textContent = tr('opt_checking');
  const s = await status();
  $('port').value = s.port;
  if (!s.reachable) {
    $('state').innerHTML = tr('opt_unreachable');
  } else if (!s.authorized) {
    $('state').innerHTML = tr('opt_not_connected');
  } else {
    $('state').innerHTML = tr('opt_connected', s.port);
  }
  $('import').disabled = !(s.reachable && s.authorized);
}

$('savePort').addEventListener('click', async () => {
  const p = parseInt($('port').value, 10);
  if (p > 1023 && p < 65536) await setConfig({ port: p });
  refresh();
});
$('check').addEventListener('click', refresh);
$('disconnect').addEventListener('click', async () => {
  await forget();
  refresh();
});

/** Convert the chrome.bookmarks tree into the lean format of the app. */
function convert(node) {
  return {
    title: node.title || '',
    url: node.url || null,
    dateAdded: node.dateAdded || null,
    children: node.children ? node.children.map(convert) : null,
  };
}

$('import').addEventListener('click', async () => {
  const [root] = await chrome.bookmarks.getTree();
  const count = (n) => (n.url ? 1 : (n.children || []).reduce((a, c) => a + count(c), 0));
  const total = count(root);
  if (!confirm(tr('opt_import_confirm', total, clientName()))) return;
  $('import').disabled = true;
  $('importState').textContent = tr('opt_transferring');
  try {
    const r = await call('/import', { method: 'POST', body: { name: clientName(), root: convert(root) }, timeout: 120000 });
    $('importState').innerHTML = tr('opt_import_done', r.links, r.created, r.linked);
  } catch (e) {
    $('importState').innerHTML = `<span class="err">${e.message}</span>`;
  } finally {
    $('import').disabled = false;
  }
});

ready.then(() => {
  applyI18n();
  const sel = $('lang');
  sel.innerHTML = LANGS.map((l) => `<option value="${l.code}">${l.label}</option>`).join('');
  sel.value = lang();
  sel.addEventListener('change', async () => {
    await setLang(sel.value);
    location.reload();
  });
  refresh();
});
