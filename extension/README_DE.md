# Bookmarks Plus – Browser-Erweiterung

<p><a href="README.md">🇬🇧 English</a> · <b>🇩🇪 Deutsch</b></p>

Diese Manifest-V3-Erweiterung funktioniert in Chrome, Vivaldi, Edge und Brave. Sie spricht ausschließlich mit der Bookmarks-Plus-App auf demselben Rechner (`127.0.0.1`, Port 47811 oder einer der folgenden).

## Installieren

1. `chrome://extensions` öffnen (in Vivaldi `vivaldi://extensions`).
2. Oben rechts den **Entwicklermodus** einschalten.
3. **Entpackte Erweiterung laden** und diesen Ordner `extension` wählen.
4. Bookmarks Plus starten, auf das Symbol der Erweiterung klicken und **Verbinden** wählen. Die App zeigt einen vierstelligen Code. Wenn er mit dem in der Erweiterung übereinstimmt, **Verbinden** bestätigen.

## Funktionen

- **Speichern:** Klick auf das Symbol öffnet ein Fenster mit Titel, Ordner (Standard: Inbox), Tags und Notiz. Ist eine Textstelle markiert, wird sie als Notiz übernommen.
- **Sofort speichern:** Mit `Alt+Umschalt+S` oder per Rechtsklick → „Seite / Link in Bookmarks Plus speichern“.
- **Anreichern:** Neue Lesezeichen reichert die App automatisch an (Vorschau, Metadaten, KI, falls eingerichtet).
- **Häkchen am Symbol:** Es zeigt, dass die aktuelle Seite schon gespeichert ist. Der Tooltip nennt den Ordner.
- **Suche:** Das Suchfeld oben durchsucht alle Lesezeichen, inklusive Volltext und Operatoren wie `#tag` oder `domain:`. Pfeiltasten und Enter öffnen einen Treffer, Strg+Enter öffnet ihn im Hintergrund.
- **Suche in der Adressleiste:** `b`, Leertaste, Suchbegriff – Treffer aus Bookmarks Plus erscheinen direkt in der Adressleiste (Volltext, `#tag`, `domain:`, `stars:>1k` …). Enter öffnet den ersten Treffer, ohne Treffer öffnet sich der Manager mit der Suche.
- **Seitenpanel** (`Alt+Umschalt+L` oder Rechtsklick auf das Symbol → „Seitenpanel öffnen“): Suchleiste plus vier Ansichten
  - **Baum:** Ordner auf-/zuklappen, Lesezeichen darin, Verknüpfungen kursiv mit ↪; ☰ zeigt einen Ordner als Liste/Kacheln
  - **Liste** und **Kacheln** (Vorschaubild bzw. GitHub-Card aus der App, Stars, Tags) für Suchergebnisse oder einen Ordner samt Unterordnern, sortierbar
  - **Tabs:** offene Tabs je Fenster als Baum, ✓ = schon gespeichert, ＋ speichert einen bzw. alle Tabs in die Inbox, × schließt
  - Rechtsklick auf ein Lesezeichen: öffnen (Tab/Fenster), Adresse kopieren, umbenennen, Tags, verschieben, verknüpfen, Papierkorb (mit Rückgängig). Ziehen auf einen Ordner verschiebt, mit Strg/Alt wird verknüpft. Klick öffnet im aktuellen Tab, Strg/Mittelklick im Hintergrund, Umschalt in neuem Tab.
- **Manager im Tab:** ⤢ im Panel oder Rechtsklick auf das Symbol → „Bookmarks Plus im Tab öffnen“ – dieselbe Oberfläche breit, mit Ordnerleiste links.
- **Programmdaten übernehmen (ab 1.3):** Auf einer Flathub-App-Seite oder einer AlternativeTo-Programmseite zeigt das Popup den erkannten Programmnamen und passende Lesezeichen (hinterlegte Seite, gleiches Repo, gleicher App-Name, gleiche Webseite, ähnlicher Titel). „Übernehmen“ bzw. „Aktualisieren“ schreibt Name, Plattform, System, Lizenz, Paket-IDs, Flathub- und AlternativeTo-Link in das Lesezeichen. Bei AlternativeTo schickt die Erweiterung die schon geladene Seite mit, es gibt also keinen zusätzlichen Abruf und keine Bot-Prüfung. Ohne passendes Lesezeichen: „Als neues Lesezeichen speichern und übernehmen“ (gespeichert wird die Webseite des Programms, sonst das Repo bzw. die Seite).
  - **Domain-Suche (ab 1.3.1):** Gibt es keinen genauen Treffer (hinterlegte Seite, gleiche URL, gleiches Repo), werden bis zu drei Lesezeichen derselben Domain wie die Programm-Webseite angeboten – „Diesen aktualisieren“ oder „Neu in Inbox anlegen und übernehmen“. Gibt es mehr, erscheint „Für alle N übernehmen …“, das erst nach einer Warnung und einer zweiten Bestätigung ausgeführt wird.
- **Seiten für die App laden (ab 1.2):** Manche Verzeichnisse wie AlternativeTo sperren Programmabrufe. Die App kann solche Seiten dann über diesen Browser laden, mit dessen Cookies. Die Erweiterung fragt alle 30 Sekunden nach Aufträgen. Sie ruft die Seite zuerst unsichtbar ab; zeigt die Seite eine Bot-Prüfung, öffnet sie sie kurz in einem minimierten Fenster und schließt es danach. Prüfungen löst sie nie selbst. Erlaubt ist nur `alternativeto.net`. Abschalten lässt sich das in der App unter ⚙ → Anreicherung → Programme.
- **Browser-Lesezeichen übernehmen:** In den Einstellungen der Erweiterung lassen sie sich einmalig in einen neuen Ordner kopieren. Vorhandene Adressen werden dabei verknüpft statt doppelt angelegt.

## Sicherheit

- Die App nimmt Anfragen nur über die Loopback-Schnittstelle an.
- Ohne Token ist nur `/api/status` erreichbar. Tokens gibt es nur nach Bestätigung in der App, gespeichert werden sie dort nur als SHA-256.
- Eine Kopplung akzeptiert die App nur aus Browser-Erweiterungen. Webseiten können die Antworten mangels CORS-Freigabe nicht lesen.
- Verbundene Browser lassen sich in der App unter ⚙ → Browser-Erweiterung wieder trennen.

## Rechte

| Recht | wofür |
|---|---|
| `activeTab`, `scripting` | Beschreibung und markierten Text der aktuellen Seite lesen sowie das Thumbnail aufnehmen („Thumbnail aktualisieren“) – nur nach Klick oder Tastenkürzel |
| `tabs` | Häkchen am Symbol für bereits gespeicherte Seiten, Ansicht „Tabs“ im Seitenpanel |
| `contextMenus` | Rechtsklick „In Bookmarks Plus speichern“ |
| `bookmarks` | einmalige Übernahme der Browser-Lesezeichen (nur auf Knopfdruck) |
| `storage` | Port und Token |
| `favicon` | Seitensymbole in den Suchergebnissen |
| `sidePanel` | Seitenpanel |
| `alarms` | alle 30 s nach Seitenaufträgen der App fragen |
| `http://127.0.0.1/*` | Verbindung zur App |
| `https://alternativeto.net/*` | AlternativeTo-Seiten für die App laden (Programmdaten) |

## Hinweise

- Seitenaufträge brauchen die App ab Schnittstellen-Version 3 (`/api/tasks/next`, `/api/tasks/{id}`).
- Seitenpanel und Adressleisten-Suche brauchen die App ab Schnittstellen-Version 2 (`/api/tree`, `/api/folders/{id}/items`, `/api/media/{id}/{kind}`). Nach dem Aktualisieren der Erweiterung die App einmal neu starten.
- Vivaldi: Kennt der Browser `chrome.sidePanel` nicht, öffnet das Tastenkürzel den Manager als Tab. Alternativ `chrome-extension://<ID>/panel.html` als Web-Panel hinzufügen (die ID steht unter `vivaldi://extensions`).
- Das Kürzel `b` der Adressleiste ist fest im Manifest; Chrome erlaubt kein Ändern zur Laufzeit.
