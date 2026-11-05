# Changelog

## Unreleased

### 日本語

#### 新機能

- 設定に「ホーム画面のアプリ」を追加しました。Dextop Book のデスクトップ（ホーム画面）に表示するアプリをアプリ一覧から選んで追加・削除できます。Dextop Book の設定ウィンドウの「デスクトップ」ページにも同じアプリ選択を追加しました。

#### 修正

- Samsung製でDeX非対応の端末（Galaxy Tab A9 / SM-X110 など）で、仮想デスクトップ上のアプリが全画面表示になってしまう問題を修正しました。端末のウィンドウスタックを判定してプロファイルを選択し、Android 15 でも利用できる `IWindowManager#setWindowingMode` で仮想ディスプレイをフリーフォームに切り替え、`ActivityOptions` でフリーフォーム起動を要求し、起動後のタスクのモードと境界を検証します。詳細は `docs/freeform-windowing.md` を参照してください。
- `wm set-display-windowing-mode` は Android 16 以降にのみ存在するため、API 35 では実行しないようにしました。
- Dextop Book を初めて起動したときホーム画面にアプリが1つも表示されなかった問題を修正しました。初回起動時に定番アプリ（ブラウザ、ファイル、設定など）をホーム画面へ自動で配置します。また、ランチャーのアプリ一覧がまだ読み込まれていない場合でも、デスクトップのアイコンが PackageManager から取得した情報で表示されるようにしました。
- 一部の端末（DeX非対応の Samsung One UI など）で `MATCH_ALL` 付きのアプリ一覧クエリが空を返す場合に、通常のクエリへフォールバックするようにしました。
- アプリ一覧の取得をさらに堅牢にしました。まず安全な通常クエリ（flags=0）、空の場合はインストール済みアプリの列挙（launch intent 付き）へとフォールバックし、`MATCH_ALL` クエリは最後の手段としてのみ使います（Samsung One UI（DeX非対応）の一部プロセスでは通常クエリが空を返し、`MATCH_ALL` クエリが数十秒ハングするため、ランチャーが空のままになる時間をなくしました）。初回のホーム画面シードはバックグラウンドで行い（シェル起動をブロックしない）、空のまま保存された場合のみ再シードします。シード内容・配置・取得件数・各アプリのレンダリング可否をオペレーションログに記録します。
- Android 15 以下の端末で Dextop Book の画面が黒いままカーソルしか表示されなかった問題を修正しました。`overlay_display_devices` で作られるオーバーレイディスプレイ（Display.TYPE_OVERLAY）では Android 16 未満でアクセシビリティオーバーレイのトークンが発行されず、シェルの `TYPE_ACCESSIBILITY_OVERLAY` ウィンドウが BadTokenException で拒否されていました（失敗は従来 logcat にしか記録されませんでした）。Android 15 以下ではシェルを `TYPE_APPLICATION_OVERLAY` で描画するようにし（`plasmaApplicationOverlay`）、オーバーレイの表示・拒否をセッションログにも記録するようにしました。
- 低スペック端末（RAM 4GB 以下。Galaxy Tab A9 / SM-X110 など）で動作が重かった問題を改善しました。ユーザーがカスタム解像度を選んでいない場合、セッションの仮想ディスプレイをピクセル・density ともに 75% でレンダリングし（dp レイアウトは不変）、ミラー側で実機パネルに拡大表示します。`flutter.disable_low_end_render_scale` で無効化できます。
- タップモードでタップがたまに効かなくなったりスクロールがおかしくなったりする問題を修正しました。仮想ディスプレイへのタッチ注入が途中で拒否されたとき、それまでに受理済みのストリームを一致するポインタ構成の CANCEL で解放するようにしました。これまではターゲット側に「押されたままの指」が残り、スクロールの stuck やタップの無反応が発生していました。また、タップの DOWN が一時的に拒否された場合は短い遅延をおいて1回だけ再送するようにし、「連打しないと反応しない」場面を減らしました。
- Dextop Book のステータスバーにキーボードを閉じるボタンを追加しました。アプリのテキスト入力で仮想ディスプレイに表示されたソフトキーボードを、ボタン一つで閉じられるようにしました（Linux コンテナのキーボードも閉じます）。

### English

#### Features

- Added "Home screen apps" to Settings: pick which apps appear on the Dextop Book desktop (home screen) from the full app list. The same picker was added to Dextop Book's own Settings window under "Desktop".

#### Fixes

- Fixed applications opening fullscreen in Dextop's virtual desktop on Samsung devices without DeX (Galaxy Tab A9 / SM-X110 and similar). The profile is now chosen from the firmware's actual window stack, the virtual display is switched to freeform through `IWindowManager#setWindowingMode` (which exists on Android 15), launches request a freeform window through `ActivityOptions`, and the resulting task's mode and bounds are verified. See `docs/freeform-windowing.md`.
- `wm set-display-windowing-mode` only exists on Android 16 and later and is no longer attempted on API 35.
- Fixed the Dextop Book home screen showing no apps on first start: the usual apps (browser, files, settings, …) are now placed on the desktop automatically, and desktop icons fall back to PackageManager data while the launcher's app list is still loading.
- On some devices (Samsung One UI without DeX), the launcher query with `MATCH_ALL` can return nothing; it now falls back to the plain query so the app list is never empty.
- Made app-list loading robust end to end: the universally safe plain query (flags=0) is tried first, then `MATCH_ALL` only if that comes back empty, and finally installed apps are enumerated via their launch intents — on Samsung One UI without DeX the `MATCH_ALL` query can hang, throw or return nothing, so it is never used on the startup path. The launcher, the Book desktop, the phone home screen and the settings picker all use this path and can never end up silently empty. First-run home-screen seeding now runs in the background (it can never block shell startup), an empty list saved while queries were broken no longer blocks re-seeding, and seeding/placement/query counts are written to the operation log for on-device diagnosis.
- Fixed the Dextop Book screen staying black with only a cursor visible on Android 15 and older. Overlay displays created via `overlay_display_devices` (Display.TYPE_OVERLAY) get no accessibility overlay token before Android 16, so the shell's `TYPE_ACCESSIBILITY_OVERLAY` window was rejected with a BadTokenException — and that failure was only written to logcat, never to the session log. On API 35 and below the shell is now drawn as `TYPE_APPLICATION_OVERLAY` (`plasmaApplicationOverlay`), and overlay show/attach success and failure are recorded in the session log.
- Improved performance on low-end devices (4 GiB RAM or less, e.g. Galaxy Tab A9 / SM-X110): unless a custom resolution profile is selected, the session's virtual display is now rendered at 75% in both pixels and density (the dp layout is unchanged) and the mirror upscales it to the phone panel. Set `flutter.disable_low_end_render_scale` to opt out.
- Fixed tap mode occasionally dropping taps or scrolling erratically: when a touch frame injected into the virtual display is rejected mid-gesture, the previously accepted stream is now released with a matching CANCEL, so the target no longer keeps a "pressed finger" that caused stuck scrolling and dead taps. A transiently rejected tap DOWN is also retried once after a short delay, so a single tap no longer has to be repeated.
- Added a "hide keyboard" button to the Dextop Book status bar: the soft keyboard shown on the virtual display while typing in an app can now be dismissed with one tap (the Linux container keyboard is hidden too).

## 1.7.0

### 日本語

#### 新機能

- Android Autoで走行中の使用にも対応しました。

#### 修正

- Play版でGitHub版の更新情報が表示される問題を修正しました。

#### Car Companion 2.0.0

##### 改善

- Android AutoのSurfaceサイズ変更時に、セッションを作り直さず同じ仮想ディスプレイをリサイズするようにしました。

##### 修正

- Car Companionの切断や異常終了後にセッションが残り、再接続できない問題を修正しました。

### English

#### New

- Added support for using Dextop with Android Auto while driving.

#### Fixes

- Fixed Play builds showing GitHub release update information.

#### Car Companion 2.0.0

##### Improvements

- Android Auto surface-size changes now resize the same virtual display without recreating the session.

##### Fixes

- Fixed Car Companion sessions remaining after a disconnect or abnormal termination and preventing reconnection.

## 1.6.9

### 日本語

#### 修正

- Android 16で履歴画面が小さな中央ウィンドウとして表示される問題を修正しました。
- 履歴画面が対象のデスクトップディスプレイに全画面で表示されるようにしました。

### English

#### Fixes

- Fixed an issue on Android 16 where Recent apps appeared in a small centered window.
- Recent apps now opens fullscreen on the target desktop display.

## 1.6.8

### 日本語

#### 新機能

- 表示倍率をウィンドウ、文字、操作部品にも適用できる設定を追加しました。自動解像度とカスタム解像度の両方に対応しています。
- カバーディスプレイセッションをFoldable端末向けの正式機能に変更し、デフォルトで有効にしました。

#### 修正

- 折りたたみ端末で表示倍率を設定してデスクトップを起動すると、表示が崩れる問題を修正しました。
- 最大付近の表示倍率でOverlay Displayを起動できない問題を修正しました。
- 画面の縦横切り替え後にメニューOverlayのボタンがリサイズされず、余白が生じる問題を修正しました。
- 折りたたみ端末で高い表示倍率を使用すると、Flexモードが繰り返し切り替わり画面が点滅する問題を修正しました。
- 通常モードでウィンドウへの表示倍率が解除され、元のサイズへ戻る問題を修正しました。

### English

#### New

- Added an option to apply display magnification to windows, text, and controls. Both automatic and custom resolutions are supported.
- Promoted cover-display sessions to a standard Foldable feature and enabled it by default.

#### Fixes

- Fixed an issue where the desktop display layout broke when launched with display magnification on foldable devices.
- Fixed Overlay Display failing to start near the maximum display magnification.
- Fixed menu overlay buttons retaining their previous size and leaving empty space after an orientation change.
- Fixed the screen flickering because Flex mode repeatedly toggled when using high display magnification on foldable devices.
- Fixed window magnification being reset to the original size in normal mode.

## 1.6.7

### 日本語

#### 新機能

- 折りたたみ端末のカバーディスプレイを、独立したuinputトラックパッドとして使用できるようにしました。

#### 改善

- 手動で開いたBlackBerryモードを永続的な手動選択として扱うようにしました。

#### 修正

- 折りたたみ端末でBlackBerryモードが表示直後に消える問題を修正しました。
- 自動姿勢判定が手動で選択したBlackBerryモードを上書きする問題を修正しました。
- カバーディスプレイの入力モード切り替え後に、背面ボタンやトラックパッドの状態が残る問題を修正しました。

### English

#### New

- Added support for using a foldable device's cover display as an independent uinput trackpad.

#### Improved

- Manually opened BlackBerry mode is now treated as a persistent manual selection.

#### Fixes

- Fixed an issue where BlackBerry mode disappeared immediately after being shown on foldable devices.
- Fixed automatic posture detection overriding a manually selected BlackBerry mode.
- Fixed rear-button or trackpad state remaining active after switching the cover-display input mode.

## 1.5.2 — Fold8 laptop-mode reliability

### Fixed

- Fixed Fold8 laptop mode failing to start when One UI temporarily reports an incorrect fold posture or hinge orientation.
- Added the hardware hinge angle as a safe Fold8 posture fallback while preserving the WindowManager posture path.
- Kept the embedded privileged Binder alive and able to republish after the app process is restarted.
- Added an in-app update confirmation shown once after a successful version upgrade.

### Changed

- Automatic laptop-mode detection is now disabled by default on new installations. Existing user choices are preserved.

## 1.4.2 — Pixel compatibility fallback and keyboard repeat input

### Improved

- Added long-press and continuous repeat input to the laptop-mode keyboard while preserving multi-key input and modifier combinations.
- Restored the localized Android Auto settings layout and aligned its sections with the rest of Dextop settings.

### Fixed

- Fixed affected Pixel firmware failing to start with `No compatible mirror backend` when `VirtualDisplayConfig.Builder#setDisplayIdToMirror` is unavailable.
- Added an automatic per-firmware Pixel/AOSP compatibility fallback that restores the legacy freeform flags and mirror backend order. The native Pixel path is probed again after each firmware update.
- Fixed held laptop keyboard keys remaining pressed when laptop mode closes or the keyboard layout is rebuilt.

## 1.4.1 — Google Cast support and display controls

### New

- Added Google Cast output with Compatibility and Low latency modes.
- Added an in-overlay Cast receiver picker with active scanning, connection status, receiver selection, and disconnection controls.
- Added a persistent 180-degree rotation control that keeps the desktop, overlay controls, touch coordinates, and gesture directions aligned in both portrait and landscape.
- Added an animated Android Auto setup guide to Dextop Car Companion.

### Improved

- Kept Android Auto displays awake while Dextop Car Companion is running.
- Removed the unfinished physical mouse and keyboard routing controls from the Dextop overlay.

### Fixed

- Fixed Pixel/AOSP devices on Android 16 or later opening desktop apps without platform window decorations by using the system-managed desktop path. Older Android versions retain the compatibility freeform path.

## 1.4.0 — Dextop Car Companion and Android Auto support

### New

- Added Dextop Car Companion for launching a dedicated Dextop desktop on supported parked Android Auto displays.
- Added an independently owned car-display session with head-unit-sized virtual displays and direct touch forwarding without starting a phone-side Dextop session.
- Added a dedicated in-car overlay with close, reconnect, stop, and workspace controls. Saved workspaces use the same names, icons, save, and launch behavior as Dextop on the phone.
- Added a compatibility display mode that exposes the Auto virtual display on the phone and an experimental direct-transfer mode that keeps it hidden.
- Added an Auto settings category with automatic phone-mirror orientation matching and the experimental hidden-display switch.
- Added recovery integration for unexpectedly disconnected Dextop Car Companion sessions.
- Added standard, wide, tall, and Subaru-style parked DHU test profiles with touch and parked-state sensors enabled.
- Added a true-black AMOLED laptop keyboard theme with near-white key legends and restrained accent colors.

### Improved

- Added explicit Running (Auto), stopping, and cleanup states so the home screen reflects the native session owner and prevents another start until teardown is complete.
- Kept phone and car display ownership isolated across display creation, topology activation, recovery, and teardown.
- Extended display topology handling to recognize Auto-owned displays while excluding displays that have already been removed.
- Improved the three-finger menu layout on foldable cover displays by recalculating the available viewport and enabling scrolling after display changes.
- Improved live resolution changes so VirtualDisplay output follows the measured destination Surface while retaining the requested logical desktop size.
- Improved DPI-only updates by changing only display density instead of rebuilding size, rotation, and desktop policy.
- Improved navigation restoration with repeated vendor-SystemUI recovery passes before the accessibility service is detached.
- Added focused window-launch diagnostics for vendor desktop environments while filtering package names, components, accounts, URIs, and private filesystem paths from shared reports.
- Serialized and coalesced window diagnostics so launching a workspace cannot create an unbounded number of diagnostic workers.
- Updated the release APK workflow to produce an arm64-only package while preserving the normal internal version code.

### Fixed

- Fixed DPI fields in the main resolution editor and quick menu rejecting Samsung IME, hardware-keyboard, and composing-text input.
- Fixed DPI-only changes unnecessarily rebuilding Samsung DeX and causing its taskbar process to disappear.
- Fixed custom-resolution changes retaining the previous crop, offset, black bars, or small centered image when switching to a full-surface resolution.
- Fixed removed overlay displays remaining in saved topology snapshots and appearing as an ever-growing list of phantom monitors.
- Fixed stale overlay display requests surviving session shutdown by clearing and verifying the request twice before reuse.
- Fixed Dextop being unable to start again after a completed stop because a second accessibility teardown left the stopping latch active.
- Fixed navigation gestures and Samsung's bottom gesture state remaining disabled after pause, interruption, or session shutdown.
- Fixed the software cursor remaining hidden after an unsupported mirror backend failed and the user returned to a working backend.
- Fixed system decorations being applied outside the existing desktop-HOME launch fallback path.
- Fixed Auto-only sessions being mistaken for broken phone sessions and incorrectly showing the Android repair flow.
- Fixed ending Dextop Car Companion stopping an unrelated phone-side Dextop session.
- Fixed configuration changes in Dextop Car Companion being treated as an unexpected Android Auto disconnect.
- Fixed car-session teardown leaving its virtual display or overlay behind after a normal exit.
- Fixed unsupported portrait DHU video modes by using supported 1080p streams with cropped portrait content regions.

## 1.3.9 — Safer virtual pointer lifecycle

### Improved

- Removed the virtual mouse DPI and pointer-acceleration controls. Pointer movement now uses a fixed raw scale, and values saved by older releases are cleared automatically.
- Standardized the Mouse settings page with the same compact section heading used by the other settings categories.
- Kept Dextop's virtual mouse and touchpad out of physical input-device routing so stopping Dextop cannot disable or re-route a separately connected mouse or touchpad.

### Fixed

- Fixed stale virtual-pointer preferences causing input to stop working until the app was reinstalled.
- Fixed virtual pointer teardown being mixed with physical-device association handling.
- Reverted incompatible secure mirror flags that could prevent a Dextop session from starting. The existing secure-display option remains available.

## 1.3.8 — Stronger laptop keyboard and trackpad feedback

### Improved

- Strengthened haptic feedback for laptop-mode keyboard keys, modifiers, FN/Meta/Menu actions, and trackpad taps, clicks, and long presses.
- Added a heavy-click vibration effect with an amplitude-controlled fallback for devices that do not expose the predefined effect.
- Added the virtual touchpad input profile and compact pointer/scroll settings, with localized labels and a software-cursor fallback.
- Improved virtual pointer lifecycle so switching between cursor and tap input does not leave stale devices or duplicate pointer events.

## 1.3.7 — Fixed “The desktop HOME activity could not be launched” startup failure

### Fixed

- Fixed regional Samsung carrier model identifiers beginning with `SC`, `SCG`, or `SCV` being resolved to a generic vendor profile instead of Samsung DeX.
- Added prefix-based model matching so carrier suffixes do not prevent Samsung profile detection.
- Added a Samsung HOME-launch fallback that recreates the virtual display with system decorations when One UI rejects HOME on the first attempt.
- Persisted the successful decoration workaround per firmware fingerprint and automatically retries the original configuration after a firmware update.

## 1.3.6

### Improved

- Added complete support for all Galaxy Z Fold series devices running One UI 8.
- Expanded foldable model recognition for worldwide Fold8 and Fold8 Ultra variants, including Japanese carrier models.
- Improved foldable profile selection by matching normalized model prefixes when regional suffixes are present.
- Improved laptop-mode detection compatibility across regional Samsung firmware variants.
- Added localized keyboard theme settings and transparency controls for keyboard backgrounds, keys, and trackpads.
- Improved keyboard theme export to include theme settings and associated images in a ZIP archive.
- Added configurable trackpad label visibility, enabled by default.
- Improved keyboard theme previews and laptop-mode overlay behavior.

### Fixed

- Fixed incorrect Meta key rendering on devices where the Android icon font fallback produced unexpected characters.
- Fixed keyboard background transparency not applying to the area between keys.
- Fixed keyboard theme settings being accessible while Dextop is running.
- Fixed unlocalized foldable setup and keyboard theme strings.
- Fixed laptop-mode keyboard and display state handling across foldable posture and display transitions.

## 1.3.5

### Improved

- Added Samsung DeX taskbar auto-hide control to Samsung desktop settings using the launcher-owned `taskbar_show_hide_on_hold_enabled` key.
- Included the Samsung DeX taskbar setting in the Samsung desktop settings backup and restore flow.
- Added runtime resolution of display-topology transaction IDs instead of relying on hard-coded Binder numbers, improving compatibility with OEM Android framework forks.
- Kept an otherwise usable VirtualDisplay session running when the optional topology API is unavailable or rejected, while recording the skipped operation in the session log.

### Fixed

- Separated the generic Android desktop taskbar setting from Samsung DeX. The Display setting now always controls only `desktop_windowing_force_hide_taskbar`, regardless of the device manufacturer.
- Fixed topology routing on newer/vendor Android builds where stale transaction IDs could invoke a protected display-mode operation and fail with `android.permission.RESTRICT_DISPLAY_MODES`.
- Fixed topology activation failures aborting Dextop mirroring instead of degrading gracefully to mirroring without topology routing.

## 1.3.4

### Improved

- Added a complete laptop keyboard theme system with Standard, Crimson, Cloud Pop, and user-created themes.
- Added full-screen localized theme management with scrollable previews, real keyboard-and-trackpad overlay previews, add/edit/delete actions, and per-theme ZIP import and export.
- Added customizable keyboard backgrounds, key, border, text, and trackpad colors, image opacity, background blur, and corner styling.
- Stabilized laptop-mode transitions and theme-preview lifecycle so preview surfaces are separated from the setup overlay and are cleaned up when leaving the editor.
- Extended the privacy-filtered Dextop session report with runtime display geometry, including the target display ID, surface and window dimensions, logical target size, density, rotation, and configuration orientation.
- Added input diagnostics for touch routing, coordinate conversion, active input mode, pointer count, and the result of each sampled `injectInputEvent` call.
- Added orientation lifecycle diagnostics covering requested rotation, applied display rotation locks, surface recreation, mirror reattachment, and rebuild completion or failure.
- Added display lifecycle snapshots when displays are added, removed, changed, resized, or disconnected so foldable and DeX transitions can be compared from one report.

### Fixed

- Fixed diagnostic reports omitting the runtime evidence needed to distinguish display-geometry mismatches from rejected input injection on vendor Android builds.

## 1.3.3

### Improved

- Shift now updates the keyboard legends to their correct symbols (`!@#$%^&*()_+{}|:"<>?`).
- Ctrl, Shift, and Alt modifiers latch on press, allowing modifier chords and multi-touch shortcuts such as Ctrl+C and Ctrl+V.
- Added smoother laptop-mode and keyboard-settings transitions with fade, slide, and scale animations.
- The desktop surface is now black-backed during laptop-mode layout changes so the Android screen cannot show through transparent resize areas.
- Stabilized hinge-angle detection with filtering and debounce to prevent posture-triggered flicker and repeated virtual-display resizing.

### Fixed

- Fixed keyboard-settings navigation returning abruptly without the laptop transition animation.
- Fixed unstable laptop-mode toggling caused by noisy real-device hinge sensor readings near posture thresholds.

## 1.3.2

### Improved

- Laptop mode now registers its on-screen keyboard as an external physical keyboard while the mode is active.
- Gboard and other IMEs now follow Android's physical-keyboard preference instead of being forcibly disabled or hidden.
- The temporary keyboard device is removed when laptop mode, Dextop, or a paused session ends.

### Fixed

- Fixed laptop-mode key input being treated only as synthetic key events, which caused virtual keyboards to appear unexpectedly in text fields.

## 1.3.1

### New

- Added persistent display arrangements so monitor positions are restored when the same external-display configuration reconnects.
- Added laptop mode for foldable devices, with a US keyboard and trackpad in the lower screen area.
- Added laptop keyboard themes, function keys, shortcut labels, and direct access to the Dextop overlay from the trackpad.
- Added optional hinge-angle detection for automatically entering and leaving laptop mode.

### Improved

- Kept manual laptop mode available from the overlay on supported foldable devices even when automatic detection is disabled.
- Updated the README and wiki with multi-display, high-refresh-rate, taskbar, and foldable laptop-mode features.
- Marked foldable main/cover-display switching as incomplete while device-specific panel transitions continue to be improved.

### Fixed

- Fixed hinge-angle detection silently enabling the laptop mode preference.
- Fixed manual laptop mode toggles overwriting the automatic detection preference.

## 1.3.0

### Multi-display support has arrived

- Added an intuitive display topology editor for arranging Dextop and external displays to match their physical layout.
- Added multi-display pointer routing and automatic topology activation when Dextop starts or resumes.
- Added live display hot-plug and topology monitoring. The editor reloads when displays are reconfigured and closes automatically when arrangement is no longer available.
- Added localized display identification, reset, cancel, and apply controls with a responsive desktop and mobile layout.

### New

- Added an option to keep supported built-in displays at 120 Hz while Dextop is running with an external monitor.
- Added automatic 120 Hz reapplication after an external display is connected, reconfigured, or disconnected.
- Added stable display settings for topology participation and automatic desktop taskbar hiding.

### Improved

- Reorganized display settings into Display and Convenience sections using the existing settings design.
- Made display topology available by default and automatically refresh it in the background at every Dextop start and resume.
- Improved the topology canvas so it scales with the available dialog size on phones, foldables, and large desktop windows.
- Removed Dextop's custom cursor and cursor rendering logic for physical mice while preserving touch-panel cursor mode and monitor routing.
- Improved localization for display topology, Samsung desktop settings, warnings, and feature descriptions.

### Fixed

- Fixed stale display topology state after external displays are connected, removed, folded, unfolded, or reconfigured.
- Fixed the display arrangement action remaining available when fewer than two configurable displays exist.
- Fixed built-in display refresh-rate settings being restored when 120 Hz should remain active after external-display disconnection.

## 1.1.2

### Improved

- Improved the three-finger edge swipe sensitivity by triggering from the leading finger instead of the centroid and lowering the required swipe distance, especially in portrait when swiping down from the top edge.

## 1.1.1

### Improved

- Device reports now include a privacy-filtered log from only the most recent Dextop session.
- Reduced noisy Android and Flutter debug logging while retaining device implementation, capability, backend, routing, failure, and restoration events.

### Fixed

- Fixed the device-report email recipient, subject, or body being omitted by some email applications.
- Fixed the updated gesture guide not appearing after upgrading from a version earlier than 1.1.0.

## 1.1.0

### New

- Added a VirtualDisplay-based display mirroring backend.
- Added a display setting for selecting Automatic, VirtualDisplay, WindowManager, or SurfaceControl mirroring.
- Added GitHub Release update checks at app startup and from App information.
- Added update indicators to the Settings navigation icon and App information entry.
- Added device-aware physical mouse and keyboard routing controls for supported external-display configurations.
- Added external-display hot-plug detection and automatic input-route restoration when a display is disconnected.
- Added localized device compatibility reports with automatically collected device details and email submission.
- Added Firebase Analytics screen and desktop-start event collection when Firebase is configured.

### Improved

- VirtualDisplay is now the default mirroring method and the first method attempted in Automatic compatibility mode.
- Improved physical mouse and touchpad cursor switching. The Dextop cursor is hidden when physical mouse movement is detected and restored when the touchscreen is tapped.
- Improved multi-touch forwarding by preserving pointer IDs, action indices, timing, history, pressure, and gesture data for smoother scrolling and reliable pinch zoom.
- App version labels now use the version installed in the APK instead of a fixed value.
- Improved update-check status reporting by distinguishing unchecked, checking, up-to-date, update available, and retrieval failure states.
- Added detailed update-check events to the Flutter debug log.
- Removed the default Flutter ripple and highlight effects from the orientation and theme segmented controls.
- Expanded the customizable overlay control row from three to five columns when input-routing controls are available.
- Updated the gesture demonstration to introduce the mouse and keyboard routing controls.
- Improved the overlay gesture flow and made multi-touch the production default.

### Fixed

- Fixed transparent or invalid app entries being captured when saving the current app arrangement from the overlay.
- Fixed rejected input injection events being incorrectly treated as successful.
- Fixed stale touch streams that could leave one-finger input unresponsive after closing the overlay.
- Fixed portrait overlay gestures so the three-finger swipe opens the panel from the top edge.
