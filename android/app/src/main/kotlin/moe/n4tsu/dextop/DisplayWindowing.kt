package moe.n4tsu.dextop

import android.graphics.Rect
import android.util.Log

/**
 * The framework windowing mode of a display or a task.
 *
 * Values are `android.app.WindowConfiguration` constants. `UNKNOWN` is
 * Dextop's own addition for "the platform would not tell us", which is the
 * normal answer when the privileged runtime is not connected.
 */
internal enum class DisplayWindowing(val frameworkValue: Int, val dumpName: String) {
    UNDEFINED(0, "undefined"),
    FULLSCREEN(1, "fullscreen"),
    PINNED(2, "pinned"),
    FREEFORM(5, "freeform"),
    MULTI_WINDOW(6, "multi-window"),
    UNKNOWN(-1, "unknown");

    val isFreeform: Boolean get() = this == FREEFORM

    companion object {
        fun fromFramework(value: Int): DisplayWindowing =
            entries.firstOrNull { it.frameworkValue == value } ?: UNKNOWN

        fun fromDump(name: String): DisplayWindowing {
            val normalized = name.trim().lowercase()
            return entries.firstOrNull { it.dumpName == normalized } ?: UNKNOWN
        }
    }
}

/**
 * Task bounds as plain integers, so the parser stays pure JVM code and is
 * unit tested without the stubbed `android.graphics.Rect`.
 */
internal data class TaskWindowBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    fun toRect(): Rect = Rect(left, top, right, bottom)
    fun flattenToShortString(): String = "[$left,$top][$right,$bottom]"
}

/** One task read from `dumpsys activity activities`. */
internal data class DumpedTask(
    val taskId: Int,
    val type: String,
    val packageName: String,
    val windowingMode: DisplayWindowing,
    val bounds: TaskWindowBounds?
)

/**
 * Reads task windowing modes out of `dumpsys activity activities`.
 *
 * A task header looks like
 * `* Task{9d3ffa9 #41 type=standard A=10234:com.android.chrome U=0 visible=true
 * mode=freeform translucent=false sz=1}` and the following lines carry
 * `bounds=[[l,t][r,b]]`. The dump is only a fallback for the binder-based
 * [TaskBridge]; it is also what a device report contains, so the parser is
 * pure and unit tested.
 */
internal object TaskWindowingDump {
    private val displayHeader = Regex("""Display:\s*mDisplayId=(\d+)""")
    private val taskHeader = Regex("""Task\{[0-9a-fA-F]+ #(\d+)""")
    private val modeToken = Regex("""\bmode=([a-z-]+)""")
    private val typeToken = Regex("""\btype=([a-z-]+)""")
    private val packageToken = Regex("""(?:A=\d+:|I=)([A-Za-z0-9._]+)""")
    private val boundsToken = Regex("""bounds=\[\[(-?\d+),(-?\d+)]\[(-?\d+),(-?\d+)]]""")

    /** Tasks of the `Display: mDisplayId=<displayId>` section of the dump, in dump order. */
    fun tasks(displayId: Int, dump: String): List<DumpedTask> {
        val result = mutableListOf<DumpedTask>()
        var inDisplay = false
        var pending: DumpedTask? = null
        dump.lineSequence().forEach { line ->
            val header = displayHeader.find(line)
            if (header != null) {
                pending?.takeIf { it.bounds != null }?.let(result::add)
                pending = null
                inDisplay = header.groupValues[1].toIntOrNull() == displayId
                return@forEach
            }
            if (!inDisplay) return@forEach
            val task = taskHeader.find(line)
            if (task != null) {
                pending?.let(result::add)
                pending = DumpedTask(
                    taskId = task.groupValues[1].toIntOrNull() ?: -1,
                    type = typeToken.find(line)?.groupValues?.getOrNull(1).orEmpty(),
                    packageName = packageToken.find(line)?.groupValues?.getOrNull(1).orEmpty(),
                    windowingMode = modeToken.find(line)
                        ?.let { DisplayWindowing.fromDump(it.groupValues[1]) }
                        ?: DisplayWindowing.UNKNOWN,
                    bounds = null
                )
                return@forEach
            }
            val bounds = boundsToken.find(line) ?: return@forEach
            val values = bounds.groupValues.drop(1).mapNotNull(String::toIntOrNull)
            if (values.size != 4) return@forEach
            pending?.let { entry ->
                pending = entry.copy(
                    bounds = TaskWindowBounds(values[0], values[1], values[2], values[3])
                )
            }
        }
        pending?.let(result::add)
        return result
    }

    /**
     * The mode of the first standard application task, or the first task that
     * is not HOME. HOME tasks stay fullscreen on a freeform display, so they
     * never decide the answer.
     */
    fun applicationMode(tasks: List<DumpedTask>): DisplayWindowing =
        tasks.firstOrNull { it.type != "home" && it.packageName.isNotEmpty() }?.windowingMode
            ?: tasks.firstOrNull()?.windowingMode
            ?: DisplayWindowing.UNKNOWN
}

/**
 * `IWindowManager#getWindowingMode` / `#setWindowingMode` for one display.
 *
 * Both methods exist from Android 12 (see `IWindowManager.aidl`); they are
 * guarded by `android.permission.INTERNAL_SYSTEM_WINDOW`, which the shell UID
 * holds and therefore Dextop's embedded ADB runtime and Shizuku both satisfy.
 * The Android 16 `wm set-display-windowing-mode` shell command is only a
 * wrapper around exactly these two calls, which is why Dextop can apply the
 * same policy on Android 15.
 */
internal class DisplayWindowingBridge(
    private val serviceProvider: (String, String) -> Any,
    private val tag: String = "DextopWindowing"
) {
    private val interfaceName = "android.view.IWindowManager"

    @Volatile private var methodPresent: Boolean? = null

    fun isSupported(): Boolean {
        methodPresent?.let { return it }
        val present = runCatching {
            val type = Class.forName(interfaceName)
            type.getMethod("getWindowingMode", Int::class.javaPrimitiveType)
            type.getMethod(
                "setWindowingMode",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )
            true
        }.getOrDefault(false)
        methodPresent = present
        return present
    }

    fun readMode(displayId: Int): DisplayWindowing? {
        if (!isSupported()) return null
        return runCatching {
            val type = Class.forName(interfaceName)
            val value = type.getMethod("getWindowingMode", Int::class.javaPrimitiveType)
                .invoke(service(), displayId) as Int
            DisplayWindowing.fromFramework(value)
        }.onFailure {
            Log.w(tag, "getWindowingMode display=$displayId failed: ${it.cause?.message ?: it.message}")
        }.getOrNull()
    }

    fun writeMode(displayId: Int, mode: DisplayWindowing): Boolean {
        if (!isSupported()) return false
        return runCatching {
            val type = Class.forName(interfaceName)
            type.getMethod(
                "setWindowingMode",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            ).invoke(service(), displayId, mode.frameworkValue)
            true
        }.onFailure {
            Log.w(
                tag,
                "setWindowingMode display=$displayId mode=${mode.dumpName} failed: " +
                    (it.cause?.message ?: it.message)
            )
        }.getOrDefault(false)
    }

    private fun service(): Any = serviceProvider("window", interfaceName)
}

/** One ordered step that makes a display a freeform desktop. */
internal enum class DesktopWindowingStep {
    /** The platform already manages freeform windows on this display. */
    KEEP_PLATFORM_FREEFORM,

    /** Allow freeform tasks at all (`enable_freeform_support`). */
    ENABLE_FREEFORM_SUPPORT,

    /** Let any activity be resized (`force_resizable_activities`). */
    FORCE_RESIZABLE_ACTIVITIES,

    /** Let activities that declare themselves non-resizable use multi-window. */
    ALLOW_NON_RESIZABLE_MULTI_WINDOW,

    /** `IWindowManager#setWindowingMode(displayId, FREEFORM)`. */
    SET_DISPLAY_WINDOWING,

    /** `wm set-display-windowing-mode -d <id> 5` (Android 16 and later). */
    SHELL_COMMAND_WINDOWING_MODE,

    /** The shell keeps every app a freeform task of its own. */
    ENFORCE_PER_TASK,

    /** Nothing can be done on this firmware; report it instead of pretending. */
    UNSUPPORTED;

    val settingsKey: String?
        get() = when (this) {
            ENABLE_FREEFORM_SUPPORT -> "enable_freeform_support"
            FORCE_RESIZABLE_ACTIVITIES -> "force_resizable_activities"
            ALLOW_NON_RESIZABLE_MULTI_WINDOW -> "enable_non_resizable_multi_window"
            else -> null
        }
}

/**
 * Decides how a display becomes a freeform desktop. Pure, so the decision is
 * unit tested without a device; the platform calls live in
 * [DesktopModeConfigurator].
 */
internal object DesktopWindowingPolicy {
    /**
     * [requiredSettings] is the profile's window-stack intent, not the live
     * state: a step is planned whenever the profile wants a value the live
     * settings do not already have. The executor skips values already present
     * and never writes a key the profile did not ask for, so profiles that
     * deliberately leave a switch alone keep doing so.
     */
    fun plan(
        observed: DisplayWindowing,
        requiredSettings: Map<String, String?>,
        liveSettings: Map<String, String?>,
        perDisplayApi: Boolean,
        shellCommand: Boolean,
        ownWindowManager: Boolean
    ): List<DesktopWindowingStep> {
        // A display the platform already runs in freeform needs nothing; this
        // is the DeX path and must stay untouched.
        if (observed.isFreeform) return listOf(DesktopWindowingStep.KEEP_PLATFORM_FREEFORM)
        val steps = mutableListOf<DesktopWindowingStep>()
        listOf(
            DesktopWindowingStep.ENABLE_FREEFORM_SUPPORT,
            DesktopWindowingStep.FORCE_RESIZABLE_ACTIVITIES,
            DesktopWindowingStep.ALLOW_NON_RESIZABLE_MULTI_WINDOW
        ).forEach { step ->
            val key = step.settingsKey ?: return@forEach
            val wanted = requiredSettings[key] ?: return@forEach
            if (liveSettings[key] != wanted) steps += step
        }
        // Candidates are attempted in order; the first one that verifies wins.
        if (perDisplayApi) steps += DesktopWindowingStep.SET_DISPLAY_WINDOWING
        if (shellCommand) steps += DesktopWindowingStep.SHELL_COMMAND_WINDOWING_MODE
        if (ownWindowManager) steps += DesktopWindowingStep.ENFORCE_PER_TASK
        if (steps.none {
                it in setOf(
                    DesktopWindowingStep.SET_DISPLAY_WINDOWING,
                    DesktopWindowingStep.SHELL_COMMAND_WINDOWING_MODE,
                    DesktopWindowingStep.ENFORCE_PER_TASK
                )
            }
        ) {
            steps += DesktopWindowingStep.UNSUPPORTED
        }
        return steps
    }
}
