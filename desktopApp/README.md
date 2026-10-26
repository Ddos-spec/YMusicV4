# `:desktopApp`

Module detail for the one-line summary in the root [`AGENTS.md`](../AGENTS.md) — read this before working inside the module.

- **Run** — the JVM desktop product shell: `./gradlew :desktopApp:run`.

- **Boot sequence** — boots:
  - `DynamicEngineManager` (QuickJS + engine-js)
  - a file-backed `:media-store` + `:media-scanner` "Scan folder…" (packed ffmpeg, or `-Dio.ymusic.ffmpeg` / PATH)
  - assembles the facade over ffmpeg local + MSE YouTube, injects the SwingPanel MSE surface.

- **Settings, cookies, keys** — one SQLite `app.db` (`AppStore`): settings rows, the AES-GCM sealed `PersistentCookieJar` document (`CookieVault`, OS-user or password key) and the Windows DPAPI / Linux fallback key; the macOS key stays in the Keychain. Pre-4.0.7 loose files are imported once and removed. No Chromium. Sign-in is owned here (`DesktopCookieSignIn` via `AppGraph.cookieSignIn`): scan a QR from the phone app (Settings → Link devices; matching confirmation code + phone unlock), or paste a Copy as cURL dump from Chrome / Firefox / Safari Network. The encrypted handoff goes through the YMusic service's one-shot `/v1/link` slot, deleted as the desktop reads it.

- **Assets** — needs the built `engine-js` bundle in the jar.

- **macOS icon** — `ymusic.icns` is the `CFBundleIconFile`; macOS 26+ draws `CFBundleIconName` from `macos/Assets.car` instead, compiled from the Icon Composer source `macos/YMusic.icon` by `macos/build-icon.sh` (Xcode 26). The build copies the committed `.car` into the bundle after jpackage and re-signs it. Edit the `.icon`, rerun the script, and commit both.

## Windows x86-64

Use a JDK with `jpackage` (JDK 17+), Node.js with npm/npx on `PATH`, and an MSYS2 MINGW64 toolchain with GCC, CMake and Make. Build the sibling [`ffmpeg-build`](https://github.com/ymusic-io/ffmpeg-build) checkout with `scripts/build-desktop.sh` from its MINGW64 shell first. The app expects `../ffmpeg-build/out/windows-x86_64/{include,lib,pack}`.

In PowerShell, set `MSYS2_ROOT` to the MSYS2 installation directory and expose its toolchain before invoking Gradle:

```powershell
$env:PATH = "$env:MSYS2_ROOT\mingw64\bin;$env:MSYS2_ROOT\usr\bin;$env:PATH"
.\gradlew.bat :desktopApp:run
.\gradlew.bat :desktopApp:packageReleaseMsi
```

Windows uses WASAPI audio output and System Media Transport Controls for playback commands and track metadata. The window uses Windows caption controls and borderless fullscreen. Closing the Windows window exits the application; minimize keeps it available from the taskbar.

Outside text inputs, Space toggles playback and F11 toggles fullscreen. Focused text inputs own their typing and editing keys, including Space and arrow keys. Enter remains available to activate a focused button.
