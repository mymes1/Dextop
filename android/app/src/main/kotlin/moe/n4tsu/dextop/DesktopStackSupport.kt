package moe.n4tsu.dextop

import android.content.Context
import android.os.Build
import android.provider.Settings

/**
 * Which window stack the firmware itself provides for Dextop's display.
 *
 * Samsung ships two different window stacks behind the same manufacturer
 * string. DeX-capable firmware registers the `desktopmode` system service,
 * ships a DeX launcher, and puts secondary displays into freeform desktop
 * mode on its own. Firmware without DeX (Galaxy Tab A9 / SM-X110, One UI 7
 * on Android 15 for example) has no desktop service at all: nothing in the
 * platform gives a secondary display a windowing policy, so every activity
 * Dextop starts there is laid out fullscreen unless Dextop configures
 * freeform windowing itself.
 *
 * The lookup is a capability probe rather than a model list, because the
 * same model can gain or lose the service across One UI releases and
 * because new models must not need a new rule. [DesktopStackOverride] lets a
 * device report force either answer while validating the patch.
 */
internal data class DesktopStackSupport(
    val platformDesktopService: Boolean,
    val freeformFeatureDeclared: Boolean,
    val override: DesktopStackOverride = DesktopStackOverride.AUTO,
    val sdk: Int = Build.VERSION.SDK_INT
) {
    /** True when an OEM desktop owns windowing; Dextop then leaves it alone. */
    val platformDesktop: Boolean
        get() = when (override) {
            DesktopStackOverride.PLATFORM -> true
            DesktopStackOverride.WINDOWED -> false
            DesktopStackOverride.AUTO -> platformDesktopService
        }

    /**
     * `IWindowManager#getWindowingMode`/`#setWindowingMode` are present from
     * Android 12 and are what Dextop uses to put one display into freeform on
     * Android 15, where the `wm set-display-windowing-mode` shell command does
     * not exist yet. Android 16 adds the shell command; the binder call keeps
     * working, so both are used with the call first.
     */
    val perDisplayWindowingApi: Boolean get() = sdk >= 31

    /** `wm set-display-windowing-mode` was added with the Android 16 shell. */
    val perDisplayWindowingShellCommand: Boolean get() = sdk >= 36

    fun report(): String = buildString {
        append("platformDesktop=").append(platformDesktop)
        append(" desktopService=").append(platformDesktopService)
        append(" freeformFeature=").append(freeformFeatureDeclared)
        append(" override=").append(override.name.lowercase())
        append(" perDisplayApi=").append(perDisplayWindowingApi)
        append(" displayCommand=").append(perDisplayWindowingShellCommand)
    }
}

internal enum class DesktopStackOverride {
    AUTO,
    PLATFORM,
    WINDOWED;

    companion object {
        fun parse(value: String?): DesktopStackOverride = when (value?.trim()?.lowercase()) {
            "platform", "dex", "system" -> PLATFORM
            "windowed", "freeform", "dextop" -> WINDOWED
            else -> AUTO
        }
    }
}

internal object DesktopStackProbe {
    /**
     * Global override, settable without rebuilding Dextop:
     * `adb shell settings put global dextop_desktop_stack windowed|platform`.
     * `auto` (or an absent value) restores capability detection.
     */
    const val OVERRIDE_KEY = "dextop_desktop_stack"

    /** Samsung's DeX 3.0 system service; absent on firmware without DeX. */
    const val DESKTOP_MODE_SERVICE = "desktopmode"

    const val FREEFORM_FEATURE = "android.software.freeform_window_management"

    fun current(context: Context): DesktopStackSupport = DesktopStackSupport(
        platformDesktopService = desktopModeServiceAvailable(context),
        freeformFeatureDeclared = freeformFeatureDeclared(context),
        override = override(context)
    )

    fun desktopModeServiceAvailable(context: Context): Boolean = runCatching {
        context.getSystemService(DESKTOP_MODE_SERVICE) != null
    }.getOrDefault(false)

    fun freeformFeatureDeclared(context: Context): Boolean = runCatching {
        context.packageManager.hasSystemFeature(FREEFORM_FEATURE)
    }.getOrDefault(false)

    fun override(context: Context): DesktopStackOverride = DesktopStackOverride.parse(
        runCatching {
            Settings.Global.getString(context.contentResolver, OVERRIDE_KEY)
        }.getOrNull()
    )
}
