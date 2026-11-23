# Bookmarks Plus – Sync server

<p><b>🇬🇧 English</b> · <a href="README_DE.md">🇩🇪 Deutsch</a></p>

A lean Ktor server through which the desktop app and the Android app sync their bookmarks. It stores **encrypted** entries only. It never sees the passphrase or the contents, because encryption happens on the devices (Argon2id + AES-256-GCM). Every user has their own data area.

A server is not strictly required: the apps also sync via **FTP/FTPS** or (desktop) a **local folder**, for example in OneDrive, Dropbox or Nextcloud.

← [Back to the overview](../README.md)

## Contents

- [Run with Docker](#run-with-docker)
- [Without Docker](#without-docker)
- [Configuration](#configuration)
- [HTTPS](#https-important)
- [Connect in the app](#connect-in-the-app)
- [How syncing works](#how-syncing-works)
- [API](#api)
- [What is stored at the target](#what-is-stored-at-the-target)

## Run with Docker

Ready-made image from the CI (see [CI/CD](../README.md#cicd)): `ghcr.io/tobsef/bookmarks-plus-server:latest`. Or build it locally:

```bash
./gradlew :server:installDist
docker build -t bookmarks-plus-server server
```

Create a user (asks for the password) and start:

```bash
docker volume create bookmarks-plus
docker run --rm -it -v bookmarks-plus:/data bookmarks-plus-server add-user alex
docker run -d --name bookmarks-plus --restart unless-stopped \
  -p 127.0.0.1:8787:8787 -v bookmarks-plus:/data bookmarks-plus-server
```

New users are read at startup. So restart the container after `add-user` (`docker restart bookmarks-plus`).

With Docker Compose:

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

The image is based on `eclipse-temurin:21-jre-alpine`, runs as an unprivileged user and stores everything under `/data`.

## Without Docker

```bash
./gradlew :server:installDist
```

Result: `server/build/install/bookmarks-plus-server/` with `bin/` (start scripts for Linux and Windows) and `lib/`. Copy the folder to the server, Java 17 or newer is all it needs there. Ready-made ZIPs are also attached to every release.

```bash
export BMP_DATA=/srv/bookmarks-plus
bin/bookmarks-plus-server add-user alex        # asks for the password
BMP_HOST=127.0.0.1 bin/bookmarks-plus-server
```

As a systemd service:

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

## Configuration

| Variable | Default | Meaning |
|---|---|---|
| `BMP_DATA` | `./data` (Docker: `/data`) | data folder, one subfolder per user |
| `BMP_USERS` | `$BMP_DATA/users.txt` | user file: one `name:pbkdf2$…` per line |
| `BMP_PORT` | `8787` | port |
| `BMP_HOST` | `0.0.0.0` | address; `127.0.0.1` behind a reverse proxy |
| `BMP_LANG` | `en` | language of messages and error texts: `en` or `de` |

More commands: `add-user <name> [password]` and `hash-password <password>` (only prints the hash, e.g. for a self-managed user file). Passwords are stored exclusively as a PBKDF2-HMAC-SHA256 hash (210,000 rounds). The server slows down failed attempts.

## HTTPS (important)

Login uses HTTP Basic Auth. The server therefore belongs behind HTTPS, for example with Caddy:

```
bookmarks.example.org {
    reverse_proxy 127.0.0.1:8787
}
```

The Android app accepts only HTTPS in the release build.

## Connect in the app

1. **Desktop:** ⚙ → *Sync* → “Own server”. Enter URL (`https://bookmarks.example.org`), user and password. **Android:** Settings → “Own server”.
2. “Test connection”.
3. Set a passphrase (at least 10 characters). **Without it the data cannot be recovered.**
4. Enter the same target and the same passphrase on other devices. The first sync merges: what exists only locally is uploaded. What already exists at the target wins.

## How syncing works

Syncing happens **per entry**: every bookmark (with its folder placements and tags), every folder, every tag and every icon is a separate, individually encrypted entry.

- Per entry the server only sees an **opaque UUID**, the **revision**, the **time of the last change** and a running **sequence number**. The UUID is an HMAC derived from the sync key, so the server cannot even tell whether it is a bookmark or a folder. The revision is the SHA-256 of the ciphertext.
- Devices only fetch what changed since their last sequence number and only upload changed entries. After the first sync a sync takes a few seconds.
- Every upload names the revision it is based on (compare-and-swap). If another device changed the entry in the meantime, the server reports a **conflict** and stores nothing. The device then fetches the other version, and the newer one wins.
- Contents are bound to their ID (ID as AAD of the encryption). So the server can neither read entries nor swap them with each other.
- Deleted entries remain as a tombstone without content for 180 days, so that all devices notice the deletion.

## API

All endpoints except `/health` require HTTP Basic Auth.

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/health` | `ok` (without login) |
| `GET` | `/api/v2/info` | `{version, count, seq}` |
| `GET` | `/api/v2/records?since=<seq>&limit=<n>` | changes since `seq`: `{records:[{id, rev, seq, updated, deleted}], next, more, reset}` |
| `POST` | `/api/v2/records/get` | `{ids:[…]}` → `{records:[{id, rev, data}]}` (Base64) |
| `POST` | `/api/v2/records/put` | `{items:[{id, base, data}]}` → `{results:[{id, ok, rev, seq} \| {id, ok:false, rev, deleted}]}` |
| `GET/PUT/DELETE` | `/api/v1/blobs/{name}` | files: `keyinfo.json`, preview images, backups |
| `GET` | `/api/v1/blobs?prefix=…` | file names, one per line |

`base` is the revision a change is based on (`null` = new entry), `data: null` deletes.

## What is stored at the target

| Path | Contents |
|---|---|
| `keyinfo.json` | salt and parameters of the key derivation (not secret) |
| `records.db` (server) | encrypted entries with UUID, revision, timestamp and sequence number |
| `items/<date>_<UUID>.bin` (FTP, folder) | one encrypted entry per file, deleted ones as empty `.del` |
| `media/…` | encrypted preview images (name = HMAC of the content) |
| `backups/…` | encrypted full database backups (on demand only, the last 10) |
| `users.txt` (server) | users with password hash |

The app removes files of the earlier scheme (`changes/`, `snapshots/`) on the first sync. A server without API v2 is detected (“Server too old”).

Messages and error texts of the server are stored in `src/main/resources/i18n/server.properties` (English) and `server_de.properties` (German).
