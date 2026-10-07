package com.munimlocation

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import com.facebook.react.HeadlessJsTaskService
import com.margelo.nitro.NitroModules
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Single event channel to JavaScript.
 *
 * Background-origin events (`persist = true`) take the first available path:
 * 1. a live JavaScript listener in an active React instance;
 * 2. the Headless JS task (`MunimLocationHeadlessTask`) when the process has
 *    no active React instance (started by a receiver or after the app was
 *    swiped away while the foreground service kept running);
 * 3. a persisted queue, replayed by `registerBackgroundHandler()`.
 */
object LocationEvents {
  private const val TAG = "MunimLocation"
  private const val MAX_PENDING = 500
  private val lock = Any()

  @Volatile
  private var listener: ((String, String) -> Unit)? = null

  fun setListener(newListener: (String, String) -> Unit) {
    listener = newListener
  }

  fun removeListener() {
    listener = null
  }

  /** True when a JavaScript listener lives in an active React instance. */
  fun hasActiveListener(): Boolean {
    if (listener == null) return false
    val reactContext = NitroModules.applicationContext ?: return false
    return try {
      reactContext.hasActiveReactInstance()
    } catch (_: Throwable) {
      false
    }
  }

  fun emit(name: String, body: Map<String, Any?>) {
    if (!hasActiveListener()) return
    val payload = LocationJson.toJson(body).toString()
    try {
      listener?.invoke(name, payload)
    } catch (error: Throwable) {
      Log.w(TAG, "Unable to emit $name", error)
    }
  }

  /** Delivers a background-origin event (see the class comment). */
  fun deliverBackground(context: Context, name: String, body: Map<String, Any?>) {
    val payload = LocationJson.toJson(body).toString()
    if (hasActiveListener()) {
      try {
        listener?.invoke(name, payload)
        return
      } catch (error: Throwable) {
        Log.w(TAG, "Live delivery of $name failed; falling back", error)
      }
    }
    if (!startHeadlessTask(context, name, payload)) {
      appendPending(context, name, payload)
    }
  }

  private fun startHeadlessTask(context: Context, name: String, payload: String): Boolean {
    return try {
      val extras = Bundle().apply {
        putString("name", name)
        putString("payload", payload)
        putDouble("timestamp", System.currentTimeMillis().toDouble())
      }
      val intent = Intent(context, MunimLocationHeadlessTaskService::class.java).putExtras(extras)
      val component = context.applicationContext.startService(intent)
      if (component != null) {
        HeadlessJsTaskService.acquireWakeLockNow(context.applicationContext)
        true
      } else {
        false
      }
    } catch (error: Throwable) {
      // Android 8+ refuses background service starts outside an allowlist window.
      Log.w(TAG, "Headless task for $name not started; persisting", error)
      false
    }
  }

  // ---------- Pending queue ----------

  private fun pendingFile(context: Context): File {
    val directory = File(context.applicationContext.filesDir, "munim-location")
    directory.mkdirs()
    return File(directory, "pending-events.json")
  }

  private fun readPending(context: Context): JSONArray {
    return try {
      val file = pendingFile(context)
      if (file.exists()) JSONArray(file.readText()) else JSONArray()
    } catch (_: Throwable) {
      JSONArray()
    }
  }

  fun appendPending(context: Context, name: String, payload: String) {
    synchronized(lock) {
      val events = readPending(context)
      events.put(
        JSONObject()
          .put("name", name)
          .put("payload", payload)
          .put("timestamp", System.currentTimeMillis().toDouble())
      )
      val trimmed = if (events.length() > MAX_PENDING) {
        JSONArray().also { array ->
          for (index in events.length() - MAX_PENDING until events.length()) {
            array.put(events.get(index))
          }
        }
      } else {
        events
      }
      try {
        pendingFile(context).writeText(trimmed.toString())
      } catch (error: Throwable) {
        Log.w(TAG, "Unable to persist $name", error)
      }
    }
  }

  fun pendingJson(context: Context): String = synchronized(lock) { readPending(context).toString() }

  fun pendingCount(context: Context): Int = synchronized(lock) { readPending(context).length() }

  fun clearPending(context: Context) {
    synchronized(lock) {
      pendingFile(context).delete()
    }
  }
}

/** Runs the JavaScript task registered by `registerBackgroundHandler()`. */
class MunimLocationHeadlessTaskService : HeadlessJsTaskService() {
  override fun getTaskConfig(intent: Intent?): com.facebook.react.jstasks.HeadlessJsTaskConfig? {
    val extras = intent?.extras ?: return null
    return com.facebook.react.jstasks.HeadlessJsTaskConfig(
      HEADLESS_TASK_NAME,
      com.facebook.react.bridge.Arguments.fromBundle(extras),
      60_000,
      true
    )
  }

  companion object {
    const val HEADLESS_TASK_NAME = "MunimLocationHeadlessTask"
  }
}
