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
    fun launchableApps(includeIcons: Boolean = true): List<Map<String, Any>> {
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return queryLauncherActivities(query)
            .asSequence()
            .filter { it.activityInfo.packageName != context.packageName }
            .distinctBy { it.activityInfo.packageName }
            .map {
                mutableMapOf<String, Any>(
                    "label" to it.loadLabel(context.packageManager).toString(),
                    "package" to it.activityInfo.packageName
                ).apply {
                    if (includeIcons) put("icon", iconBytes(it.loadIcon(context.packageManager)))
                }
            }
            .sortedBy { (it["label"] as String).lowercase() }
            .toList()
    }

    fun launchableAppIcons(): Map<String, ByteArray> {
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return queryLauncherActivities(query)
            .asSequence()
            .filter { it.activityInfo.packageName != context.packageName }
            .distinctBy { it.activityInfo.packageName }
            .associate { it.activityInfo.packageName to iconBytes(it.loadIcon(context.packageManager)) }
    }

    /**
     * Launcher activities. Some vendor firmware (Samsung One UI without DeX,
     * e.g. SM-X110) filters the MATCH_ALL query down to nothing, so fall back
     * to the plain query and never return an empty catalog.
     */
    private fun queryLauncherActivities(query: Intent): List<android.content.pm.ResolveInfo> {
        val manager = context.packageManager
        return manager.queryIntentActivities(query, PackageManager.MATCH_ALL)
            .ifEmpty { manager.queryIntentActivities(query, 0) }
    }

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
