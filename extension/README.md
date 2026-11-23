# Bookmarks Plus – Browser extension

<p><b>🇬🇧 English</b> · <a href="README_DE.md">🇩🇪 Deutsch</a></p>

This Manifest V3 extension works in Chrome, Vivaldi, Edge and Brave. It talks exclusively to the Bookmarks Plus app on the same computer (`127.0.0.1`, port 47811 or one of the following).

## Install

1. Open `chrome://extensions` (in Vivaldi `vivaldi://extensions`).
2. Switch on **Developer mode** at the top right.
3. **Load unpacked** and choose this `extension` folder.
4. Start Bookmarks Plus, click the extension icon and choose **Connect**. The app shows a four-digit code. If it matches the one in the extension, confirm **Connect**.

## Features

- **Save:** clicking the icon opens a popup with title, folder (default: Inbox), tags and note. If text is selected, it is used as the note.
- **Save immediately:** with `Alt+Shift+S` or via right click → “Save page / link to Bookmarks Plus”.
- **Enrich:** the app enriches new bookmarks automatically (preview, metadata, AI if set up).
- **Check mark on the icon:** shows that the current page is already saved. The tooltip names the folder.
- **Search:** the search field at the top searches all bookmarks, including full text and operators like `#tag` or `domain:`. Arrow keys and Enter open a hit, Ctrl+Enter opens it in the background.
- **Search in the address bar:** `b`, space, search term – hits from Bookmarks Plus appear directly in the address bar (full text, `#tag`, `domain:`, `stars:>1k` …). Enter opens the first hit, without hits the manager opens with the search.
- **Side panel** (`Alt+Shift+L` or right click on the icon → “Open side panel”): search bar plus four views
  - **Tree:** expand/collapse folders, bookmarks inside, links in italics with ↪; ☰ shows a folder as list/tiles
  - **List** and **tiles** (preview image or GitHub card from the app, stars, tags) for search results or a folder including subfolders, sortable
  - **Tabs:** open tabs per window as a tree, ✓ = already saved, ＋ saves one or all tabs to the Inbox, × closes
  - Right click on a bookmark: open (tab/window), copy address, rename, tags, move, link, trash (with undo). Dragging onto a folder moves, with Ctrl/Alt it links. A click opens in the current tab, Ctrl/middle click in the background, Shift in a new tab.
- **Manager in a tab:** ⤢ in the panel or right click on the icon → “Open Bookmarks Plus in a tab” – the same interface, wide, with a folder bar on the left.
- **Apply program data (1.3+):** on a Flathub app page or an AlternativeTo program page the popup shows the detected program name and matching bookmarks (stored page, same repo, same app name, same website, similar title). “Apply” or “Update” writes name, platform, system, license, package IDs, Flathub and AlternativeTo link into the bookmark. For AlternativeTo the extension sends the page that is already loaded, so there is no extra request and no bot check. Without a matching bookmark: “Save as new bookmark and apply” (the program's website is saved, otherwise the repo or the page).
  - **Domain search (1.3.1+):** if there is no exact match (stored page, same URL, same repo), up to three bookmarks of the same domain as the program website are offered – “Update this one” or “Create new in Inbox and apply”. If there are more, “Apply to all N …” appears, which only runs after a warning and a second confirmation.
- **Load pages for the app (1.2+):** some directories like AlternativeTo block program requests. The app can then load such pages via this browser, with its cookies. The extension asks for jobs every 30 seconds. It fetches the page invisibly first; if the page shows a bot check, it opens it briefly in a minimized window and closes it afterwards. It never solves checks itself. Only `alternativeto.net` is allowed. This can be switched off in the app under ⚙ → Enrichment → Programs.
- **Import browser bookmarks:** in the extension options they can be copied once into a new folder. Existing addresses are linked instead of duplicated.
- **Language:** English (default) or German, selectable on the options page.

## Security

- The app only accepts requests via the loopback interface.
- Without a token only `/api/status` is reachable. Tokens are only issued after confirmation in the app, where they are stored only as SHA-256.
- The app only accepts a pairing from browser extensions. Web pages cannot read the responses due to the missing CORS permission.
- Connected browsers can be disconnected again in the app under ⚙ → Browser extension.

## Permissions

| Permission | Purpose |
|---|---|
| `activeTab`, `scripting` | read description and selected text of the current page and capture the thumbnail (“Update thumbnail”) – only after a click or shortcut |
| `tabs` | check mark on the icon for already saved pages, “Tabs” view in the side panel |
| `contextMenus` | right click “Save to Bookmarks Plus” |
| `bookmarks` | one-time import of the browser bookmarks (only on demand) |
| `storage` | port, token and language |
| `favicon` | page icons in the search results |
| `sidePanel` | side panel |
| `alarms` | ask the app for page jobs every 30 s |
| `http://127.0.0.1/*` | connection to the app |
| `https://alternativeto.net/*` | load AlternativeTo pages for the app (program data) |

## Translations

The texts live in `_locales/en/messages.json` (English) and `_locales/de/messages.json` (German). `i18n.js` loads the language chosen on the options page (stored as `uiLang`); manifest texts (description, shortcuts) follow the browser language.

## Notes

- Page jobs require the app with API version 3 or later (`/api/tasks/next`, `/api/tasks/{id}`).
- Side panel and address bar search require the app with API version 2 or later (`/api/tree`, `/api/folders/{id}/items`, `/api/media/{id}/{kind}`). After updating the extension, restart the app once.
- Vivaldi: if the browser does not know `chrome.sidePanel`, the shortcut opens the manager as a tab. Alternatively add `chrome-extension://<ID>/panel.html` as a web panel (the ID is shown under `vivaldi://extensions`).
- The address bar keyword `b` is fixed in the manifest; Chrome does not allow changing it at runtime.
