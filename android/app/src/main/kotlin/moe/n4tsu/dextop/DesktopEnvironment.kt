package moe.n4tsu.dextop

import android.os.Build

internal data class DeviceIdentity(
    val manufacturer: String,
    val brand: String,
    val model: String,
    val device: String,
    val product: String,
    val fingerprint: String,
    val sdk: Int,
    /**
     * Whether this firmware ships a platform desktop that manages windowing
     * itself (Samsung DeX). `null` means "not probed": every rule keeps the
     * assumption it was written for, so tests and callers that only have a
     * [Build] snapshot do not change behaviour. Production always probes, see
     * [DeviceIdentity.current] with a Context.
     */
    val platformDesktopAvailable: Boolean? = null
) {
    companion object {
        fun current() = DeviceIdentity(
            Build.MANUFACTURER, Build.BRAND, Build.MODEL, Build.DEVICE,
            Build.PRODUCT, Build.FINGERPRINT, Build.VERSION.SDK_INT
        )

        fun current(context: android.content.Context): DeviceIdentity =
            current().copy(
                platformDesktopAvailable = DesktopStackProbe.current(context).platformDesktop
            )
    }
}

internal data class DeviceMatch(
    val manufacturers: Set<String> = emptySet(),
    val models: Set<String> = emptySet(),
    /** Match regional model identifiers whose suffix varies by carrier. */
    val modelPrefixes: Set<String> = emptySet(),
    val devices: Set<String> = emptySet(),
    val fingerprintPrefixes: Set<String> = emptySet(),
    val minSdk: Int = 29,
    val maxSdk: Int = Int.MAX_VALUE
) {
    fun matches(identity: DeviceIdentity): Boolean {
        fun String.normalized() = lowercase().trim()
        if (identity.sdk !in minSdk..maxSdk) return false
        if (manufacturers.isNotEmpty() && manufacturers.none { it.normalized() == identity.manufacturer.normalized() }) return false
        if (models.isNotEmpty() && models.none { it.normalized() == identity.model.normalized() }) return false
        if (modelPrefixes.isNotEmpty() && modelPrefixes.none {
                identity.model.normalized().startsWith(it.normalized())
            }) return false
        if (devices.isNotEmpty() && devices.none { it.normalized() == identity.device.normalized() }) return false
        if (fingerprintPrefixes.isNotEmpty() && fingerprintPrefixes.none {
                identity.fingerprint.normalized().startsWith(it)
            }) return false
        return true
    }
}

internal data class DesktopEnvironment(
    val id: String,
    val displayName: String,
    val platformManaged: Boolean,
    val temporaryGlobalSettings: Map<String, String?>,
    val configureFreeformWindowing: Boolean,
    val startupMode: DesktopStartupMode,
    val displayChromeMode: DisplayChromeMode,
    val supportsLaunchBounds: Boolean = true,
    /** Recreate a device-resolution session when a foldable host changes size. */
    val autoResizeWithHostDisplay: Boolean = false,
    /**
     * Window manager written at first launch while the user has not chosen one
     * (a WindowManagerSelection value). Vendors whose own desktop is unusable on
     * Dextop's display start on the Dextop window manager instead.
     */
    val defaultWindowManager: String = "dextop_book",
    /** Ordered, device-local strategies. Never change the global order for one model. */
    val displayCreationStrategies: List<String> = listOf("overlay_settings"),
    val mirrorStrategies: List<String> = listOf("virtual_display", "window_manager", "surface_control"),
    val windowingStrategies: List<String> = listOf("wm", "activity_task_manager"),
    /**
     * Create the recording VirtualDisplay without FLAG_TRUSTED. With
     * force_desktop_mode_on_external_displays enabled, WindowManager treats
     * every trusted virtual display as decorated and starts SECONDARY_HOME on
     * it; the recording display then has its own content and ContentRecorder
     * pauses the mirror. Falls back to the trusted flags if rejected.
     */
    val untrustedMirrorDisplay: Boolean = false,
    /**
     * Force Plasma v2 application displays to fullscreen. With desktop mode
     * forced, a public secondary display defaults to freeform and apps open
     * as phone-sized windows that are cropped inside the Plasma window.
     */
    val fullscreenApplicationDisplays: Boolean = false,
    /**
     * Keep the Plasma v2 overlay unfocusable unless one of its own text fields
     * is open. A focusable full-screen overlay takes focus on every touch, so
     * the application display loses focus and its soft keyboard closes.
     */
    val passivePlasmaShellFocus: Boolean = false,
    /**
     * Apply the maximum-refresh-rate option to Plasma v2 application displays
     * too. Without a requested rate they are created as 60 Hz virtual
     * displays, so apps inside a 120 Hz Plasma window still render at 60 Hz.
     */
    val maxRateApplicationDisplays: Boolean = false,
    /**
     * Draw the Plasma v2 shell as TYPE_APPLICATION_OVERLAY. Before Android 16,
     * AccessibilityManagerService issues no overlay token for overlay displays
     * (Display.TYPE_OVERLAY), so accessibility overlays there fail with
     * BadTokenException and Plasma never appears.
     */
    val plasmaApplicationOverlay: Boolean = false,
    /**
     * Recreate the recording VirtualDisplay after a live logical resize.
     * Before Android 16 ContentRecorder does not recompute its crop and scale
     * when only the mirrored display changes size, so a rotation keeps the
     * old portrait transform and shows the desktop as a narrow strip.
     */
    val rebuildMirrorAfterLiveResize: Boolean = false,
    /**
     * Create Plasma v2 application displays without FLAG_PUBLIC. With desktop
     * mode forced, every public secondary display gets the launcher's
     * SECONDARY_HOME behind its app; a private display does not. Only the
     * owner (the privileged shell) may launch onto it, which Plasma v2
     * already falls back to.
     */
    val privateApplicationDisplays: Boolean = false,
    /**
     * For Plasma v2, render the phone desktop on an app-owned public
     * VirtualDisplay drawn straight into Dextop's Surface instead of an
     * overlay display plus a recording mirror. Before Android 16 an overlay
     * display gets no accessibility overlay token, and the application
     * overlay used instead is hidden by any HIDE_NON_SYSTEM_OVERLAY_WINDOWS
     * window (Settings, permission dialogs); it also shows the overlay
     * preview window on the phone.
     */
    val directPlasmaDisplay: Boolean = false,
    /**
     * Dextop's own window manager positions and resizes every application it
     * shows, which requires the Dextop display itself to be a freeform
     * desktop. Firmware with a platform desktop (DeX) already reports
     * freeform; firmware without one launches every activity fullscreen
     * unless Dextop switches the display, so the session verifies the
     * display's windowing mode and escalates when it is not freeform.
     */
    val requiresFreeformDisplay: Boolean = false,
    /**
     * Window-stack settings applied only when the display turns out not to be
     * a freeform desktop. Journaled like every other temporary global and
     * restored when the session ends.
     */
    val freeformFallbackSettings: Map<String, String?> = emptyMap()
)

internal enum class DesktopStartupMode {
    SYSTEM_MANAGED,
    COMPAT_WINDOWING,
    OEM_MANAGED
}

internal enum class DisplayChromeMode {
    HIDDEN,
    VISIBLE
}

internal object AndroidDesktopOverrides {
    val modernKeys = listOf(
        "override_desktop_experience_features",
        "override_desktop_mode_features",
        "enable_non_resizable_multi_window",
        "force_desktop_mode_on_secondary_displays",
        "force_desktop_mode_on_external_displays",
        "force_resizable_activities",
        "enable_freeform_support"
    )

    val compatibilityValues = linkedMapOf(
        "force_resizable_activities" to "1",
        "enable_freeform_support" to "1",
        "force_desktop_mode_on_external_displays" to "1"
    )

    /**
     * Android's window-stack switches for a display Dextop windows itself.
     *
     * `force_desktop_mode_on_external_displays` is deliberately absent: it
     * changes the default policy of *every* public secondary display,
     * including the private application displays the compositing shell draws
     * inside its own windows, where apps must keep filling their display.
     * The Dextop display itself is switched explicitly with
     * `IWindowManager#setWindowingMode` instead (see [DisplayWindowingBridge]).
     */
    val windowedDesktopValues = linkedMapOf(
        "enable_freeform_support" to "1",
        "force_resizable_activities" to "1",
        "enable_non_resizable_multi_window" to "1"
    )
}

internal data class DesktopEnvironmentRule(
    val id: String,
    val match: DeviceMatch,
    val create: (DeviceIdentity) -> DesktopEnvironment
)

/**
 * Device-specific changes belong in a narrow rule. A model/fingerprint override
 * cannot affect another device. Runtime capability probes remain authoritative.
 */
internal object DesktopEnvironmentRegistry {
    /** Resolves with the runtime probe, which is what production always wants. */
    fun current(context: android.content.Context): DesktopEnvironment =
        resolve(DeviceIdentity.current(context))

    fun resolve(identity: DeviceIdentity): DesktopEnvironment {
        val rule = DeviceProfiles.rules.firstOrNull { it.match.matches(identity) }
        return rule?.create?.invoke(identity) ?: aospFreeform(identity)
    }

    internal fun vendorFreeform(identity: DeviceIdentity, id: String, stringKey: String) =
        aospFreeform(identity).copy(id = id, displayName = NativeStrings.text(stringKey))

    /** Pre-Android-16 Pixel/AOSP profile retained for firmware compatibility. */
    internal fun legacyPixelAosp(identity: DeviceIdentity): DesktopEnvironment = DesktopEnvironment(
        "pixel_aosp_compat", NativeStrings.text("nativePixelAndroidDesktop"),
        false,
        AndroidDesktopOverrides.compatibilityValues,
        configureFreeformWindowing = true,
        startupMode = DesktopStartupMode.COMPAT_WINDOWING,
        displayChromeMode = DisplayChromeMode.VISIBLE,
        // The missing recording-VirtualDisplay API is the reason this profile
        // is selected. Prefer the older framework mirror paths accordingly.
        mirrorStrategies = listOf("window_manager", "surface_control", "virtual_display")
    )

    internal fun samsungDex(autoResizeWithHostDisplay: Boolean = false) = DesktopEnvironment(
        "samsung_dex", "Samsung DeX", true, emptyMap(), false,
        DesktopStartupMode.OEM_MANAGED, DisplayChromeMode.HIDDEN,
        autoResizeWithHostDisplay = autoResizeWithHostDisplay,
        // DeX normally owns the freeform policy of its displays. The session
        // still verifies that this display really is freeform: on firmware
        // that keeps the DeX service but does not put virtual displays into
        // desktop mode (Galaxy Tab A9, SM-X110), nothing else would.
        requiresFreeformDisplay = true,
        freeformFallbackSettings = AndroidDesktopOverrides.windowedDesktopValues
    )

    /**
     * Samsung firmware without a DeX desktop.
     *
     * Galaxy Tab A9 (SM-X110) is the reference device: One UI 7 / Android 15,
     * no DeX service, no `SecondaryLauncher`, and no per-display windowing
     * policy, so the old manufacturer-wide Samsung rule configured nothing
     * and every activity filled the Dextop display. This profile is the AOSP
     * freeform profile with Samsung's chrome and launcher expectations
     * removed, plus the windowing settings Dextop has to apply itself.
     */
    internal fun samsungWindowedDesktop(identity: DeviceIdentity): DesktopEnvironment =
        aospFreeform(identity).copy(
            id = "samsung_windowed",
            displayName = NativeStrings.text("nativeAndroidDesktopFreeform"),
            platformManaged = false,
            // The AOSP compat values force desktop mode on *every* public
            // secondary display, which would also turn the compositing
            // shell's own application displays into floating windows. Only
            // the switches that make freeform possible are applied here; the
            // Dextop display itself is switched per display.
            temporaryGlobalSettings = AndroidDesktopOverrides.windowedDesktopValues,
            // Android 15 has no `wm set-display-windowing-mode`; the display is
            // switched through IWindowManager#setWindowingMode, which
            // DesktopModeConfigurator uses before these legacy commands.
            configureFreeformWindowing = true,
            startupMode = DesktopStartupMode.COMPAT_WINDOWING,
            // System decorations give each freeform task the platform caption
            // (drag bar, close, maximise), which is what makes a window
            // resizable by touch without a Dextop-drawn frame. There is no
            // DeX taskbar on this firmware to conflict with.
            displayChromeMode = DisplayChromeMode.VISIBLE,
            defaultWindowManager = "dextop_book",
            requiresFreeformDisplay = true,
            freeformFallbackSettings = AndroidDesktopOverrides.windowedDesktopValues
        )

    internal fun aospFreeform(identity: DeviceIdentity): DesktopEnvironment {
        val platformNative = identity.sdk >= 36
        val settings: Map<String, String?> = if (platformNative) {
            // Let the current Android desktop stack choose its own windowing
            // configuration for the newer, purely cosmetic toggles.
            // SessionJournal preserves every displaced value.
            //
            // force_resizable_activities and force_desktop_mode_on_external_displays
            // are the exception: Dextop's default mirror strategy targets a
            // DisplayManager *virtual* display (see DesktopEnvironment.mirrorStrategies),
            // not a genuine connected external display, and ActivityTaskManager still
            // silently rejects launching non-resizable phone activities onto it on
            // SDK 36+ unless these two are forced. Leaving them null let them sit at
            // their OS default of disabled, so the app drawer closed without ever
            // launching anything. See #17.
            AndroidDesktopOverrides.modernKeys.associateWithTo(linkedMapOf()) { key ->
                if (key == "force_resizable_activities" ||
                    key == "force_desktop_mode_on_external_displays"
                ) {
                    "1"
                } else {
                    null
                }
            }
        } else {
            AndroidDesktopOverrides.compatibilityValues
        }
        return DesktopEnvironment(
            "android_freeform", NativeStrings.text("nativeAndroidDesktopFreeform"),
            false,
            settings,
            configureFreeformWindowing = !platformNative,
            startupMode = if (platformNative) {
                DesktopStartupMode.SYSTEM_MANAGED
            } else {
                DesktopStartupMode.COMPAT_WINDOWING
            },
            displayChromeMode = DisplayChromeMode.VISIBLE,
            // Verification is always safe: a display the platform already
            // runs in freeform is left alone.
            requiresFreeformDisplay = true,
            freeformFallbackSettings = if (platformNative) {
                emptyMap()
            } else {
                AndroidDesktopOverrides.compatibilityValues
            }
        )
    }
}
