# Publishing to the Nextcloud app store

Written for whoever maintains releases of this app.

Getting an app into the store is a one-off setup (a certificate and a registered app
id) followed by a repeatable release (tag, and let CI do the rest). The setup half
cannot be automated: it involves a pull request a human has to merge.

## Names, and which of them matter

Three names are in play and only one of them is permanent:

| Name | Value | Changeable? |
|---|---|---|
| **App id** | `photosweep` | **No.** It is the certificate's `CN`, the app store registration, the directory the app must live in, and the URL of every OCS endpoint. Changing it means a new certificate and, to users, a different app. |
| Display name | Photo Sweep | Yes. `<name>` in `info.xml`, shown in the store and the app menu. |
| Repository | `photo-sweep` | Yes. GitHub only; Nextcloud never sees it. |

The id is restricted to `[a-z]+[a-z0-9_]*[a-z0-9]+` — lowercase letters, digits and
underscores, 32 characters at most. No hyphens, so a repository name with one in it
cannot be used as an id.

### Do not put "Nextcloud" in the name

This is a hard rule, not a preference. The
[app store rules](https://docs.nextcloud.com/server/stable/developer_manual/app_publishing_maintenance/publishing.html)
say plainly:

> Apps must not use 'Nextcloud' in their name.

and breaking the guidelines means an app "might be blocked from the app store
altogether". The
[trademark guidelines](https://nextcloud.com/trademarks/) go further — "you should not
include a Nextcloud mark in the name of your application, product or service" — and
for Google Play and the App Store, "you can **NOT** use the term 'Nextcloud' in the
name of your app".

What *is* allowed is describing compatibility: "for Nextcloud", "compatible with
Nextcloud". That is why the summary, the README and this document say it freely while
the name does not. The same applies to the Android client's label and package id.

---

## 1. Generate the signing key

The private key is the app's permanent identity. The store accepts an update only if
it is signed by the same key, and **there is no recovery** — a lost key means asking
Nextcloud to revoke the old certificate and issue a new one, which takes another pull
request and leaves every existing install unable to verify the app in the meantime.

```bash
openssl req -nodes -newkey rsa:4096 -keyout photosweep.key -out photosweep.csr \
        -subj "/CN=photosweep"
```

The `CN` **must** be exactly the app id, `photosweep`. The store checks this.

### Back it up before doing anything else

- **A password manager**, as a file attachment. This is the single most useful copy.
- **An offline copy** — a USB drive, or printed base64 in a safe. Something that
  survives losing this machine and your cloud accounts at the same time.
- **Not** in this repository, not in a shared folder, not in a chat message.

`.gitignore` already refuses `*.key` and `*.crt`, but that is a safety net, not a plan.

## 2. Get the certificate signed

Open a pull request against
[nextcloud/app-certificate-requests](https://github.com/nextcloud/app-certificate-requests)
adding `photosweep/photosweep.csr`, with a link to this repository in the
description. A Nextcloud maintainer reviews it and commits the signed
`photosweep.crt` back to that repository.

This takes days rather than minutes. Start it before you plan to release.

Once merged:

```bash
mkdir -p ~/.nextcloud/certificates
mv photosweep.key ~/.nextcloud/certificates/
curl -sSfL https://raw.githubusercontent.com/nextcloud/app-certificate-requests/master/photosweep/photosweep.crt \
     -o ~/.nextcloud/certificates/photosweep.crt
chmod 600 ~/.nextcloud/certificates/photosweep.key
```

## 3. Register the app id

Sign in at [apps.nextcloud.com](https://apps.nextcloud.com) with a Nextcloud account,
then register `photosweep` under **Developer → Register app**. The id has to match
the certificate's `CN` and `appinfo/info.xml`'s `<id>`.

Take an API token from your account settings — the release workflow uses it.

## 4. Add the CI secrets

Under **Settings → Secrets and variables → Actions**:

| Secret | Value |
|---|---|
| `APP_PRIVATE_KEY` | contents of `photosweep.key` |
| `APP_CERTIFICATE` | contents of `photosweep.crt` |
| `APP_STORE_TOKEN` | your app store API token |

Without them the release workflow still runs, and publishes an **unsigned** tarball
with a warning. That is useful for testing the pipeline; the app store will refuse it.

## 5. Release

```bash
# 1. bump <version> in appinfo/info.xml, then the same number in package.json and
#    package-lock.json together:
npm version --no-git-tag-version 1.0.1
# 2. commit
git tag v1.0.1
git push origin v1.0.1
```

The tag has to be exactly `v` and three plain numbers (`v1.0.1`, not `v1.0.1-rc1`).
The workflow then checks it against `info.xml` (a mismatch fails the build rather
than shipping a mislabelled release), builds the frontend and assembles the package.
In parallel it runs the whole of CI against the tag, including the acceptance runs
on real Nextcloud 31 and 34, and nothing is signed until all of that passes.

Signing and publishing happen in a second job, the only one that sees the secrets.
It runs no npm or composer at all: it signs the package's contents with
`occ integrity:sign-app` in a Nextcloud image pinned by digest, tars it, attaches it
to the GitHub release, and posts the download URL and a detached signature to the app
store API.

The tarball is reproducible: entries sorted, every timestamp set to the tagged
commit's, owned by root, no name or time in the gzip header. Building the same tag
twice gives the same bytes.

### Doing it by hand

`NEXTCLOUD_ROOT` is a server directory, the one holding `occ`; `make appstore` stops
with a message if it is missing. The tarball step needs GNU tar — on macOS install it
and add `TAR=gtar`.

```bash
make appstore NEXTCLOUD_ROOT=/path/to/nextcloud
openssl dgst -sha512 -sign ~/.nextcloud/certificates/photosweep.key \
        build/artifacts/photosweep-1.0.1.tar.gz | openssl base64 -A
```

Then upload the tarball somewhere permanent and POST it. `--fail-with-body` shows the
store's reason when it refuses the release:

```bash
curl --fail-with-body -X POST https://apps.nextcloud.com/api/v1/apps/releases \
     -H "Authorization: Token $APP_STORE_TOKEN" \
     -H 'Content-Type: application/json' \
     -d '{"download": "https://…/photosweep-1.0.1.tar.gz", "signature": "…", "nightly": false}'
```

---

## What the store checks

- `appinfo/info.xml` validates against
  [its schema](https://apps.nextcloud.com/schema/apps/info.xsd). CI validates this on
  every push, because otherwise you discover a malformed `info.xml` at the moment you
  are trying to publish.
- The tarball contains exactly one top-level directory, named `photosweep`.
- The detached signature verifies against the registered certificate.
- `<nextcloud min-version>`/`<max-version>` decide which servers are offered the app.
  **Raise `max-version` when a new Nextcloud comes out**, or the app quietly disappears
  from the store for everyone who upgrades.

## Version support

| Nextcloud | Status |
|---|---|
| 31 – 34 | Supported, declared in `info.xml` |
| ≤ 30 | Not supported. The app uses the FilesMetadata API and PHP 8.1 syntax. |

