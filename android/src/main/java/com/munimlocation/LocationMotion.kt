package com.munimlocation

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity
import com.margelo.nitro.munimlocation.MotionActivity
import com.margelo.nitro.munimlocation.MotionActivityType
import com.margelo.nitro.munimlocation.MotionConfidence
import com.margelo.nitro.munimlocation.MotionPermission
import java.util.concurrent.ConcurrentHashMap

/**
 * Motion activity from Google Play services' Activity Recognition
 * transition API (the battery-efficient one: the system reports confident
 * transitions only). One registration is shared by every consumer:
 * JavaScript (`motionActivity` events), watches and the foreground service
 * (`pauseWhenStationary`), and PendingIntent background updates. The
 * registration is a PendingIntent, so it outlives the process;
 * [handleIntent] drops it once nobody needs it any more.
 */
@SuppressLint("MissingPermission")
object LocationMotion {
  private const val TAG = "MunimLocationMotion"
  const val ACTION_ACTIVITY = "com.munimlocation.ACTION_ACTIVITY"
  private const val REQUEST_CODE = 4
  private const val ASKED = "asked.motion"

  private val observers = ConcurrentHashMap<String, (MotionActivity) -> Unit>()

  @Volatile
  private var registered = false

  /** Latest transition this process has seen. */
  @Volatile
  var last: MotionActivity? = null
    private set

  private val permissionName: String
    get() = if (Build.VERSION.SDK_INT >= 29) {
      Manifest.permission.ACTIVITY_RECOGNITION
    } else {
      "com.google.android.gms.permission.ACTIVITY_RECOGNITION"
    }

  fun available(context: Context): Boolean = LocationEngine.playServicesAvailable(context)

  fun granted(context: Context): Boolean = LocationPermissions.granted(context, permissionName)

  fun permission(context: Context): MotionPermission = when {
    !available(context) -> MotionPermission.UNAVAILABLE
    granted(context) -> MotionPermission.GRANTED
    // Below Android 10 the permission is install-time: not declared = denied.
    Build.VERSION.SDK_INT < 29 -> MotionPermission.DENIED
    LocationStore.getBoolean(context, ASKED) -> MotionPermission.DENIED
    else -> MotionPermission.NOTDETERMINED
  }

  fun request(context: Context, resolve: (MotionPermission) -> Unit, reject: (Throwable) -> Unit) {
    val current = permission(context)
    if (current == MotionPermission.UNAVAILABLE || current == MotionPermission.GRANTED || Build.VERSION.SDK_INT < 29) {
      resolve(current)
      return
    }
    if (!LocationPermissions.declaredInManifest(context, permissionName)) {
      reject(
        LocationException(
          "E_MOTION_PERMISSION_DENIED",
          "Declare $permissionName in AndroidManifest.xml (the Expo config plugin adds it with isAndroidActivityRecognitionEnabled)"
        )
      )
      return
    }
    LocationPermissions.request(arrayOf(permissionName), {
      LocationStore.setBoolean(context, ASKED, true)
      resolve(permission(context))
    }, reject)
  }

  /**
   * Registers a consumer. When the registration was already live the
   * current activity is replayed, so the consumer starts from the present
   * state; a fresh registration reports on the next transition.
   */
  fun addObserver(context: Context, key: String, block: (MotionActivity) -> Unit) {
    val wasRegistered = registered
    observers[key] = block
    ensureRegistered(context)
    if (wasRegistered) last?.let(block)
  }

  fun removeObserver(context: Context, key: String) {
    if (observers.remove(key) != null) releaseIfUnused(context)
  }

  /** Background updates in PendingIntent mode need transitions with no in-process observer. */
  private fun backgroundNeedsMotion(context: Context): Boolean =
    LocationStore.getBoolean(context, LocationStore.BACKGROUND_RUNNING) &&
      LocationStore.backgroundOptions(context)?.optBoolean("pauseWhenStationary", false) == true

  fun ensureRegistered(context: Context) {
    if (registered) return
    if (!available(context) || !granted(context)) return
    val transitions = listOf(
      DetectedActivity.STILL,
      DetectedActivity.WALKING,
      DetectedActivity.RUNNING,
      DetectedActivity.ON_BICYCLE,
      DetectedActivity.IN_VEHICLE
    ).map {
      ActivityTransition.Builder()
        .setActivityType(it)
        .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
        .build()
    }
    try {
      registered = true
      ActivityRecognition.getClient(context)
        .requestActivityTransitionUpdates(ActivityTransitionRequest(transitions), pendingIntent(context))
        .addOnFailureListener { error ->
          registered = false
          LocationEvents.emit(
            "motionActivityError",
            mapOf("code" to "E_LOCATION_ERROR", "message" to (error.message ?: "Activity recognition refused"))
          )
        }
    } catch (error: Throwable) {
      registered = false
      Log.w(TAG, "Activity transition updates refused", error)
    }
  }

  fun releaseIfUnused(context: Context) {
    if (observers.isNotEmpty() || backgroundNeedsMotion(context)) return
    registered = false
    try {
      ActivityRecognition.getClient(context).removeActivityTransitionUpdates(pendingIntent(context))
    } catch (_: Throwable) {
    }
  }

  private fun pendingIntent(context: Context) =
    LocationEngine.pendingIntent(context, ACTION_ACTIVITY, REQUEST_CODE)

  /** ACTION_ACTIVITY broadcasts (see MunimLocationReceiver). */
  fun handleIntent(context: Context, intent: Intent) {
    if (!ActivityTransitionResult.hasResult(intent)) return
    val result = ActivityTransitionResult.extractResult(intent) ?: return
    // A registration that survived the process means it is still live.
    registered = true
    val event = result.transitionEvents.lastOrNull {
      it.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER
    } ?: return
    val ageMs = (SystemClock.elapsedRealtimeNanos() - event.elapsedRealTimeNanos) / 1_000_000.0
    val activity = MotionActivity(
      type = type(event.activityType),
      confidence = MotionConfidence.HIGH,
      timestamp = System.currentTimeMillis() - ageMs
    )
    last = activity
    observers.values.forEach {
      try {
        it(activity)
      } catch (error: Throwable) {
        Log.w(TAG, "Motion observer failed", error)
      }
    }
    LocationEngine.onBackgroundMotion(context, activity)
    releaseIfUnused(context)
  }

  private fun type(activityType: Int): MotionActivityType = when (activityType) {
    DetectedActivity.STILL -> MotionActivityType.STILL
    DetectedActivity.WALKING, DetectedActivity.ON_FOOT -> MotionActivityType.WALKING
    DetectedActivity.RUNNING -> MotionActivityType.RUNNING
    DetectedActivity.ON_BICYCLE -> MotionActivityType.CYCLING
    DetectedActivity.IN_VEHICLE -> MotionActivityType.AUTOMOTIVE
    else -> MotionActivityType.UNKNOWN
  }

  fun map(activity: MotionActivity): Map<String, Any?> = mapOf(
    "type" to when (activity.type) {
      MotionActivityType.STILL -> "still"
      MotionActivityType.WALKING -> "walking"
      MotionActivityType.RUNNING -> "running"
      MotionActivityType.CYCLING -> "cycling"
      MotionActivityType.AUTOMOTIVE -> "automotive"
      MotionActivityType.UNKNOWN -> "unknown"
    },
    "confidence" to when (activity.confidence) {
      MotionConfidence.LOW -> "low"
      MotionConfidence.MEDIUM -> "medium"
      MotionConfidence.HIGH -> "high"
    },
    "timestamp" to activity.timestamp
  )

  /**
   * Pause decision shared by watches, the foreground service, and
   * PendingIntent background updates: a confident `still` pauses, any
   * confident movement resumes. Returns the new paused state.
   */
  fun nextPaused(paused: Boolean, activity: MotionActivity): Boolean {
    if (activity.confidence == MotionConfidence.LOW) return paused
    return when (activity.type) {
      MotionActivityType.STILL -> true
      MotionActivityType.UNKNOWN -> paused
      else -> false
    }
  }
}

/** Pauses a location session while the device is still (`pauseWhenStationary`). */
class StationaryGate(
  private val context: Context,
  private val key: String,
  private val onChange: (paused: Boolean) -> Unit
) {
  @Volatile
  var paused = false
    private set

  fun start() {
    if (!LocationMotion.available(context)) return
    if (!LocationMotion.granted(context)) {
      LocationEvents.emit(
        "motionActivityError",
        mapOf(
          "code" to "E_MOTION_PERMISSION_DENIED",
          "message" to "pauseWhenStationary needs the ACTIVITY_RECOGNITION permission; updates are not paused",
          "source" to key
        )
      )
      return
    }
    LocationMotion.addObserver(context, key) { handle(it) }
  }

  fun stop() {
    LocationMotion.removeObserver(context, key)
    paused = false
  }

  @Synchronized
  private fun handle(activity: MotionActivity) {
    val next = LocationMotion.nextPaused(paused, activity)
    if (next == paused) return
    paused = next
    LocationEngine.handler.post { onChange(next) }
  }
}

/**
 * Buffers fixes and hands them on in batches: once `size` fixes are
 * buffered, or `intervalMs` after the first buffered fix. With neither set
 * every delivery passes straight through.
 */
class LocationBatcher(
  size: Double,
  intervalMs: Double,
  private val handler: Handler,
  private val output: (List<android.location.Location>) -> Unit
) {
  private val size = if (size > 1) size.toInt() else 0
  private val interval = if (intervalMs > 0) intervalMs.toLong() else 0L
  private val buffer = ArrayList<android.location.Location>()
  private var scheduled = false
  private val timeout = Runnable {
    synchronized(this) { scheduled = false }
    flush()
  }

  val enabled: Boolean get() = size > 0 || interval > 0

  fun add(locations: List<android.location.Location>) {
    if (locations.isEmpty()) return
    if (!enabled) {
      output(locations)
      return
    }
    val ready = synchronized(this) {
      buffer.addAll(locations)
      if (size > 0 && buffer.size >= size) {
        takeLocked()
      } else {
        if (interval > 0 && !scheduled) {
          scheduled = true
          handler.postDelayed(timeout, interval)
        }
        null
      }
    }
    ready?.let(output)
  }

  fun flush() {
    synchronized(this) { takeLocked() }?.let(output)
  }

  fun discard() {
    synchronized(this) {
      handler.removeCallbacks(timeout)
      scheduled = false
      buffer.clear()
    }
  }

  private fun takeLocked(): List<android.location.Location>? {
    handler.removeCallbacks(timeout)
    scheduled = false
    if (buffer.isEmpty()) return null
    val batch = ArrayList(buffer)
    buffer.clear()
    return batch
  }
}
