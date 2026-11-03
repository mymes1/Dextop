# Freeform windowing on Android 15: why Dextop launched every app fullscreen, and the patch that fixes it

Target device for this work: **Samsung Galaxy Tab A9 Wi-Fi, SM-X110, Android 15 (API 35,
One UI 7), Mali-G57 MC2**, app launch through Dextop's virtual desktop, no Dextop
replacement launcher, no root required.

This document is the root-cause report, the patch description, the device guide, the test
protocol and the rollback procedure for the changes on branch `arena/660df280-dextop`
(committed on top of `4d25a58` of `main`).

---

## 1. Summary

Two independent defects made a Dextop session on this tablet lay **every application out
fullscreen**, even though the virtual display was created and mirrored correctly:

1. **The Samsung device rule assumes DeX.** `DeviceProfiles` resolved *every* Samsung
   device to the `samsung_dex` profile, which is `platformManaged = true`,
   `configureFreeformWindowing = false` and has **no** desktop globals. On firmware
   without a DeX desktop (the Tab A9 has no DeX; only the A9+ does) nothing in Dextop
   configured windowing at all, and nothing in the platform did either, so the display's
   task display area stayed in `WINDOWING_MODE_FULLSCREEN` and every activity filling it
   was correct from the framework's point of view.
2. **The only display-windowing switch Dextop used does not exist on Android 15.**
   `DesktopModeConfigurator` forced a display into freeform with the shell command
   `wm set-display-windowing-mode -d <id> 5` (and two equivalent spellings). That command
   was added to AOSP's `WindowManagerShellCommand` **in Android 16**; Android 15 has no
   such command, so all three strategies failed and were logged as framework refusals.
   The policy behind it, `IWindowManager#setWindowingMode(int displayId, int mode)`,
   *does* exist on Android 15 (and since at least Android 12), is what the Android 16
   command wraps, and is reachable from Dextop's privileged runtime - Dextop already
   calls it for its compositing shell's application displays.

The patch (section 5) makes the Samsung profile capability-driven, switches the Dextop
display into freeform through the binder call that exists on API 35, verifies the result
by reading the mode back, escalates only when verification fails, always requests a
freeform launch from `ActivityOptions`, and repairs a task that still comes up fullscreen.
Everything is reported in the session log and the diagnostic report, so a device report
shows what the framework actually accepted.

---

## 2. How the code reaches a fullscreen launch (pre-patch)

Paths are relative to `android/app/src/main/kotlin/moe/n4tsu/dextop/`.

| Step | Code | Behaviour before the patch |
| --- | --- | --- |
| Profile selection | `DeviceProfiles.kt:26-28` (`DesktopEnvironmentRule("samsung_dex", DeviceMatch(manufacturers = setOf("samsung")))`) | Any Samsung device resolves to the DeX profile, independent of whether the firmware has DeX. |
| Profile contents | `DesktopEnvironment.kt:192-196` (`samsungDex()`) | `platformManaged = true`, `temporaryGlobalSettings = emptyMap()`, `configureFreeformWindowing = false`, chrome `HIDDEN`. |
| Session globals | `DesktopModeConfigurator.kt:27-30` | `applyForCurrentDevice()` returns immediately for `platformManaged`, so `enable_freeform_support`, `force_resizable_activities`, `enable_non_resizable_multi_window` and `force_desktop_mode_on_external_displays` are never written. |
| Display windowing | `DesktopModeConfigurator.kt:47` | `configureDisplay()` returns immediately because `configureFreeformWindowing == false` and the session is not a forced-freeform (Auto) session. |
| Launcher hint | `AppCatalog.kt:64-75` | `ActivityOptions.setLaunchWindowingMode(FREEFORM)` is only applied when `startupMode == COMPAT_WINDOWING`; the DeX profile is `OEM_MANAGED`, so the launch is a plain `setLaunchDisplayId`. |
| Home | `MirrorService.kt:12960-12978` | For `samsung_dex`, HOME is `com.sec.android.app.launcher/com.honeyspace.dexservice.SecondaryLauncher`, which does not exist on non-DeX firmware. |

On a DeX device the missing pieces are supplied by One UI. On an SM-X110 there is nothing
to supply them, so all of the following are true at once:

* the overlay display requested through `Settings.Global.overlay_display_devices` is
  created with the display's default task display area in fullscreen mode;
* `ActivityOptions.setLaunchDisplayId(...)` places the activity exactly there;
* `ActivityStarter` validates the launch windowing mode against the request, the target
  display and the activity's resize mode, and the request is "undefined", so the task
  inherits the display's fullscreen mode;
* the compositing shell (`BookShell`) then either adopts the stray fullscreen task into
  one of its own windows or, with the system window manager selected, leaves it where it
  is - fullscreen.

Nothing in that chain is a Dextop UI problem, and no Android setting in the tablet's UI
fixes it either: the relevant developer switches are either absent from the UI or need a
per-display mode that the UI cannot express.

---

## 3. Framework evidence (AOSP, `android15-release`)

All quotes are from `aosp-mirror/platform_frameworks_base`, branch `android15-release`,
fetched while preparing this patch.

### 3.1 The display's effective windowing mode

`services/core/java/com/android/server/wm/DisplayWindowSettings.java`

```java
private int getWindowingModeLocked(SettingsProvider.SettingsEntry settings, DisplayContent dc) {
    int windowingMode = settings.mWindowingMode;
    // This display used to be in freeform, but we don't support freeform anymore ...
    if (windowingMode == WindowConfiguration.WINDOWING_MODE_FREEFORM
            && !mService.mAtmService.mSupportsFreeformWindowManagement) {
        return WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
    }
    // No record is present so use default windowing mode policy.
    if (windowingMode == WindowConfiguration.WINDOWING_MODE_UNDEFINED) {
        windowingMode = mService.mAtmService.mSupportsFreeformWindowManagement
                && (mService.mIsPc || dc.forceDesktopMode())
                ? WindowConfiguration.WINDOWING_MODE_FREEFORM
                : WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
    }
    return windowingMode;
}

void setWindowingModeLocked(DisplayContent dc, int mode) {
    ...
    overrideSettings.mWindowingMode = mode;
    final TaskDisplayArea defaultTda = dc.getDefaultTaskDisplayArea();
    if (defaultTda != null) defaultTda.setWindowingMode(mode);
    mSettingsProvider.updateOverrideSettings(displayInfo, overrideSettings);
}
```

Consequences that drive the patch:

* A **per-display override wins over every desktop-mode heuristic**. It only needs
  `mSupportsFreeformWindowManagement` to be true, which is what
  `Settings.Global.enable_freeform_support` (or the `android.software.freeform_window_management`
  system feature) provides.
* Without an override, a secondary display is freeform only when
  `force_desktop_mode_on_external_displays` is set (`dc.forceDesktopMode()`), i.e. only on
  the "force desktop mode" path - not on any Samsung firmware without DeX.

`DisplayContent.forceDesktopMode()` (`services/core/java/com/android/server/wm/DisplayContent.java:4269`):

```java
boolean forceDesktopMode() {
    return mWmService.mForceDesktopModeOnExternalDisplays && !isDefaultDisplay && !isPrivate();
}
```

### 3.2 There is a per-display binder API on Android 15

`core/java/android/view/IWindowManager.aidl` (`android15-release`, lines 607-621):

```aidl
int getWindowingMode(int displayId);
...
void setWindowingMode(int displayId, int mode);
```

Both methods exist in `android12-release` and later. `WindowManagerService` implements them
(`services/core/java/com/android/server/wm/WindowManagerService.java:7616-7651`) and guards
them with

```java
if (!checkCallingPermission(INTERNAL_SYSTEM_WINDOW, "setWindowingMode()")) { ... }
```

`INTERNAL_SYSTEM_WINDOW` is held by the shell UID - `packages/Shell/AndroidManifest.xml`
line 173 declares it - so Dextop's embedded ADB runtime and Shizuku (both uid 2000) satisfy
the check. Dextop already relies on this for `setShouldShowSystemDecors`,
`setDisplayImePolicy`, `setForcedDisplayDensityForUser` and, in `CompositedWindowShell`,
for the application displays themselves.

### 3.3 The shell command Dextop used is an Android 16 addition

`services/core/java/com/android/server/wm/WindowManagerShellCommand.java`

* `android15-release`: the command table has `size`, `density`, `folded-area`, `scaling`,
  `user-rotation`, `fixed-to-user-rotation`, `set-ignore-orientation-request`,
  `set-letterbox-style`, `set-multi-window-config`, `reset`, `shell`, … and **no**
  `set-display-windowing-mode`.
* `android16-release`: line 167 `case "set-display-windowing-mode": return runSetDisplayWindowingMode(pw);`
  whose implementation is

```java
private int runSetDisplayWindowingMode(PrintWriter pw) throws RemoteException {
    ...
    final int windowingMode = Integer.parseInt(arg);
    mInterface.setWindowingMode(displayId, windowingMode);
    return 0;
}
```

So on Android 15, Dextop's `cmd window`, `wm` and `cmd activity_task` strategies all fail
with "Unknown command", and the failure is indistinguishable in a log from a policy
refusal. The binder call in 3.2 is the same operation without the missing wrapper.

### 3.4 What makes an activity eligible for a window at all

`services/core/java/com/android/server/wm/ActivityTaskManagerService.java:913-980`
reads `enable_freeform_support`, `force_resizable_activities` and
`enable_non_resizable_multi_window` into `mSupportsFreeformWindowManagement`,
`mForceResizableActivities` and `mDevEnableNonResizableMultiWindow`.
`WindowManagerService.SettingsObserver` (`WindowManagerService.java:818-1005`) **observes
those keys live** and updates the same fields (`updateFreeformWindowManagement`,
`updateForceResizableTasks`, `updateDevEnableNonResizableMultiWindow`), so on Android 15
they take effect without a reboot.

`ActivityRecord.supportsMultiWindowInDisplayArea` (`ActivityRecord.java:3374-3390`):

```java
if (!isResizeable() && !tda.supportsNonResizableMultiWindow()) {
    // Not support non-resizable in multi window.
    return false;
}
```

`Task.isResizeable` (`Task.java:2805-2815`) is forced true for standard activities when
`force_resizable_activities` is set. This is exactly the path that lets an activity which
declared `android:resizeableActivity="false"` live in a window at all - and the reason the
patch writes both switches.

---

## 4. What the framework cannot be made to do

Stated here so the result is not oversold:

* **Android 15 has no per-display windowing-mode shell command.** The patch uses the binder
  call instead; the command is used only on Android 16+ (`SDK_INT >= 36`).
* **The switch applies to the task display area, not to each task.** After
  `setWindowingMode` the framework calls `DisplayWindowSettings.setWindowingModeLocked`
  (which sets the override *and* the task display area's requested windowing mode) and then
  `DisplayContent.reconfigureDisplayLocked()`. Tasks that only inherit the display's mode
  follow it, but they keep their current bounds until something places them, and a task
  that carries an explicit windowing override of its own (a fullscreen-requesting activity,
  a pinned task, some games) keeps that override until it is relaunched. That is why the
  patch also verifies and repairs each launch instead of trusting the display switch.
* **Activities that are not resizeable are only windowed when
  `force_resizable_activities` / `enable_non_resizable_multi_window` are accepted** by the
  firmware. Where they are accepted, such apps run in a window and are letterboxed inside
  it (size-compat); their layout is not reflowed, because the app never declared support.
* **Immersive, fixed-orientation and non-resizeable fullscreen activities can still refuse
  a window.** `ActivityInfo.resizeMode`, `screenOrientation`, `--windowingMode` overrides
  by the OEM and hard-coded `SYSTEM_UI_FLAG` use are app/firmware decisions. The patch
  reports these instead of pretending they are windowed.
* **Samsung forks may reject the binder call.** One UI keeps AOSP's `IWindowManager`
  surface, but a vendor change could make `setWindowingMode` a no-op. The verification
  read-back (`getWindowingMode`) is what distinguishes the two; the log and the diagnostic
  report show the observed mode, not the requested one.

---

## 5. The patch

### 5.1 New files

**`DisplayWindowing.kt`**

* `DisplayWindowing` - `WindowConfiguration` windowing modes plus `UNKNOWN`.
* `TaskWindowingDump` / `DumpedTask` / `TaskWindowBounds` - a pure parser for the
  `mode=freeform|fullscreen|multi-window|pinned` token and `bounds=[[l,t][r,b]]` of
  `dumpsys activity activities`, used as the fallback observer and by device reports.
* `DisplayWindowingBridge` - reflective `IWindowManager#getWindowingMode` /
  `#setWindowingMode` over Dextop's privileged runtime, with a cached
  "method present" probe.
* `DesktopWindowingStep` / `DesktopWindowingPolicy` - the ordered plan
  (`KEEP_PLATFORM_FREEFORM`, settings steps, `SET_DISPLAY_WINDOWING`,
  `SHELL_COMMAND_WINDOWING_MODE`, `ENFORCE_PER_TASK`, `UNSUPPORTED`) as pure, unit-tested
  logic.

**`DesktopStackSupport.kt`**

* Capability probe for the firmware window stack: Samsung's `desktopmode` system service
  (the DeX 3.0 service, registered only on firmware that ships it), the
  `android.software.freeform_window_management` feature, and which windowing interfaces
  the API level provides.
* `DesktopStackOverride` + `Settings.Global.dextop_desktop_stack` (`auto` | `platform` |
  `windowed`) so a report can force either answer without rebuilding Dextop.

### 5.2 Changed files

| File | Change |
| --- | --- |
| `DesktopEnvironment.kt` | `DeviceIdentity` carries the probed `platformDesktopAvailable` (null keeps the old assumption for callers with a `Build` snapshot only). New `DesktopEnvironment.requiresFreeformDisplay` and `freeformFallbackSettings`. `samsungDex()` now requires a verified freeform display and carries the fallback settings. New `samsungWindowedDesktop()` profile for Samsung firmware without DeX: AOSP freeform windowing, `platformManaged = false`, `COMPAT_WINDOWING`, visible chrome, no `force_desktop_mode_on_external_displays`. `AndroidDesktopOverrides.windowedDesktopValues` (freeform support, force-resizable, non-resizable multi-window). `DesktopEnvironmentRegistry.current(context)`. |
| `DeviceProfiles.kt` | The Samsung rules (`samsung_trifold`, `samsung_carrier_model`, `samsung_desktop`) resolve DeX vs. windowed desktop from the probe. A non-DeX Tab A9 now gets a profile that configures windowing itself. |
| `DesktopModeConfigurator.kt` | `prepareDesktopWindowing()` applies the window-stack globals before the display is created (journaled, verified, privileged fallback for the write). `ensureDisplayWindowing()` applies the policy plan and **reads the mode back**. `configureDisplay()` runs it first, restricts the Android-16-only shell commands to `SDK_INT >= 36`, and no longer runs the engagement command on platform-managed firmware. `restoreDisplayWindowing()` clears the per-display override when the session ends. `latestReport()` feeds the diagnostics. |
| `AppCatalog.kt` | The freeform launch hint is applied for every profile (not only `COMPAT_WINDOWING`), bounds are set when present, and a rejected launch is retried without the hint instead of being dropped. |
| `MirrorService.kt` | Calls `prepareDesktopWindowing()` before both display-creation paths; new `launchTaskBridge`; `scheduleWindowLaunchRepair()` + `repairLaunchedTaskWindow()` verify that a launch became a freeform task and repair the mode/bounds once, logging the framework's verdict; `privilegedAppLaunch()` falls back to `am start --display <id> --windowingMode 5` as the shell when Dextop's own uid is refused; `windowingSummary()`/`windowingDiagnostics()` expose the verdict. |
| `DiagnosticReport.kt` | New `[FREEFORM WINDOWING]` section: desktop-stack probe, override, profile flags, session windowing verdict, per-display `getWindowingMode` result and per-display task modes. |
| `AndroidAutoMirrorActivity.kt`, `CastCompatibilityStreamer.kt`, `MainActivity.kt` | Resolve the environment with the probe (`current(context)`). |
| `DeviceMatchTest.kt` | New cases: SM-X110 without DeX resolves to `samsung_windowed` with the right globals and no `force_desktop_mode_on_external_displays`; a DeX-capable Samsung keeps `samsung_dex`; an unprobed identity keeps the old assumption; override parsing. |
| `DisplayWindowingTest.kt` (new) | Policy plans per API level, and the task-dump parser (display filtering, `mode=`, `bounds=`, HOME tasks not deciding the answer). |

### 5.3 Behaviour after the patch (SM-X110, Android 15)

1. `DesktopStackProbe` finds no `desktopmode` service → `DeviceIdentity.platformDesktopAvailable = false` →
   `samsung_windowed` profile.
2. Before the display exists: `enable_freeform_support=1`, `force_resizable_activities=1`,
   `enable_non_resizable_multi_window=1` (journaled; restored when the session stops).
   `force_desktop_mode_on_external_displays` is deliberately **not** written, so the
   compositing shell's own public application displays keep filling their display.
3. After the display is attached: `IWindowManager#setWindowingMode(<dextop display>, 5)`,
   then `getWindowingMode(<dextop display>)` → `freeform`. Log:
   `Windowing: display=2 reason=display_configured before=fullscreen after=freeform verified=true steps=...`
   On Android 16+ the same result is cross-checked with
   `cmd window set-display-windowing-mode` only if the binder path did not verify.
4. Every app launch: `setLaunchDisplayId` + `setLaunchWindowingMode(FREEFORM)` + launch
   bounds; ~0.5 s and ~1.4 s later the task is read back. Freeform → `window verified`.
   Fullscreen → one `setWindowingMode` + `setBounds` repair, then a truthful
   `framework kept ... fullscreen` warning if both are refused.
5. HOME: because the profile id is not `samsung_dex`, `launchHome()` uses
   `Intent.CATEGORY_HOME` instead of the non-existent
   `com.honeyspace.dexservice.SecondaryLauncher`.

---

## 6. Build

The sandbox used for this patch has no Android SDK, no JDK, no Flutter SDK and no network
route to `dl.google.com`, `services.gradle.org` or Flutter's artifact host, so **an APK
cannot be produced here**. What was executed is in section 8.

Build on a normal machine (or through the repository's `Debug APK` workflow, which runs on
`push`/`pull_request`/`workflow_dispatch`):

```bash
git checkout arena/660df280-dextop
flutter pub get
flutter build apk --flavor github --debug --dart-define=DISTRIBUTION_CHANNEL=github
# output: build/app/outputs/flutter-apk/app-github-debug.apk
```

Android unit tests (the new cases included):

```bash
cd android && ./gradlew testGithubDebugUnitTest
```

Install:

```bash
adb install -r build/app/outputs/flutter-apk/app-github-debug.apk
```

Privileged access (required by every windowing operation; Dextop itself, its embedded
runtime or Shizuku - no root, no bootloader change, no factory reset, existing data is
kept):

* **Embedded runtime** (Dextop 1.5+): start Dextop, use the in-app privileged-access
  setup and pair wireless debugging. The runtime runs as the shell UID
  (`app_process`, uid 2000), which is the UID that holds
  `android.permission.INTERNAL_SYSTEM_WINDOW`.
* **Shizuku**: install Shizuku, start it over ADB (`adb shell sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh`)
  or wireless debugging, grant Dextop `moe.shizuku.manager.permission.API_V23`.

Both paths keep Dextop's windowing calls inside the framework's own permission model; no
hidden API is used without the required permission being present.

---

## 7. Verifying on the device

Privileged commands (run through `adb shell`; the same commands are what Dextop logs):

```bash
# 1. Which window stack does the firmware provide?
adb shell service list | grep -i desktopmode          # empty output => no DeX service
adb shell pm list features | grep freeform            # android.software.freeform_window_management

# 2. Dextop's profile and windowing verdict
adb shell settings get global dextop_desktop_stack    # null/auto, platform or windowed
adb shell dumpsys activity activities | grep -E "Display: mDisplayId=|mode=" | head -40

# 3. The display mode Dextop set (works on Android 15 too)
adb shell dumpsys window displays | grep -A3 "Display: mDisplayId="
# Android 16+ only:
adb shell cmd window get-display-windowing-mode -d <displayId>

# 4. Task modes on the Dextop display
adb shell dumpsys activity activities | sed -n '/Display: mDisplayId=<displayId>/,/Display: mDisplayId=/p'
```

Expected Dextop log lines (Settings → diagnostics → last session, or
`dumpsys activity` output collected by the diagnostic report):

```
DesktopMode: freeform desktop prepared environment=samsung_windowed global:enable_freeform_support=true ...
Windowing: display=2 reason=display_configured before=fullscreen after=freeform verified=true steps=setting:enable_freeform_support=true,setting:force_resizable_activities=true,setting:enable_non_resizable_multi_window=true,set_display_windowing written=true read=freeform
AppLaunch: requested package=com.android.chrome display=2 windowingMode=freeform bounds=Rect(120, 80 - 1080, 740) ...
AppLaunch: window verified package=com.android.chrome display=2 attempt=1 task=12 bounds=Rect(120, 80 - 1080, 740)
```

A firmware that refuses freeform shows `after=fullscreen verified=false` and
`framework kept ... fullscreen` - that is the honest signal that the display cannot be
windowed by Dextop on that build.

### Test matrix and what each case proves

| # | Scenario | Pass criterion | Framework support vs. workaround |
| --- | --- | --- | --- |
| 1 | Normal resizable app | Task on the Dextop display has `mode=freeform` and the requested bounds | Real: display mode + launch options |
| 2 | App declaring `resizeableActivity="false"` | Task is `mode=freeform`; content is letterboxed inside the window | Real, needs `force_resizable_activities` + `enable_non_resizable_multi_window`; app layout is not reflowed |
| 3 | Fullscreen-only / immersive app | Either a window, or the explicit "framework kept … fullscreen" log | Framework limit; reported, not faked |
| 4 | Two apps at once | Two freeform tasks with disjoint bounds | Real |
| 5 | Resize and move both | `setBounds`/caption drag changes `bounds=[…]`; `window repair … placed=true` if a launch was fullscreen | Real, with per-task repair as fallback |
| 6 | Switch workspaces | Workspace restore relaunches each package; each launch goes through the same verification | Real for placement; app state is not preserved by Android |
| 7 | Close and reopen | Reopen produces a new freeform task with the same bounds | Real |
| 8 | Restart Dextop | Settings are re-applied (`prepareDesktopWindowing`) before the new display exists; workspaces reload | Real; settings are restored on stop and re-applied on start |

The on-device run of this matrix was **not** possible in this sandbox (no tablet, no
build). Section 8 lists what was executed instead.

---

## 8. What was actually executed here

* **Source analysis** of the Dextop tree at `4d25a58` and of AOSP
  `platform_frameworks_base` `android15-release` / `android16-release`
  (files and line references in section 3).
* **Type check of the changed windowing sources against the real API 35 `android.jar`**
  using Kotlin 2.4.21 (`NativeStrings`, `DesktopStackSupport`, `DisplayWindowing`,
  `DesktopEnvironment`, `DeviceProfiles`, `DesktopModeConfigurator`, `AppCatalog`) - zero
  errors. `MirrorService.kt`, `DiagnosticReport.kt`, `AndroidAutoMirrorActivity.kt`,
  `MainActivity.kt` and `CastCompatibilityStreamer.kt` were parse-checked (no syntax
  errors); they depend on Flutter/Shizuku/Compose artifacts that are not reachable from
  this sandbox, so they were reviewed rather than compiled.
* **Automated tests executed**: 22 tests, all passing -
  `DeviceMatchTest` (13, including the four new cases) and `DisplayWindowingTest` (9,
  new). Run command in the session log; the same files run under
  `./gradlew testGithubDebugUnitTest` in CI.
* **Not executed**: Gradle/Flutter build (no SDK/network), APK install, on-device matrix.
  Nothing in this document claims a device result.

---

## 9. Known limitations

1. **No APK is attached.** The environment cannot build one (section 6/8). Build with the
   repository workflow or a local SDK; the patch is source-complete and type-checked
   against API 35.
2. **Existing fullscreen tasks are reconfigured by the framework, but keep their old
   bounds**, and a task with an explicit fullscreen override of its own is not converted.
   Reopen such an app, or let the shell adopt it (`PlasmaShell.adopt` does this through
   `WindowContainerTransaction`).
3. **Non-resizable apps are letterboxed, not reflowed.** Android draws them at their
   supported size inside the window. That is the framework's size-compat behaviour.
4. **Activities that force fullscreen** (immersive media, some games, activities with a
   locked orientation and `RESIZE_MODE_*` restrictions the firmware honours) may still
   refuse a window or snap back. The patch logs the exact refusal.
5. **Vendor forks.** If Samsung's `IWindowManager` no-ops `setWindowingMode`, the read-back
   is `fullscreen` and Dextop reports the display as unwindowable instead of pretending.
   `dextop_desktop_stack=windowed|platform` lets a test force either profile.
6. **The compositing shell's application displays are intentionally left fullscreen.** Each
   app runs on its own display and is drawn inside a Dextop window; turning those displays
   freeform would put a floating window inside a window. This is why
   `force_desktop_mode_on_external_displays` is not written for this profile.
7. **Android 16+ differs.** There the shell command exists; the patch keeps both paths and
   verifies rather than assuming.

---

## 10. Rollback

The change is a normal source patch on one branch; it writes no persistent firmware state.

* **Code**: `git revert <commit>` (or check out `main` and rebuild), then reinstall the
  APK. Nothing outside the app is modified.
* **Settings restored by Dextop**: the window-stack globals are journaled by
  `SessionJournal` and restored when the session stops, exactly like every other temporary
  global. If a session is killed, the journal is replayed on the next start
  (`ADB-System-Recovery` flow).
* **Manual restore** (only needed if a session was force-killed and the journal is gone):

  ```bash
  adb shell settings delete global enable_freeform_support
  adb shell settings delete global force_resizable_activities
  adb shell settings delete global enable_non_resizable_multi_window
  adb shell settings delete global dextop_desktop_stack
  ```

  Deleting a key restores the firmware default; these keys are developer switches that
  only affect multi-window behaviour while Dextop runs.
* **Per-display override**: `IWindowManager#setWindowingMode` writes an override for the
  Dextop display only. The display is a synthetic overlay display and its settings are
  dropped with it; `restoreDisplayWindowing()` clears it earlier when a session stops
  while the display still exists.
* **Uninstall** the app restores nothing else: no root, no bootloader change, no factory
  reset, no user data is touched.

---

## 11. Patch file

`docs/patches/sm-x110-freeform-windowing.patch` is the same change as a `git format-patch`
series, applicable to a clean checkout of `main` (`4d25a58`):

```bash
git checkout -b sm-x110-freeform main
git am docs/patches/sm-x110-freeform-windowing.patch
```
