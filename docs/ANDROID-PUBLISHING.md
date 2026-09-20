# Publishing the Android client

Written for whoever maintains releases of this app.

[android/README.md](../android/README.md) already covers building, the signing key and
the CI secrets. This document is only about getting the APK in front of people, and
about the two things that decide how long that takes: a Play account gate that is
measured in weeks, and an F-Droid review queue that is measured in months.

Nothing here blocks shipping. An `android-v*` tag already builds, signs and attaches
an APK to a GitHub release, and that release is a perfectly good distribution channel
on its own. Treat the stores as additions to it rather than the way in.

> **Play is deliberately not being pursued for now.** The tester gate below costs
> twelve real people and a fortnight before the app can reach anybody, which is a poor
> trade for a self-hosting audience that largely installs from F-Droid anyway. The
> section is kept because the decision is worth revisiting once the app has users, and
> because the API level requirement it forced is good practice regardless.

## The three channels, and what each actually costs

| | GitHub release | F-Droid | Google Play |
|---|---|---|---|
| Available | Already working | Weeks to months of review | ~2 weeks minimum, see the tester gate |
| Costs | Nothing | Nothing | $25, once, plus identity verification |
| Signed by | Your key | **F-Droid's key** | Your key, or Google's if you opt in |
| Reaches | People who read the README | Privacy-minded Android users | Everyone else |
| Updates | Manual download | Automatic | Automatic |

Self-hosting people are disproportionately F-Droid users, so for this app the middle
column matters more than it would for most.

## Identifiers you cannot change later

Two of them, and both are set the moment you first publish:

- **`applicationId`** — `io.github.nissaar.photosweep`. Neither store will reissue it,
  and on Play it is the package name in your own store URL forever.
- **The signing key** — for Play and for direct APKs. F-Droid is the exception: it
  builds from source and signs with its own key, which is also why an app cannot be
  moved between F-Droid and Play installs without an uninstall.

`versionCode` must increase on every release. It is derived from the tag in
[app/build.gradle.kts](../android/app/build.gradle.kts), so the tag is the only place
you set a version.

---

## Google Play

### 1. The tester gate — read this before anything else

A **personal** developer account created after 13 November 2023 cannot ship to
production until it has run a closed test with **at least 12 testers, opted in
continuously for 14 days**. Since 2026 Google also checks the testers actually used
the app, so twelve names who never open it will not clear the gate.

This is the long pole. It means Play is a fortnight away at the very best, and it
needs twelve real people with Google accounts who are willing to install the app and
use it. Organisation accounts are exempt, but registering as an organisation requires
a D-U-N-S number, which is its own delay.

Plan around it: start the closed test early and let it run in the background while the
app ships through GitHub and F-Droid.

### 2. Create the account

[play.google.com/console](https://play.google.com/console) — $25 once, plus identity
verification. Allow a few days.

### 3. Meet the target API requirement

Already done: the app targets API 36, which
[Play has required of new submissions since 31 August 2026](https://developer.android.com/google/play/requirements/target-sdk).
Expect to raise it again each August, or the listing quietly stops being offered to
devices newer than the target.

### 4. Fill in the declarations

The tedious part, and the part that gets listings rejected:

- **Data safety.** This app collects nothing and sends nothing anywhere except the
  user's own Nextcloud. Say exactly that. The one thing to declare honestly is the
  app password, which is encrypted under an Android Keystore key, stays on the
  device, and is never transmitted to you.
- **Content rating** questionnaire — a photo utility rates as low as it goes.
- **Privacy policy URL** — required even when you collect nothing. A page in this
  repository is acceptable; it has to be reachable and stable.
- **Store listing** — screenshots for phone, a 512×512 icon, a feature graphic
  (1024×500). Play rejects listings for missing graphics more often than for anything
  else.

### 5. Upload

Play wants an **AAB**, not the APK the release workflow builds:

```bash
cd android
./gradlew :app:bundleRelease
```

Sign it with the same keystore, or enrol in Play App Signing and let Google hold the
key. Enrolling is generally the safer choice for a solo maintainer — it means losing
your keystore does not end the app — but it is irreversible.

---

## F-Droid

F-Droid does not accept uploads. You ask them to package the app, they build it from
source on their own infrastructure, and they sign it with their key.

### What it requires of the app

All already true here, but worth knowing why:

- **Everything FOSS.** AGPL-3.0, and every dependency is Apache-2.0 or compatible.
- **No proprietary libraries.** No Google Play Services, no Firebase, no analytics.
  This is the requirement that disqualifies most apps, and the reason to keep it that
  way when adding dependencies later.
- **Builds reproducibly from a tag** with no manual steps.
- **Anti-features declared** if any apply. None do.

### Submitting

1. Open a **Request For Packaging** issue at
   [gitlab.com/fdroid/rfp](https://gitlab.com/fdroid/rfp) describing the app and
   linking the repository, or go straight to a merge request against
   [fdroiddata](https://gitlab.com/fdroid/fdroiddata) with a build recipe, which is
   faster if you are comfortable writing one.
2. The recipe pins a tag and commit, and declares the Gradle task to run. Since the
   version comes from the tag, keep tagging exactly as now.
3. Expect **weeks to months**. The queue is volunteer-run. This is not a reason to
   delay the GitHub release.

### Making the listing good for free

F-Droid reads listing text and screenshots straight out of the repository, and this is
already in place:

```
fastlane/metadata/android/en-US/
├── title.txt
├── short_description.txt       # 80 characters
├── full_description.txt
├── images/
│   ├── icon.png                # 512×512
│   └── phoneScreenshots/       # sorted alphabetically, hence the numbers
│       ├── 01-months.png
│       ├── 02-deck.png
│       ├── 03-review.png
│       └── 04-settings.png
└── changelogs/
    ├── 10000.txt               # the versionCode, not the version name
    └── 10001.txt
```

**It has to be at the repository root**, not beside the Android sources. F-Droid's
scanner looks at the top level and at the Gradle module directory, and `android/`
is neither — with it there the bot reports "Fastlane was not found in your repo" and
the listing gets no description or screenshots at all.

A `changelogs/` file is named after the **versionCode**, which this project derives
from the tag: 1.0.0 becomes 10000, 1.0.1 becomes 10001. Getting that wrong means the
changelog silently does not appear.

### What the scanner bot checks

It comments on the RFP within minutes, and it is worth reading rather than waiting:

- **`distributionSha256Sum`** must be in `gradle-wrapper.properties`. Without it the
  Gradle download is unverified, which is a real supply-chain hole. Set it with
  `./gradlew wrapper --gradle-version <v> --gradle-distribution-sha256-sum <sum>`,
  which also keeps `gradle-wrapper.jar` in step with the version the properties
  declare — the bot checks that they match.
- **Tracker libraries.** It scans every Gradle configuration, including Android's
  internal test-platform tooling, so it can report trackers that are nowhere near the
  APK. Check before believing it: `unzip -p app-release.apk classes.dex | strings |
  grep -c <name>`.

---

## Suggested order

1. **Tag `android-v1.0.0`.** The APK is on GitHub the same day, and that is a real
   release people can install.
2. **Start the Play closed test** immediately, because the fourteen days only start
   once twelve testers are in.
3. **Open the F-Droid RFP** in parallel. Add the fastlane metadata first.
4. **Promote to Play production** when the gate clears.

Steps 2 and 3 both have waiting built into them, and neither blocks step 1.
