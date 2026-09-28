# Photo Sweep

Go through your Nextcloud photo library one photo at a time, month by month, keeping
or deleting each one. Deletions are real — they go to the Nextcloud trash, not just
out of view.

Nothing leaves your server. There is no third-party service, no account, no analytics.

> This is the Nextcloud counterpart of
> [google-photos-cleaner](https://github.com/Nissaar/google-photos-cleaner). Same idea,
> completely different foundations: Google has no delete API, so that app drives a
> browser session. Nextcloud is your own server, so this one is an ordinary server app
> using documented interfaces.

---

## How it works

1. **Index.** Your photos and videos are catalogued by the date they were *taken*,
   into months. This runs in the background and is resumable.
2. **Pick a month.** Finished months move out of the way; the grid shows what is left.
3. **Judge one at a time.** Drag the photo, use the buttons, or press the arrow keys.
   Videos play in place. Undo steps back.
4. **Review and confirm.** The only screen that changes your files. You see everything
   marked for deletion, can pull individual photos back out, then confirm.
5. **Change your mind.** Anything already dealt with can be brought back, from the
   same screen.

### Two ways to delete

| Mode | What it does |
|---|---|
| **Move to trash** (default) | A real deletion. The files leave your library and your server's retention policy decides how long they stay recoverable. |
| **Collect in a folder** | Deletes nothing. The files are gathered into one folder — `/To Be Deleted` by default — so you can look through them in Files and delete them yourself. |

The collection folder is excluded from the index, so photos you have already put there
do not come back around for review.

If `files_trashbin` is disabled on the server, trash mode deletes permanently. The app
detects this and says so, on the settings screen and again in the confirmation dialog,
rather than letting you find out afterwards.

---

## Where "date taken" comes from

This is the whole premise of a month-by-month review, and Nextcloud does not store it
as a first-class field. Photo Sweep walks a chain, best evidence first, and records
which link answered so an odd-looking month is explainable rather than mysterious:

| Order | Source | When it applies |
|---|---|---|
| 1 | **EXIF `DateTimeOriginal`** | Extracted by the Photos app into file metadata. The good case. |
| 2 | **The file name** | `IMG_20240712_140325.jpg`, `PXL_…`, `Screenshot_20240712-140325.png`, `2024-07-12 14.03.25.jpg` and similar. |
| 3 | **Modification time** | Anything with neither — files copied over a network share, most screenshots. |
| 4 | **Upload time** | Storages that report no usable mtime at all. |

Two deliberate details:

- **Dates with no timezone in them are read in *your* timezone**, not the server's. A
  photo taken at 11pm belongs to that day where the person taking it was standing, and
  reading it in the server's zone is what pushes late-night photos into the wrong month
  for anyone whose server is not where they are.
- **A date that only looks like one is rejected.** PHP's date parser does not fail on
  `20241312` — it rolls month thirteen over into January 2025. An eight-digit serial
  number would then invent a month in your grid holding a single photo you cannot
  account for. Every parse is formatted back and must match what was read.

The Photos app is used when present but is not required. Without it you lose step 1,
which the app tells you per photo.

---

## Requirements

- Nextcloud 31 – 34
- PHP 8.1 or newer
- `files_trashbin` (shipped and enabled by default) if you want deletions to be
  recoverable

## Installing

From the Nextcloud app store: **Apps → Multimedia → Photo Sweep → Download and
enable**.

By hand:

```bash
cd /path/to/nextcloud/apps
git clone https://github.com/Nissaar/photo-sweep.git photosweep
cd photosweep
npm ci && npm run build
sudo -u www-data php ../../occ app:enable photosweep
```

The directory **must** be named `photosweep` — Nextcloud resolves an app by its
directory name, and the repository is named after the project rather than the app id.

### First index

The app indexes in the background and shows months as it finds them. On a large
library the first pass is better run from the command line, where there is no request
timeout and you can watch it:

```bash
sudo -u www-data php occ photosweep:index alice --until-complete
```

After that a background job keeps it current. Without a working cron the index only
advances while someone has the app open.

---

## API

Everything the web UI does goes through OCS, so the Android client and any script you
write use exactly the same endpoints. Authenticate with an
[app password](https://docs.nextcloud.com/server/latest/user_manual/en/session_management.html#managing-devices)
over Basic auth and send `OCS-APIRequest: true`.

```
GET    /ocs/v2.php/apps/photosweep/api/v1/index            index state + totals
POST   /ocs/v2.php/apps/photosweep/api/v1/index            advance the index one chunk
GET    /ocs/v2.php/apps/photosweep/api/v1/months           every month, with progress
GET    /ocs/v2.php/apps/photosweep/api/v1/months/2024-07   one month's photos
DELETE /ocs/v2.php/apps/photosweep/api/v1/months/2024-07   forget that month's verdicts
GET    /ocs/v2.php/apps/photosweep/api/v1/decisions/pending
GET    /ocs/v2.php/apps/photosweep/api/v1/decisions/applied
POST   /ocs/v2.php/apps/photosweep/api/v1/decisions        record one, or a batch
DELETE /ocs/v2.php/apps/photosweep/api/v1/decisions/{fileId} undo a pending verdict
POST   /ocs/v2.php/apps/photosweep/api/v1/apply            carry out pending deletes
POST   /ocs/v2.php/apps/photosweep/api/v1/restore          bring applied items back
GET    /ocs/v2.php/apps/photosweep/api/v1/config
PUT    /ocs/v2.php/apps/photosweep/api/v1/config
```

`POST /decisions` accepts either `{fileId, verdict}` or `{verdicts: [{fileId, verdict}, …]}`.
The batch form exists for offline clients: a review session on a train produces a
hundred verdicts that should reach the server in one request, not a hundred.

`POST /apply` takes an optional `{fileIds: [...], permanent: bool}`. With `fileIds` it
carries out only the pending deletes in that list — send the list the user actually
looked at, so a verdict that arrived from another device in the meantime is not carried
out unseen. Without it, every pending delete is carried out. In trash mode on a server
without `files_trashbin` the delete would be permanent, so the request is refused unless
it says `permanent: true`. Refusals are HTTP 409 with a stable code in `data.error`:
`trash_unavailable`, or `apply_running` when another apply for the same user has not
finished. Nothing is touched in either case.

Thumbnails come from core, not from this app:
`/index.php/core/preview?fileId={id}&x=1024&y=1024&a=1`.

---

## Design notes

**Deciding is separated from doing.** A verdict is a row in a table; it touches
nothing. You can go through a thousand photos, change your mind about any of them, and
close the tab having changed nothing at all. Only the review screen acts, and only
after you confirm.

**A verdict is marked applied after the file moves, never before.** An interrupted run
therefore leaves work pending and safe to retry, rather than claiming work it did not
do. Each file is handled on its own, so one permission error costs one photo instead
of the batch.

**The index scan is resumable.** It walks the library ordered by file id and stores
how far it reached, so a process killed mid-scan costs one batch of 500 rather than
the whole pass. A "file id greater than" cursor would resume more exactly, but
Nextcloud's file search only accepts `eq` and `in` on `fileid`, so it cannot be
expressed; ordering by file id makes the offset about as stable as an offset gets,
since new uploads take higher ids and land past the window rather than shifting it.
A completed pass then sweeps any row it did not touch, which is what keeps the index
honest about files that left while the app was not watching.

**Private API use is confined to two files.** `MediaFinder` needs `OC\Files\Search\*`
because there is no public factory for a paged `ISearchQuery`; `TrashService` needs
`OCA\Files_Trashbin` because restoring has no public interface. Both check what is
available first and degrade rather than break — `MediaFinder` falls back to the public
`Folder::searchByMime()`, and `TrashService` reports honestly that restoring is not
possible. Everything else is OCP.

**Nothing is indexed for users who never open the app.** A server with a thousand
accounts should not be walking a thousand photo libraries because two people use this.

---

## Building and testing

Requires PHP 8.1+ and Node 22.14+.

```bash
make dev-setup          # composer install && npm ci
make build              # compile the frontend into js/
make test               # coding standard, psalm, phpunit, eslint
make package            # assemble build/photosweep/
make appstore NEXTCLOUD_ROOT=/path/to/nextcloud   # ... and sign and tar it
```

The tests are plain unit tests — they run against the `nextcloud/ocp` stubs and need
no Nextcloud instance. They cover the two places where a quiet mistake would be worst:
resolving capture dates, and bucketing timestamps into months across a timezone
boundary.

Publishing to the app store is documented in [docs/PUBLISHING.md](docs/PUBLISHING.md).

---

## Android app

A companion Android app lives in [`android/`](android/) and talks to the API above. It
signs in with [Login Flow v2](https://docs.nextcloud.com/server/latest/developer_manual/client_apis/LoginFlow/index.html),
so your password is typed into your own Nextcloud's login page and the app only ever
holds a revocable app password.

## Licence

AGPL-3.0-or-later. See [COPYING](COPYING).
