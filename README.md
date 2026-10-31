# Bible Audio / Nghe Kinh Thanh

**[DOWNLOAD THE LATEST ANDROID APP](https://github.com/ZohanDam/Onl_Offline-Bible-Audio-Android-apk/releases/latest)**

This README is the single guide for users, developers, and AI assistants.
GitHub Releases contains installers. The repository contains source code, not APKs.

## Install Or Update

1. Open the download link above. Under **Assets**, download the `.apk` file.
   Do not download `Source code` or `update.json`.
2. Open the APK on an Android phone (Android 8 or newer). Allow installation
   from that source if Android asks, then confirm installation.
3. Install over the existing app. **Do not uninstall**, because that can remove
   downloaded audio and settings.

For later updates, use **Settings > Check for updates** inside the app.
After the repository rename, versions 2.24 and earlier need one manual APK update
to 2.25 or later; install over the existing app to preserve downloads.
The original 2.14 signing key differs; updating that installation requires its
original private key. Versions 2.15 and later share the current signing identity.

## Use The App

- Read VIE1925, VIE2010, or NIV offline. Each has 66 books and 1,189 chapters.
- Audio is streamed or downloaded inside the app, never bundled with the APK.
  Download a chapter, a book, or the whole Bible; downloads use up to 12 workers.
- VIE1925 audio needs about 1.16 GB; NIV audio needs about 1.08 GB.
  NIVUK (David Suchet) needs about 1.20 GB.
  VIE2010 text uses VIE1925 audio, not a VIE2010 recording.
- English audio: NIVUK (David Suchet, Anglicised) is first and the default;
  NIV (Max McLean) remains selectable. Choose the recording above the player or
  in Downloads. Changing it preserves play/pause state and starts the chapter
  from the beginning. Files, positions, download queues, and loops stay separate.
  Existing NIV files are retained. English text remains NIV, not NIVUK text.
- Use the speaker icon to hide/show playback controls. Chapter navigation remains
  available. Changing chapters while paused does not start playback.
- Settings includes font size, VN/EN interface language, day/night icons, and
  named playback loops. Save books or chapters in selection order; repeat the
  whole selection. Selecting outside the active loop turns looping off, but
  keeps saved loops. NIV and VIE1925 loops are separate.
- Settings stays open when choosing options; font sizes can be previewed without
  reopening the chooser. Close the chooser explicitly; Back or tapping outside closes the menu.
- Playback supports background/lock-screen controls, rewind 15 seconds, speed,
  and a sleep timer. Timer remaining time is shown in whole minutes.

## Folder Layout

```text
README.md       The only project guide
app/            Android application, bundled text, notices, and regression tests
gradle/         Gradle wrapper; keep gradlew and the root Gradle files too
tools/          Release, validation, and authorized data-maintenance tools
install/        One current local installer: NgheKinhThanh1925.apk (not in Git)
archive/        Old installers/builds/settings; recovery only (not in Git)
```

Old APKs are removed from the repository's current files; Git history and older
Releases preserve published versions. Never use an archived installer as latest.
To refresh the local installer from the official release:

```powershell
.\tools\sync_latest_release.ps1
```

This checks the release URL, version metadata, size, and SHA-256 before replacing
the local installer. A different existing installer is archived first.

## Develop And Verify

Open this project folder in Android Studio. Use JDK 21, Android SDK 35, and Git.
Android Studio supplies the SDK location in the untracked `local.properties`.
Keep caches/builds outside OneDrive; run these commands from the project root:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:GRADLE_USER_HOME = "$env:LOCALAPPDATA\NgheKinhThanh1925\gradle"
$build = "$env:LOCALAPPDATA\NgheKinhThanh1925\build"
.\tools\check_project.ps1
.\tools\test_release_tools.ps1
.\gradlew.bat testDebugUnitTest lintRelease assembleRelease "-PngheBuildDir=$build" "-PdebugKeystore=$env:USERPROFILE\.android\debug.keystore"
```

The release APK is `$build\outputs\apk\release\app-release.apk`.
Robolectric tests simulate Android; they do not replace testing on a real phone.
Use the existing private signing key, not a newly generated key. Its certificate
SHA-256 is `364b713d0baf12a9c45ed74b5c224b8bc8a10d24582eb61939c380456914c613`.
Back it up privately. Never commit keys, credentials, local settings, or builds.

## Publish An App Update

For an app change, increment `versionCode` and `versionName` in
`app/build.gradle.kts`, run the checks above, then commit and push to `main`.
Sign in with Git Credential Manager using an account with repository write access.

```powershell
.\tools\publish_release.ps1 -ApkPath "$build\outputs\apk\release\app-release.apk" -SourceCommit (git rev-parse HEAD) -Notes 'Describe the app changes.'
```

The publisher checks signing/build metadata, uploads one versioned APK and
`update.json` to a draft, verifies the uploaded file, publishes, and refreshes
`install/`. The permanent download link at the top needs no version edits.
Never replace a published APK, put an APK in the source tree, or rewrite history.
Documentation-only cleanup does not require a new app version.

## Data And AI Maintenance

- Start with `git status`; preserve unrelated edits. Work in this checkout,
  not an older staging copy. Run `tools/check_project.ps1` before committing.
- Reader UI: `MainActivity.kt`; background playback: `AudioPlayerService.kt`;
  loops: `PlaybackLoopStore.kt` and the two `PlaybackLoop*Dialog.kt` files.
- Preserve paused state, sleep/focus handling, signing identity, and existing
  downloaded filenames. NIV and Vietnamese audio storage must remain separate.
  Do not add polling, wake locks, or dependencies without a demonstrated need.
- `app/src/main/assets/bible.db` is already complete. Do not rebuild or fetch
  Bible content during ordinary app builds. Authorized refresh tools, in order:
  `import_youversion_text.py`, `import_additional_versions.py`, `import_niv_audio.py`.
  The text importers explain required arguments through `--help`; back up the
  database first. Data tools require Python 3 and BeautifulSoup (`pip install beautifulsoup4`).
  Offline parser tests: `python -m unittest discover -s tools -p 'test_*.py'`.
- Content provenance/copyright notices are retained under `app/src/main/assets/`:
  `VIE1925_SOURCE.txt`, `TEXT_VERSIONS_SOURCE.txt`, `NIV_AUDIO_SOURCE.txt`, and `NIVUK_AUDIO_SOURCE.txt`.
  VIE1925 is public domain; VIE2010/NIV are copyrighted and require permission.
  The owner's stated authorization is not a license for third-party redistribution.
- `tools/import_nivuk_audio.py` imports only the public Suchet NIVUK recording's
  chapter links and sizes. It validates all 1,189 chapters and preserves existing
  database content; `archive/nivuk-audio-cache/` allows resuming.
  No recordings are bundled in the APK.
