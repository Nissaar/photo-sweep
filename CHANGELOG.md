# Changelog

Short notes on what changed for people using Photo Sweep. The newest version goes
first, and its section is what the release page and the in-app "What's new" show.
Android has its own, in `fastlane/metadata/android/en-US/changelogs/`.

## [1.0.5] - 2026-10-19

- Deleting a long list no longer times out. Photos are sent 25 at a time and
  progress is shown, so two thousand photos go through in one tap.
- A batch that is interrupted is retried, and anything that did not go through
  stays on the list to try again.

## [1.0.4] - 2026-09-28

- Confirming deletes only the photos on screen, and a delete that cannot go to the
  trash asks first.
- Sizes and dates follow your Nextcloud language and time zone.
