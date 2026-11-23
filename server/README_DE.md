# Bookmarks Plus – Sync-Server

<p><a href="README.md">🇬🇧 English</a> · <b>🇩🇪 Deutsch</b></p>

Ein schlanker Ktor-Server, über den Desktop-App und Android-App ihre Lesezeichen abgleichen. Er speichert ausschließlich **verschlüsselte** Einträge. Passphrase und Inhalte sieht er nie, denn verschlüsselt wird auf den Geräten (Argon2id + AES-256-GCM). Jeder Benutzer hat seinen eigenen Datenbereich.

Einen Server braucht es nicht zwingend: Die Apps synchronisieren auch über **FTP/FTPS** oder (Desktop) einen **lokalen Ordner**, etwa in OneDrive, Dropbox oder Nextcloud.

← [Zurück zur Übersicht](../README_DE.md)

## Inhalt

- [Mit Docker betreiben](#mit-docker-betreiben)
- [Ohne Docker](#ohne-docker)
- [Konfiguration](#konfiguration)
- [HTTPS](#https-wichtig)
- [In der App verbinden](#in-der-app-verbinden)
- [Wie synchronisiert wird](#wie-synchronisiert-wird)
- [API](#api)
- [Was am Ziel liegt](#was-am-ziel-liegt)

## Mit Docker betreiben

Fertiges Image aus der CI (siehe [CI/CD](../README_DE.md#cicd)): `ghcr.io/tobsef/bookmarks-plus-server:latest`. Oder lokal bauen:

```bash
./gradlew :server:installDist
docker build -t bookmarks-plus-server server
```

Benutzer anlegen (fragt nach dem Passwort) und starten:

```bash
docker volume create bookmarks-plus
docker run --rm -it -v bookmarks-plus:/data bookmarks-plus-server add-user anna
docker run -d --name bookmarks-plus --restart unless-stopped \
  -p 127.0.0.1:8787:8787 -v bookmarks-plus:/data bookmarks-plus-server
```

Neue Benutzer werden beim Start eingelesen. Nach `add-user` den Container also neu starten (`docker restart bookmarks-plus`).

Mit Docker Compose:

```yaml
services:
  bookmarks-plus:
    image: ghcr.io/tobsef/bookmarks-plus-server:latest
    restart: unless-stopped
    ports:
      - "127.0.0.1:8787:8787"
    volumes:
      - data:/data
volumes:
  data:
```

Das Image basiert auf `eclipse-temurin:21-jre-alpine`, läuft als unprivilegierter Benutzer und legt alles unter `/data` ab.

## Ohne Docker

```bash
./gradlew :server:installDist
```

Ergebnis: `server/build/install/bookmarks-plus-server/` mit `bin/` (Startskripte für Linux und Windows) und `lib/`. Den Ordner auf den Server kopieren, dort genügt Java 17 oder neuer. Fertige ZIPs liegen auch bei jedem Release.

```bash
export BMP_DATA=/srv/bookmarks-plus
bin/bookmarks-plus-server add-user anna        # fragt nach dem Passwort
BMP_HOST=127.0.0.1 bin/bookmarks-plus-server
```

Als systemd-Dienst:

```ini
[Unit]
Description=Bookmarks Plus Sync
After=network.target

[Service]
Environment=BMP_DATA=/srv/bookmarks-plus BMP_HOST=127.0.0.1
ExecStart=/opt/bookmarks-plus-server/bin/bookmarks-plus-server
User=bookmarks
Restart=on-failure

[Install]
WantedBy=multi-user.target
```

## Konfiguration

| Variable | Standard | Bedeutung |
|---|---|---|
| `BMP_DATA` | `./data` (Docker: `/data`) | Datenordner, je Benutzer ein Unterordner |
| `BMP_USERS` | `$BMP_DATA/users.txt` | Benutzerdatei: je Zeile `name:pbkdf2$…` |
| `BMP_PORT` | `8787` | Port |
| `BMP_HOST` | `0.0.0.0` | Adresse; hinter einem Reverse Proxy `127.0.0.1` |
| `BMP_LANG` | `en` | Sprache der Meldungen und Fehlertexte: `en` oder `de` |

Weitere Befehle: `add-user <name> [passwort]` und `hash-password <passwort>` (gibt nur den Hash aus, etwa für eine selbst verwaltete Benutzerdatei). Passwörter werden ausschließlich als PBKDF2-HMAC-SHA256-Hash (210.000 Runden) gespeichert. Fehlversuche bremst der Server.

## HTTPS (wichtig)

Die Anmeldung läuft per HTTP Basic Auth. Der Server gehört deshalb hinter HTTPS, zum Beispiel mit Caddy:

```
bookmarks.example.org {
    reverse_proxy 127.0.0.1:8787
}
```

Die Android-App akzeptiert im Release-Build ausschließlich HTTPS.

## In der App verbinden

1. **Desktop:** ⚙ → *Sync* → „Eigener Server“. URL (`https://bookmarks.example.org`), Benutzer und Passwort eintragen. **Android:** Einstellungen → „Eigener Server“.
2. „Verbindung testen“.
3. Passphrase festlegen (mindestens 10 Zeichen). **Ohne sie lassen sich die Daten nicht wiederherstellen.**
4. Auf weiteren Geräten dasselbe Ziel und dieselbe Passphrase eintragen. Der erste Sync führt zusammen: Was nur lokal liegt, wird hochgeladen. Was es am Ziel schon gibt, gewinnt.

## Wie synchronisiert wird

Synchronisiert wird **je Eintrag**: Jedes Lesezeichen (samt Ordnerplätzen und Tags), jeder Ordner, jeder Tag und jedes Icon ist ein eigener, einzeln verschlüsselter Eintrag.

- Der Server sieht je Eintrag nur eine **undurchsichtige UUID**, die **Revision**, den **Zeitpunkt der letzten Änderung** und eine fortlaufende **Sequenznummer**. Die UUID ist ein HMAC aus dem Sync-Schlüssel, der Server erkennt also nicht einmal, ob es ein Lesezeichen oder ein Ordner ist. Die Revision ist der SHA-256 des Chiffrats.
- Geräte holen nur, was sich seit ihrer letzten Sequenznummer geändert hat, und laden nur geänderte Einträge hoch. Nach dem ersten Abgleich dauert ein Sync wenige Sekunden.
- Jeder Upload nennt die Revision, auf der er beruht (Compare-and-Swap). Hat ein anderes Gerät den Eintrag inzwischen geändert, meldet der Server einen **Konflikt** und speichert nichts. Das Gerät holt dann die andere Fassung, und die jüngere gewinnt.
- Inhalte sind an ihre ID gebunden (ID als AAD der Verschlüsselung). Der Server kann Einträge also weder lesen noch untereinander vertauschen.
- Gelöschte Einträge bleiben 180 Tage als Grabstein ohne Inhalt liegen, damit alle Geräte die Löschung mitbekommen.

## API

Alle Endpunkte außer `/health` verlangen HTTP Basic Auth.

| Methode | Pfad | Zweck |
|---|---|---|
| `GET` | `/health` | `ok` (ohne Anmeldung) |
| `GET` | `/api/v2/info` | `{version, count, seq}` |
| `GET` | `/api/v2/records?since=<seq>&limit=<n>` | Änderungen seit `seq`: `{records:[{id, rev, seq, updated, deleted}], next, more, reset}` |
| `POST` | `/api/v2/records/get` | `{ids:[…]}` → `{records:[{id, rev, data}]}` (Base64) |
| `POST` | `/api/v2/records/put` | `{items:[{id, base, data}]}` → `{results:[{id, ok, rev, seq} \| {id, ok:false, rev, deleted}]}` |
| `GET/PUT/DELETE` | `/api/v1/blobs/{name}` | Dateien: `keyinfo.json`, Vorschaubilder, Sicherungen |
| `GET` | `/api/v1/blobs?prefix=…` | Dateinamen, je Zeile einer |

`base` ist die Revision, auf der eine Änderung beruht (`null` = neuer Eintrag), `data: null` löscht.

## Was am Ziel liegt

| Pfad | Inhalt |
|---|---|
| `keyinfo.json` | Salt und Parameter der Schlüsselableitung (nicht geheim) |
| `records.db` (Server) | verschlüsselte Einträge mit UUID, Revision, Zeitpunkt und Sequenznummer |
| `items/<Datum>_<UUID>.bin` (FTP, Ordner) | ein verschlüsselter Eintrag je Datei, gelöschte als leere `.del` |
| `media/…` | verschlüsselte Vorschaubilder (Name = HMAC des Inhalts) |
| `backups/…` | verschlüsselte vollständige Datenbank-Sicherungen (nur auf Knopfdruck, die letzten 10) |
| `users.txt` (Server) | Benutzer mit Passwort-Hash |

Dateien des früheren Verfahrens (`changes/`, `snapshots/`) entfernt die App beim ersten Sync. Ein Server ohne API v2 wird erkannt („Server zu alt“).
