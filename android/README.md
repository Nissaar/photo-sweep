# Photo Sweep for Android

The phone client for the [Photo Sweep Nextcloud app](../README.md). It talks to the
same OCS API the web UI uses, so verdicts given here show up there and the other way
round.

## Signing in

There is no login form. Type your server's address and the app hands you to your own
Nextcloud's login page in a browser, using
[Login Flow v2](https://docs.nextcloud.com/server/latest/developer_manual/client_apis/LoginFlow/index.html).

That is not just tidier — it is the only way an app can honour whatever two-factor or
single sign-on your server uses. What comes back is an **app password**: a per-device
credential you can revoke from **Settings → Security** on your Nextcloud without
changing your actual password. The app never sees the real one.

The app password is held in `EncryptedSharedPreferences`, encrypted under a key the
Android Keystore holds and the app itself cannot read.

On a device whose Keystore is unusable, the password is kept in memory for that
session and never written to disk, so you are asked to sign in again after a restart.
It is not quietly written out in clear text instead — that would make the sentence
above false exactly on the devices where it matters most.

## Swiping offline

A verdict is written to a local queue first and sent afterwards. That is what makes
the deck instant — waiting for a round trip between every photo is what makes going
through a thousand of them unbearable — and it means a train tunnel does not end a
review session.

The queue survives the process being killed, is sent in a single batch when the
connection comes back, and a later verdict for the same photo replaces an earlier one
rather than sending both. The review screen drains it before showing the list, and
says so if anything is still waiting, so you are never asked to confirm a list that is
quietly missing what you just marked.

## Permissions

The release APK declares exactly these, verified against the built manifest:

```
android.permission.INTERNET
android.permission.USE_BIOMETRIC
android.permission.USE_FINGERPRINT
```

No storage access, no account access, no location, no network-state. `media3` pulls in
`ACCESS_NETWORK_STATE` for adaptive-streaming bitrate estimates; this app plays one
progressive file at a time from one known server and gains nothing from it, so the
manifest removes it again.

Other measures:

| Measure | Where |
|---|---|
| App password in Keystore-backed encrypted preferences | `data/AccountStore.kt` |
| `FLAG_SECURE` — no screenshots, screen recording or recents thumbnail | `MainActivity.kt` |
| Optional biometric / device-credential lock | `MainActivity.maybePromptUnlock` |
| Cleartext refused; **user-installed CAs not trusted**, defeating proxy interception | `res/xml/network_security_config.xml` |
| No cloud backup, no device-to-device transfer | `res/xml/data_extraction_rules.xml` |
| Logging stripped from release builds | `proguard-rules.pro` |

## Building

Requires JDK 17+ and the Android SDK (compileSdk 36). Minimum Android 8.0 (API 26).

```bash
cd android
./gradlew :app:assembleDebug          # installable, signed with the debug key
./gradlew :app:testDebugUnitTest      # unit tests
./gradlew :app:assembleRelease        # unsigned; sign before installing
```

Install the debug build:

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Signing

The signing key is the app's permanent identity. Android accepts an update only if it
is signed by the same key as the installed version, so **losing the key means you can
never update the app again** — existing users would have to uninstall. There is no
recovery process.

```bash
../tools/make-keystore.sh
```

Back it up in more than one place: a password manager (the `.jks` as a file attachment
next to its password) and something offline. Not in the repository, not in a public
cloud folder, not in a chat message.

Then add four repository secrets under **Settings → Secrets and variables → Actions**:

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | `base64 -w0 nextcloud-photo-sweep-release.jks` |
| `KEY_ALIAS` | the alias you chose |
| `KEYSTORE_PASSWORD` | the password you chose |
| `KEY_PASSWORD` | the same password (PKCS12 uses one for both) |

Pushing an `android-v*` tag then builds, signs, checksums and publishes the APK.
Without the secrets it still runs and publishes an **unsigned** APK, labelled as such,
so forks work without configuration.

## Layout

```
app/src/main/java/io/github/nissaar/photosweep/
├── api/          Login Flow v2, the OCS client, and the wire models
├── data/         account store, offline verdict queue, repository
├── ui/           Compose screens
└── vm/           view models
```
