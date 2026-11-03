package moe.n4tsu.dextop

import android.content.ContentResolver
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings

internal data class StrategyAttempt(val strategy: String, val succeeded: Boolean, val detail: String)

/**
 * The result of making one display a freeform desktop.
 *
 * Reported to the session log and the diagnostic report so a device report
 * shows what the framework accepted instead of only what Dextop asked for.
 */
internal data class WindowingSetupReport(
    val displayId: Int,
    val reason: String,
    val before: DisplayWindowing,
    val after: DisplayWindowing,
    val steps: List<String>,
    val verified: Boolean,
    val perTaskOnly: Boolean
) {
    fun summary(): String = buildString {
        append("display=").append(displayId)
        append(" reason=").append(reason)
        append(" before=").append(before.dumpName)
        append(" after=").append(after.dumpName)
        append(" verified=").append(verified)
        if (perTaskOnly) append(" perTask=true")
        append(" steps=").append(steps.joinToString(","))
    }
}

/**
 * Applies the window-stack configuration for the current [DesktopEnvironment].
 *
 * Android 15 has no `wm set-display-windowing-mode`: that shell command was
 * added with Android 16, so every command Dextop used to force a display into
 * freeform silently failed on API 35. The display policy itself is reachable
 * on both releases through `IWindowManager#setWindowingMode(displayId, mode)`,
 * which is what the Android 16 command wraps. Dextop therefore sets the mode
 * through the binder call, reads it back, and only then falls back to the
 * shell command (Android 16+), the shell's per-task enforcement, or an
 * explicit "unsupported" report.
 */
internal class DesktopModeConfigurator(
    private val context: Context,
    private val resolver: ContentResolver,
    private val privilegedAccess: PrivilegedAccess,
    private val environment: DesktopEnvironment,
    private val sessionJournal: SessionJournal,
    private val windowing: DisplayWindowingBridge = DisplayWindowingBridge(
        { name, interfaceName -> privilegedAccess.service(name, interfaceName) },
        "DextopWindowing"
    )
) {
    private data class SavedGlobal(val key: String, val value: String?)
    private val savedGlobals = mutableListOf<SavedGlobal>()
    private var applied = false
    private var forcedFreeformGlobalsApplied = false
    /** Display modes Dextop changed, so they can be handed back on stop. */
    private val changedDisplays = linkedMapOf<Int, DisplayWindowing>()
    @Volatile private var lastReport: WindowingSetupReport? = null

    fun latestReport(): WindowingSetupReport? = lastReport

    fun applyForCurrentDevice(): List<StrategyAttempt> {
        if (applied) return emptyList()
        applied = true
        if (environment.platformManaged) {
            OperationLog.i(context, "DesktopMode", "platform-managed environment=${environment.id}")
            return listOf(StrategyAttempt("platform_managed", true, environment.id))
        }
        return writeGlobals(environment.temporaryGlobalSettings)
    }

    /**
     * Window-stack settings a Dextop-managed desktop needs, applied before the
     * display exists so its first tasks are already created as windows.
     *
     * A platform-managed environment (DeX) is left untouched here: its display
     * policy is verified after creation, and the fallback settings are applied
     * only if the platform did not deliver a freeform display.
     */
    fun prepareDesktopWindowing(): List<StrategyAttempt> {
        if (!environment.requiresFreeformDisplay) return emptyList()
        if (environment.platformManaged) {
            return listOf(
                StrategyAttempt(
                    "freeform_deferred",
                    true,
                    "platform desktop owns windowing environment=${environment.id}"
                )
            )
        }
        val attempts = writeGlobals(environment.freeformFallbackSettings)
        OperationLog.i(
            context,
            "DesktopMode",
            "freeform desktop prepared environment=${environment.id} " +
                attempts.joinToString { "${it.strategy}=${it.succeeded}" }
        )
        return attempts
    }

    /** Optional configuration: failure is isolated to this display and never aborts startup. */
    fun configureDisplay(displayId: Int, forceFreeform: Boolean = false): List<StrategyAttempt> {
        val windowingRequested = environment.configureFreeformWindowing || forceFreeform ||
            environment.requiresFreeformDisplay
        if (!windowingRequested) return emptyList()
        val attempts = mutableListOf<StrategyAttempt>()
        if (forceFreeform && !forcedFreeformGlobalsApplied) {
            forcedFreeformGlobalsApplied = true
            attempts += writeGlobals(AndroidDesktopOverrides.windowedDesktopValues)
        }
        // Display-level first: this is the only mechanism that exists on
        // Android 15, and it is what the Android 16 shell command wraps.
        val report = ensureDisplayWindowing(
            displayId = displayId,
            // With the system window manager selected there is no Dextop shell
            // to fall back to per-task placement, so the policy must not claim
            // that fallback exists.
            ownWindowManager = runCatching {
                moe.n4tsu.dextop.plasma.WindowManagerSelection.isPlasma(context)
            }.getOrDefault(false),
            reason = if (forceFreeform) "forced_freeform" else "display_configured"
        )
        attempts += StrategyAttempt(
            if (report.verified) "display_freeform" else "display_windowing_unverified",
            report.verified,
            report.summary()
        )
        if (Build.VERSION.SDK_INT >= 35 && !environment.platformManaged) {
            val engagement = privilegedAccess.execute(
                "wm", "set-display-engagement-mode", "-d", displayId.toString(), "3"
            )
            attempts += StrategyAttempt(
                "display_engagement",
                engagement.succeeded,
                engagement.error.ifBlank { engagement.output }
            )
        }
        // The shell commands only exist from Android 16. Attempting them on
        // API 35 produced an "Unknown command" failure that looked like a
        // framework refusal in device reports.
        if (!report.verified && Build.VERSION.SDK_INT >= 36) {
            val windowingStrategies = if (forceFreeform) {
                // Electron DEX applies this synchronously, before the display is
                // published back to the app. Keep its exact command order: on
                // several Samsung releases `cmd window` works while the older
                // activity_task command either exits successfully without taking
                // effect or races DeX's initial task creation.
                listOf("cmd_window", "wm")
            } else environment.windowingStrategies
            for (strategy in windowingStrategies) {
                val args = when (strategy) {
                    "cmd_window" -> arrayOf(
                        "cmd", "window", "set-display-windowing-mode", "-d",
                        displayId.toString(), "5"
                    )
                    "wm" -> arrayOf("wm", "set-display-windowing-mode", "-d", displayId.toString(), "5")
                    "activity_task_manager" -> arrayOf(
                        "cmd", "activity_task", "set-display-windowing-mode", displayId.toString(), "5"
                    )
                    else -> continue
                }
                val result = privilegedAccess.execute(*args)
                val attempt = StrategyAttempt(strategy, result.succeeded, result.error.ifBlank { result.output })
                attempts += attempt
                OperationLog.i(
                    context,
                    "DesktopMode",
                    "windowing display=$displayId strategy=$strategy success=${attempt.succeeded} detail=${attempt.detail}"
                )
                if (result.succeeded) break
            }
            // Pixel's projected-display controller initializes asynchronously and
            // can overwrite the first windowing-mode request while registering the
            // new overlay. Reassert it after WMShell has observed the display.
            if (attempts.any { it.strategy != "display_engagement" && it.succeeded }) {
                listOf(250L, 750L).forEach { delay ->
                    Handler(Looper.getMainLooper()).postDelayed({
                        privilegedAccess.execute(
                            "wm", "set-display-windowing-mode", "-d", displayId.toString(), "5"
                        )
                    }, delay)
                }
            }
        }
        return attempts
    }

    /**
     * Makes [displayId] a freeform desktop and verifies the outcome.
     *
     * The verification is what distinguishes real framework support from a
     * request: `getWindowingMode` returns the mode the display policy really
     * uses, including "fullscreen" when the firmware refuses freeform.
     */
    fun ensureDisplayWindowing(
        displayId: Int,
        ownWindowManager: Boolean,
        reason: String
    ): WindowingSetupReport {
        if (displayId < 0) {
            return WindowingSetupReport(
                displayId, reason, DisplayWindowing.UNKNOWN, DisplayWindowing.UNKNOWN,
                listOf("invalid_display"), false, false
            ).also { lastReport = it }
        }
        val before = windowing.readMode(displayId) ?: DisplayWindowing.UNKNOWN
        val steps = mutableListOf<String>()
        var after = before
        var verified = before.isFreeform
        var perTaskOnly = false
        val required = environment.freeformFallbackSettings
        val live = required.keys.associateWith { Settings.Global.getString(resolver, it) }
        val plan = DesktopWindowingPolicy.plan(
            observed = before,
            requiredSettings = required,
            liveSettings = live,
            perDisplayApi = windowing.isSupported(),
            shellCommand = Build.VERSION.SDK_INT >= 36,
            ownWindowManager = ownWindowManager
        )
        for (step in plan) {
            if (verified) break
            when (step) {
                DesktopWindowingStep.KEEP_PLATFORM_FREEFORM -> {
                    steps += "keep_platform_freeform"
                    verified = true
                }
                DesktopWindowingStep.ENABLE_FREEFORM_SUPPORT,
                DesktopWindowingStep.FORCE_RESIZABLE_ACTIVITIES,
                DesktopWindowingStep.ALLOW_NON_RESIZABLE_MULTI_WINDOW -> {
                    val key = step.settingsKey ?: continue
                    val value = required[key] ?: continue
                    val attempt = writeGlobal(key, value)
                    steps += "setting:$key=${attempt.succeeded}"
                }
                DesktopWindowingStep.SET_DISPLAY_WINDOWING -> {
                    changedDisplays.putIfAbsent(displayId, before)
                    val wrote = windowing.writeMode(displayId, DisplayWindowing.FREEFORM)
                    after = windowing.readMode(displayId) ?: DisplayWindowing.UNKNOWN
                    verified = after.isFreeform
                    steps += "set_display_windowing written=$wrote read=${after.dumpName}"
                }
                DesktopWindowingStep.SHELL_COMMAND_WINDOWING_MODE -> {
                    val result = privilegedAccess.execute(
                        "cmd", "window", "set-display-windowing-mode", "-d",
                        displayId.toString(), "5"
                    )
                    after = windowing.readMode(displayId) ?: DisplayWindowing.UNKNOWN
                    verified = after.isFreeform
                    steps += "shell_command succeeded=${result.succeeded} read=${after.dumpName}"
                }
                DesktopWindowingStep.ENFORCE_PER_TASK -> {
                    perTaskOnly = true
                    steps += "per_task_enforced"
                }
                DesktopWindowingStep.UNSUPPORTED -> {
                    steps += "unsupported"
                }
            }
        }
        val report = WindowingSetupReport(
            displayId = displayId,
            reason = reason,
            before = before,
            after = after,
            steps = steps,
            verified = verified,
            perTaskOnly = perTaskOnly
        )
        lastReport = report
        OperationLog.i(context, "Windowing", report.summary())
        if (!verified) {
            OperationLog.w(
                context,
                "Windowing",
                "display=$displayId is not a freeform desktop after configuration " +
                    "(before=${before.dumpName} after=${after.dumpName}); " +
                    "applications are kept windowed per task where the firmware allows it",
                null
            )
        }
        return report
    }

    /**
     * Hands the displays Dextop changed back to the firmware default. Overlay
     * displays drop their settings when they are removed; this covers sessions
     * that end while the display still exists.
     */
    fun restoreDisplayWindowing() {
        if (changedDisplays.isEmpty()) return
        changedDisplays.forEach { (displayId, previous) ->
            val restored = windowing.writeMode(displayId, DisplayWindowing.UNDEFINED)
            OperationLog.i(
                context,
                "Windowing",
                "display=$displayId windowing override cleared success=$restored was=${previous.dumpName}"
            )
        }
        changedDisplays.clear()
    }

    fun restore() {
        restoreDisplayWindowing()
        sessionJournal.restoreSystemSettings()
        OperationLog.i(context, "DesktopMode", "restored settings count=${savedGlobals.size}")
        savedGlobals.clear()
        applied = false
        forcedFreeformGlobalsApplied = false
        lastReport = null
    }

    // region settings

    private fun writeGlobals(settings: Map<String, String?>): List<StrategyAttempt> =
        settings.map { (key, value) -> writeGlobal(key, value) }

    /**
     * Writes one window-stack global and verifies it.
     *
     * `Settings.Global` accepts a write only with WRITE_SECURE_SETTINGS. Dextop
     * declares it and holds it through the privileged runtime; when that is not
     * connected the privileged `settings put` is still tried, and a rejected
     * write is reported instead of being treated as applied.
     */
    private fun writeGlobal(key: String, value: String?): StrategyAttempt {
        val previous = runCatching { Settings.Global.getString(resolver, key) }.getOrNull()
        if (previous == value) {
            return StrategyAttempt("global:$key", true, "already ${value ?: "<unset>"}")
        }
        savedGlobals += SavedGlobal(key, previous)
        runCatching { sessionJournal.rememberGlobal(key, previous) }
        val direct = runCatching {
            check(Settings.Global.putString(resolver, key, value)) { "setting rejected" }
            true
        }.getOrDefault(false)
        val writeSucceeded = direct || runCatching {
            if (value == null) {
                privilegedAccess.execute("settings", "delete", "global", key).succeeded
            } else {
                privilegedAccess.execute("settings", "put", "global", key, value).succeeded
            }
        }.getOrDefault(false)
        val verified = runCatching { Settings.Global.getString(resolver, key) }.getOrNull() == value
        val attempt = StrategyAttempt(
            "global:$key",
            verified,
            "$previous -> ${value ?: "<deleted>"} direct=$direct verified=$verified"
        )
        if (!verified) {
            OperationLog.w(
                context,
                "DesktopMode",
                "window-stack setting not applied key=$key write=$writeSucceeded detail=${attempt.detail}",
                null
            )
        }
        return attempt
    }
    // endregion
}
