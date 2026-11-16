package com.munimlocation

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import com.facebook.react.HeadlessJsTaskService
import com.margelo.nitro.NitroModules
import com.margelo.nitro.munimlocation.Location
import com.margelo.nitro.munimlocation.LocationBatchSource
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * Event channels to JavaScript: a typed location channel (Nitro structs,
 * one call per batch) and a JSON channel for everything else.
 *
 * Background-origin events take the first available path:
 * 1. a live JavaScript listener in an active React instance;
 * 2. the Headless JS task (`MunimLocationHeadlessTask`) when the process has
 *    no active React instance (started by a receiver or after the app was
 *    swiped away while the foreground service kept running);
 * 3. a persisted queue, replayed by `registerBackgroundHandler()`.
 */
object LocationEvents {
  private const val TAG = "MunimLocation"

  @Volatile
  private var listener: ((String, String) -> Unit)? = null

  @Volatile
  private var locationListener: ((LocationBatchSource, Double, Array<Location>) -> Unit)? = null

  fun setListener(newListener: (String, String) -> Unit) {
    listener = newListener
  }

  fun removeListener() {
    listener = null
  }

  fun setLocationListener(newListener: (LocationBatchSource, Double, Array<Location>) -> Unit) {
    locationListener = newListener
  }

  fun removeLocationListener() {
    locationListener = null
  }

  private fun reactInstanceActive(): Boolean {
    val reactContext = NitroModules.applicationContext ?: return false
    return try {
      reactContext.hasActiveReactInstance()
    } catch (_: Throwable) {
      false
    }
  }

  /** True when a JavaScript listener lives in an active React instance. */
  fun hasActiveListener(): Boolean = listener != null && reactInstanceActive()

  fun emit(name: String, body: Map<String, Any?>) {
    if (!hasActiveListener()) return
    val payload = LocationJson.toJson(body).toString()
    try {
      listener?.invoke(name, payload)
    } catch (error: Throwable) {
      Log.w(TAG, "Unable to emit $name", error)
    }
  }

  private fun structs(locations: List<android.location.Location>): Array<Location> =
    Array(locations.size) { LocationJson.location(locations[it]) }

  /** Fixes for a `watchPosition()` subscription. Dropped when JavaScript is gone. */
  fun emitLocations(watchId: Int, locations: List<android.location.Location>) {
    if (locations.isEmpty() || !reactInstanceActive()) return
    val typed = locationListener
    if (typed != null) {
      try {
        typed(LocationBatchSource.WATCH, watchId.toDouble(), structs(locations))
      } catch (error: Throwable) {
        Log.w(TAG, "Unable to deliver locations for watch $watchId", error)
      }
      return
    }
    emit("location", mapOf("watchId" to watchId) + LocationJson.locations(locations))
  }

  /** Background or significant-change fixes (see the class comment). */
  fun deliverBackgroundLocations(
    context: Context,
    source: LocationBatchSource,
    locations: List<android.location.Location>
  ) {
    if (locations.isEmpty()) return
    val typed = locationListener
    if (typed != null && reactInstanceActive()) {
      try {
        typed(source, 0.0, structs(locations))
        return
      } catch (error: Throwable) {
        Log.w(TAG, "Live delivery of $source locations failed; falling back", error)
      }
    }
    val name = if (source == LocationBatchSource.SIGNIFICANTCHANGE) "significantLocationChange" else "backgroundLocation"
    deliverBackground(context, name, LocationJson.locations(locations))
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

  fun appendPending(context: Context, name: String, payload: String) = PendingEventQueue.append(context, name, payload)

  fun pendingJson(context: Context): String = PendingEventQueue.json(context)

  fun pendingCount(context: Context): Int = PendingEventQueue.count(context)

  fun clearPending(context: Context) = PendingEventQueue.clear(context)
}

/**
 * Append-only queue of `{ name, payload, timestamp }` JSON lines.
 *
 * Appending writes one line instead of reading and rewriting the whole
 * queue; the file is trimmed to [MAX_EVENTS] only once it holds 1.5x that
 * many lines, so the rewrite cost is amortised.
 */
internal object PendingEventQueue {
  private const val TAG = "MunimLocation"
  private const val MAX_EVENTS = 500
  private val lock = Any()
  private var cachedCount: Int? = null

  private fun directory(context: Context): File =
    File(context.applicationContext.filesDir, "munim-location").apply { mkdirs() }

  private fun file(context: Context) = File(directory(context), "pending-events.ndjson")

  private fun legacyFile(context: Context) = File(directory(context), "pending-events.json")

  fun append(context: Context, name: String, payload: String) {
    val line = JSONObject()
      .put("name", name)
      .put("payload", payload)
      .put("timestamp", System.currentTimeMillis().toDouble())
      .toString()
    synchronized(lock) {
      migrateLegacy(context)
      val file = file(context)
      try {
        // Start on a fresh line if a crash left a torn last line.
        val prefix = if (file.length() > 0 && lastByte(file) != '\n'.code) "\n" else ""
        FileOutputStream(file, true).use { it.write("$prefix$line\n".toByteArray()) }
      } catch (error: Throwable) {
        Log.w(TAG, "Unable to persist $name", error)
        return
      }
      val count = (cachedCount ?: (lines(context).size - 1)) + 1
      cachedCount = count
      if (count > MAX_EVENTS + MAX_EVENTS / 2) write(context, lines(context).takeLast(MAX_EVENTS))
    }
  }

  fun json(context: Context): String = synchronized(lock) {
    migrateLegacy(context)
    lines(context).takeLast(MAX_EVENTS).joinToString(",", "[", "]")
  }

  fun count(context: Context): Int = synchronized(lock) {
    migrateLegacy(context)
    minOf(lines(context).size, MAX_EVENTS)
  }

  fun clear(context: Context) {
    synchronized(lock) {
      file(context).delete()
      legacyFile(context).delete()
      cachedCount = 0
    }
  }

  private fun lastByte(file: File): Int = RandomAccessFile(file, "r").use {
    it.seek(it.length() - 1)
    it.read()
  }

  /** Complete, valid JSON lines (a crash mid-append can leave a torn last line). */
  private fun lines(context: Context): List<String> {
    val file = file(context)
    val result = if (!file.exists()) {
      emptyList()
    } else {
      try {
        file.readLines().filter { line ->
          line.isNotBlank() && try {
            JSONObject(line)
            true
          } catch (_: Throwable) {
            false
          }
        }
      } catch (_: Throwable) {
        emptyList()
      }
    }
    cachedCount = result.size
    return result
  }

  private fun write(context: Context, lines: List<String>) {
    try {
      val target = file(context)
      val temporary = File(target.parentFile, "${target.name}.tmp")
      temporary.writeText(if (lines.isEmpty()) "" else lines.joinToString("\n", postfix = "\n"))
      if (!temporary.renameTo(target)) {
        target.delete()
        temporary.renameTo(target)
      }
      cachedCount = lines.size
    } catch (error: Throwable) {
      Log.w(TAG, "Unable to trim the pending queue", error)
    }
  }

  /** Moves events from the pre-0.4 JSON-array file into the line queue. */
  private fun migrateLegacy(context: Context) {
    val legacy = legacyFile(context)
    if (!legacy.exists()) return
    try {
      val array = JSONArray(legacy.readText())
      val migrated = (0 until array.length()).mapNotNull { array.optJSONObject(it)?.toString() }
      if (migrated.isNotEmpty()) write(context, (migrated + lines(context)).takeLast(MAX_EVENTS))
    } catch (_: Throwable) {
    }
    legacy.delete()
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
