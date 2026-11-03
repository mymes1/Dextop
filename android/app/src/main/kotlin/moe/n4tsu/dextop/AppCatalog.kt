package moe.n4tsu.dextop

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import java.io.ByteArrayOutputStream

internal class AppCatalog(private val context: Context) {
    fun launchableApps(includeIcons: Boolean = true): List<Map<String, Any>> =
        launcherEntries()
            .map { (pkg, label, icon) ->
                mutableMapOf<String, Any>(
                    "label" to label,
                    "package" to pkg
                ).apply {
                    if (includeIcons) put("icon", iconBytes(icon))
                }
            }
            .sortedBy { (it["label"] as String).lowercase() }
            .toList()

    fun launchableAppIcons(): Map<String, ByteArray> =
        launcherEntries().associate { (pkg, _, icon) -> pkg to iconBytes(icon) }

    /** Packages of all launchable apps (same robust query as [launchableApps]). */
    fun launchablePackages(): Set<String> = launcherEntries().map { it.first }.toSet()

    /**
     * Every launchable app as (package, label, icon). MATCH_ALL disables result
     * filtering; some vendor firmware (Samsung One UI without DeX, e.g. SM-X110)
     * throws on it or returns nothing, so fall back to the plain query and
     * finally to enumerating installed applications via their launch intents.
     * Never throws and never returns an empty catalog while apps are installed.
     */
    private fun launcherEntries(): List<Triple<String, String, Drawable>> = runCatching {
        val manager = context.packageManager
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = runCatching { manager.queryIntentActivities(query, PackageManager.MATCH_ALL) }.getOrNull()
            ?.ifEmpty { runCatching { manager.queryIntentActivities(query, 0) }.getOrNull().orEmpty() }
            .orEmpty()
            .filter { it.activityInfo.packageName != context.packageName }
            .distinctBy { it.activityInfo.packageName }
        if (resolved.isNotEmpty()) {
            // One broken icon/label must not drop the whole catalog.
            return@runCatching resolved.mapNotNull { ri ->
                runCatching {
                    Triple(ri.activityInfo.packageName, ri.loadLabel(manager).toString(), ri.loadIcon(manager))
                }.getOrNull()
            }
        }
        runCatching { manager.getInstalledApplications(0) }.getOrNull().orEmpty()
            .filter { it.packageName != context.packageName }
            .mapNotNull { info ->
                runCatching {
                    if (manager.getLaunchIntentForPackage(info.packageName) == null) null else Triple(
                        info.packageName,
                        manager.getApplicationLabel(info).toString(),
                        manager.getApplicationIcon(info),
                    )
                }.getOrNull()
            }
            .distinctBy { it.first }
    }.getOrDefault(emptyList())

    private fun iconBytes(drawable: Drawable): ByteArray {
        val size = (64 * context.resources.displayMetrics.density).toInt().coerceAtLeast(64)
        val bitmap = if (drawable is BitmapDrawable && drawable.bitmap != null) {
            Bitmap.createScaledBitmap(drawable.bitmap, size, size, true)
        } else {
            Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { output ->
                val canvas = Canvas(output)
                drawable.setBounds(0, 0, canvas.width, canvas.height)
                drawable.draw(canvas)
            }
        }
        return ByteArrayOutputStream().use { stream ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 90, stream)
            stream.toByteArray()
        }
    }

    /**
     * Starts [packageName] on [displayId] as an independently positioned
     * window.
     *
     * The freeform launch hint used to be gated on the COMPAT_WINDOWING
     * profile, so Samsung profiles - including firmware without DeX, where no
     * platform desktop exists to choose a window mode - asked the framework
     * for a plain launch and got a fullscreen activity. The hint is now always
     * requested and only dropped when the platform rejects it.
     */
    fun launch(packageName: String, displayId: Int, bounds: Rect? = null) {
        require(displayId >= 0) { "Dextop is not running" }
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: error(NativeStrings.text("nativeSelectedAppCannotLaunch"))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId)
        val freeformRequested = applyFreeformWindowing(options)
        if (bounds != null && !bounds.isEmpty) options.launchBounds = bounds
        val first = runCatching { context.startActivity(intent, options.toBundle()) }
        if (first.isSuccess) return
        // A vendor ActivityTaskManager can reject an explicit windowing mode
        // for one activity. Launching without it still puts the task on the
        // right display, where the shell can turn it into a window.
        OperationLog.w(
            context,
            "AppLaunch",
            "freeform launch rejected package=$packageName display=$displayId freeform=$freeformRequested",
            first.exceptionOrNull()
        )
        val plain = ActivityOptions.makeBasic().setLaunchDisplayId(displayId)
        if (bounds != null && !bounds.isEmpty) plain.launchBounds = bounds
        val second = runCatching { context.startActivity(intent, plain.toBundle()) }
        second.exceptionOrNull()?.let { throw it }
    }

    /** True when the platform exposes the explicit freeform launch request. */
    private fun applyFreeformWindowing(options: ActivityOptions): Boolean = runCatching {
        ActivityOptions::class.java
            .getMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType)
            .invoke(options, DisplayWindowing.FREEFORM.frameworkValue)
        true
    }.onFailure {
        OperationLog.w(context, "AppLaunch", "setLaunchWindowingMode unavailable", it)
    }.getOrDefault(false)
}
