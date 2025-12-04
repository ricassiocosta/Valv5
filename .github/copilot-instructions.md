# Copilot / AI Agent Instructions for Valv5

Purpose: Quickly onboard an AI coding agent so it can be productive in this repository. Focus on concrete, discoverable patterns and commands (no generic advice).

Quick start (build/test/run)
- Build the Android app (uses Gradle Kotlin DSL): `./gradlew assembleDebug` or `./gradlew :app:assembleDebug`.
- Install debug APK on a connected device: `./gradlew :app:installDebug`.
- Run unit tests: `./gradlew test` (tests live under `app/src/test` if present).
- CLI tool (separate): see `valv-cli/README.md`. Typical steps: `pip install -r valv-cli/requirements.txt` and `./valv-cli/build.sh`.

Big-picture architecture
- Two main deliverables in this repo:
  - `app/` — Android application (Java with AndroidX, navigation using `NavHostFragment`).
  - `valv-cli/` — CLI Python wrapper + Java encryption engine for offline encryption tasks.
- Key runtime singletons and data flows:
  - `Password` (`app/src/main/java/.../data/Password.java`) — session password + `Password.lock(context, boolean)` used to clear session and wipe memory.
  - `EphemeralSessionKey`, `SecureMemoryManager` — secure memory lifecycle and cleanup hooks.
  - `Settings` (`utils/Settings.java`) — central SharedPreferences keys (constants like `PREF_APP_PREFERRED_APP`, `PREF_APP_RETURN_TO_LAST_APP`, `PREF_APP_BACKGROUND_LOCK_TIMEOUT`). Use these getters/setters rather than accessing SharedPreferences directly.
  - Navigation: app uses Navigation Component. The locked state equals the destination `R.id.password` (see `MainActivity` checks).

Important files to read first
- `app/src/main/java/ricassiocosta/me/valv5/MainActivity.java` — app lifecycle, background lock logic, `ScreenOffReceiver`, `startBackgroundLockTimer()`, `checkBackgroundLockTimeout()` and `performBackgroundLock()`.
- `app/src/main/java/ricassiocosta/me/valv5/utils/Settings.java` — all prefs keys and defaults.
- `app/src/main/java/ricassiocosta/me/valv5/data/Password.java` — how the app clears credentials and performs secure cleanup.
- `valv-cli/README.md` and `valv-cli/valv_cli.py` — CLI usage and build.

Project-specific conventions & patterns
- Security-first: no disk caching of decrypted content — decrypted data stays in memory and is wiped via `SecureMemoryManager`.
- SharedPrefs usage is centralized in `Settings` (use `Settings.getInstance(context)` when reading/writing prefs).
- Logging: `SecureLog` is used for debug/error logs — prefer it over `System.out`.
- Navigation state determines locked/unlocked behavior: code checks `NavController.getCurrentDestination().getId() == R.id.password` to infer locked state.
- Broadcast receivers: screen-off behavior is handled with a dynamic receiver in `MainActivity.ScreenOffReceiver`. Be aware of lifecycle ordering when modifying registration/unregistration.
- Generated artifacts (do not modify): `app/build/generated/...` and `app/build/intermediates/...`.

Integration points & cross-component communication
- App <> CLI: `valv-cli` produces files compatible with the app's V5 format. The encryption engine Java code sits in `valv-cli/` (see `ValvEncryptionCLI.java`).
- Settings and session state are shared via singletons and SharedPreferences; locking is performed by calling `Password.lock(context, boolean)` which triggers `SecureMemoryManager` cleanup and `EphemeralSessionKey.destroy()`.
- Opening external apps: `MainActivity.returnToLastApp()` uses `getPackageManager().getLaunchIntentForPackage(packageName)`; check `Settings.getPreferredApp()` for user-preferred targets.

Common pitfalls to watch for (examples from codebase)
- Lost `ACTION_SCREEN_OFF` broadcasts: registering/unregistering receivers in `onStart()`/`onStop()` can miss broadcasts due to lifecycle ordering — MainActivity already contains safeguards (receiver registration in application context). If you change this logic, test with `adb shell input keyevent KEYCODE_POWER` and `adb logcat`.
- Starting activities while screen is off/keyguard present can be blocked on some OEMs. When automating app launches from `onReceive`, add error handling and fallbacks.
- Sensitive cleanup must call `Password.lock(...)` and `SecureMemoryManager.performFullCleanup(context)` consistently. Follow existing pattern rather than ad-hoc wipes.

Debugging tips & important commands
- Filter logs for relevant tags: `adb logcat -c && adb logcat | grep -E "(FATAL|AndroidRuntime|java\.|Exception|OutOfMemory|SecretStream|Encryption)" 2>&1`.
- Rebuild and reinstall a debug APK quickly. Use the Gradlew extension:

Editing & tests guidance
- Do not modify generated code under `app/build/` — change source in `app/src/...` and re-run Gradle.
- When adding instrumentation logs for lifecycle issues (e.g., screen-off flow), place them in `MainActivity` next to `ScreenOffReceiver.onReceive`, `performBackgroundLock()`, and `Password.lock(...)` to observe flow.

What to ask the human developer
- Which devices/OEMs are high-priority for QA (some behavior may be OEM-specific)?
- Preferred behavior for failures when `returnToLastApp()` cannot open the target (immediate home fallback, retry on unlock, or record for later handling)?

If anything here is unclear or you want the agent to include more examples (e.g., complete grep patterns, unit test commands, or an AGENT.md with longer workflows), tell me which area to expand and I will iterate.
