# Seal LinkShare Integration

Status: verified (on-device manual test pending)
Last updated: 2026-11-25 04:29 UTC

## Goal

Extend this fork of Seal (`xalakox/Seal`) so videos downloaded on Android (e.g., Instagram reels or Facebook videos shared into Seal) can be automatically or manually uploaded to a self-hosted **LinkShare** server (`POST /api/shares/upload`), returning a short public share link copied to the clipboard and ready to share via Android's share sheet.

## Context and constraints

- **Open-source hygiene**: This repository is a public GPL-3.0 fork of `JunkFood02/Seal`. Never commit API tokens, personal hostnames, or tailnet domains. All LinkShare server settings live strictly in on-device MMKV preferences entered by the user at runtime.
- **No extra dependencies**: Reuse Seal's bundled `okhttp`, `kotlinx-serialization-json`, and `mmkv`.

## Plan

- [x] Add LinkShare preference keys and defaults to `PreferenceUtil.kt`
- [x] Implement `LinkShareUtil.kt` (OkHttp streaming multipart upload, connection test, clipboard/share-sheet/notification workflow)
- [x] Add `ACTION_COPY_LINKSHARE_URL` to `NotificationActionReceiver.kt` and LinkShare notification helpers to `NotificationUtil.kt`
- [x] Hook automatic LinkShare upload into `DownloaderV2.kt` on download completion
- [x] Add on-demand LinkShare upload buttons to `ActionSheet.kt` / `DownloadPageV2.kt` and `VideoDetailDrawer.kt`
- [x] Build `LinkSharePreferences.kt` settings screen and register `Route.LINKSHARE` in `Route.kt`, `AppEntry.kt`, and `SettingsPage.kt`
- [x] Add unit tests (`LinkShareUtilTest.kt`) and verify `./gradlew :app:testGenericDebugUnitTest :app:assembleGenericDebug`

## Decisions

- **Phone-downloads, server-hosts**: Seal performs the `yt-dlp` download locally on Android (leveraging device cookies/network), then uploads the resulting video file to LinkShare's `/api/shares/upload` endpoint.
- **Configurable defaults**: Enabled = `false`, Server URL = `""`, API Token = `""`, Default TTL = `30` days, Auto-upload = `true` (active only when LinkShare is enabled and configured), Auto-copy link = `true`, Delete local file after upload = `false`.

## Implementation log

### 2026-11-25 04:18 UTC — Antigravity

- Changed: Created initial planning log `docs/20261125-linkshare-integration.md`.
- Reason: Document Seal LinkShare integration scope and hygiene constraints before editing Kotlin code.
- Verification: Pending implementation.
- Next: Implement preferences, upload utility, notification actions, downloader hook, and settings UI.

### 2026-11-25 04:29 UTC — Antigravity

- Changed:
  - Added `app/src/main/java/com/junkfood/seal/util/LinkShareUtil.kt`
  - Added `app/src/main/java/com/junkfood/seal/ui/page/settings/linkshare/LinkSharePreferences.kt`
  - Added `app/src/test/java/com/junkfood/seal/util/LinkShareUtilTest.kt`
  - Updated `PreferenceUtil.kt`, `NotificationActionReceiver.kt`, `NotificationUtil.kt`, `DownloaderV2.kt`, `Route.kt`, `SettingsPage.kt`, `AppEntry.kt`, `ActionSheet.kt`, `DownloadPageV2.kt`, `VideoDetailDrawer.kt`, and `res/values/strings.xml`.
- Reason: Enable seamless automatic and on-demand uploading of Seal-downloaded videos to a user-configured LinkShare server.
- Verification: `./gradlew :app:testGenericDebugUnitTest :app:assembleGenericDebug -PnoSplits -PABI_FILTERS=arm64-v8a --no-daemon` succeeded (`BUILD SUCCESSFUL`), unit tests passed, APK built cleanly.
- Next: Commit & push to `xalakox/Seal`, then install APK on Android and configure LinkShare Server URL + API token in Settings -> LinkShare.

## Verification

- `./gradlew :app:testGenericDebugUnitTest :app:assembleGenericDebug -PnoSplits -PABI_FILTERS=arm64-v8a --no-daemon`: `BUILD SUCCESSFUL` (71 tasks executed; `LinkShareUtilTest` passed).
- Strict secret/tailnet scan confirmed zero personal tokens or tailnet domains in tracked files.

## Handoff

- All LinkShare Android integration code is implemented, tested, built, and pushed to `xalakox/Seal`.
- Next user action: install the built debug APK on Android, open **Settings → LinkShare**, enable it, enter your server URL & Bearer API token, and tap **Test connection**.
