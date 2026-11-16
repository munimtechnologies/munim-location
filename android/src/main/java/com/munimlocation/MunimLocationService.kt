package com.munimlocation

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationRequestCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationResult
import com.margelo.nitro.munimlocation.LocationBatchSource
import org.json.JSONObject

/**
 * `location`-type foreground service for continuous background updates.
 * Restarted by the system (START_STICKY) and, when `restartOnBoot` is set,
 * by MunimLocationBootReceiver.
 */
@SuppressLint("MissingPermission")
class MunimLocationService : Service() {
  private var callback: LocationCallback? = null
  private var listener: LocationListenerCompat? = null
  private var batcher: LocationBatcher? = null
  private var gate: StationaryGate? = null

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onCreate() {
    super.onCreate()
    current = this
  }

  /** Hands buffered fixes over now (flushLocations / stop). */
  fun flush() {
    batcher?.flush()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    LocationEngine.attach(this)
    val options = LocationStore.backgroundOptions(this)
    if (options == null || !LocationStore.getBoolean(this, LocationStore.BACKGROUND_RUNNING)) {
      stopSelf()
      return START_NOT_STICKY
    }
    try {
      val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
      ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(options), type)
    } catch (error: Throwable) {
      // Android 14+ throws when FOREGROUND_SERVICE_LOCATION or the location
      // permission is missing; Android 12+ when started from the background.
      Log.w(TAG, "startForeground refused", error)
      LocationStore.setBoolean(this, LocationStore.BACKGROUND_RUNNING, false)
      LocationEvents.deliverBackground(
        this,
        "locationError",
        mapOf("code" to "E_FOREGROUND_SERVICE", "message" to (error.message ?: "startForeground refused"), "source" to "background")
      )
      stopSelf()
      return START_NOT_STICKY
    }
    startUpdates(options)
    return START_STICKY
  }

  private fun startUpdates(options: JSONObject) {
    stopUpdates()
    if (!LocationPermissions.hasForeground(this)) {
      stopSelf()
      return
    }
    val context = this
    val locationBatcher = LocationBatcher(
      options.optDouble("batchSize", 0.0),
      options.optDouble("batchIntervalMs", 0.0),
      LocationEngine.handler
    ) { LocationEvents.deliverBackgroundLocations(context, LocationBatchSource.BACKGROUND, it) }
    batcher = locationBatcher
    val distance = DistanceFilter(options.optDouble("distanceFilter", 0.0))
    if (LocationEngine.playServicesAvailable(this)) {
      val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
          if (gate?.paused == true) return
          locationBatcher.add(distance.filter(result.locations))
        }
      }
      callback = locationCallback
      LocationEngine.fused(this).requestLocationUpdates(
        LocationEngine.backgroundRequest(options),
        LocationEngine.executor,
        locationCallback
      )
      if (options.optBoolean("pauseWhenStationary", false)) {
        val stationaryGate = StationaryGate(this, "background") { paused ->
          if (callback !== locationCallback) return@StationaryGate
          LocationStore.setBoolean(context, LocationStore.BACKGROUND_PAUSED, paused)
          if (paused) locationBatcher.flush()
          val request = if (paused) {
            LocationEngine.stationaryRequest(options.optDouble("intervalMs", 10_000.0).toLong())
          } else {
            LocationEngine.backgroundRequest(options)
          }
          LocationEngine.fused(context).requestLocationUpdates(request, LocationEngine.executor, locationCallback)
          LocationEvents.deliverBackground(
            context,
            if (paused) "locationUpdatesPaused" else "locationUpdatesResumed",
            mapOf("source" to "motion")
          )
        }
        gate = stationaryGate
        stationaryGate.start()
      }
    } else {
      val manager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
      val provider = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        .firstOrNull { manager.isProviderEnabled(it) } ?: return
      val compatListener = LocationListenerCompat { location -> locationBatcher.add(distance.filter(listOf(location))) }
      listener = compatListener
      val request = LocationRequestCompat.Builder(options.optDouble("intervalMs", 10_000.0).toLong()).build()
      LocationManagerCompat.requestLocationUpdates(manager, provider, request, LocationEngine.executor, compatListener)
    }
  }

  private fun stopUpdates() {
    gate?.stop()
    gate = null
    batcher?.flush()
    batcher = null
    callback?.let { LocationEngine.fused(this).removeLocationUpdates(it) }
    listener?.let {
      LocationManagerCompat.removeUpdates(getSystemService(Context.LOCATION_SERVICE) as LocationManager, it)
    }
    callback = null
    listener = null
  }

  override fun onTaskRemoved(rootIntent: Intent?) {
    val options = LocationStore.backgroundOptions(this)
    if (options?.optBoolean("stopOnTaskRemoved", false) == true) {
      LocationStore.setBoolean(this, LocationStore.BACKGROUND_RUNNING, false)
      stopSelf()
    }
    super.onTaskRemoved(rootIntent)
  }

  override fun onDestroy() {
    stopUpdates()
    if (current === this) current = null
    super.onDestroy()
  }

  private fun metaString(key: String): String? = try {
    val info = if (Build.VERSION.SDK_INT >= 33) {
      packageManager.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong()))
    } else {
      @Suppress("DEPRECATION")
      packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
    }
    val bundle = info.metaData
    when {
      bundle == null || !bundle.containsKey(key) -> null
      // aapt stores "#RRGGBB" meta-data values as colour integers.
      else -> bundle.getString(key) ?: bundle.getInt(key, 0).let { String.format("#%08X", it) }
    }
  } catch (_: Throwable) {
    null
  }

  private fun option(options: JSONObject, key: String, meta: String, fallback: String): String =
    options.optString(key).ifEmpty { metaString(meta) ?: fallback }

  private fun notification(options: JSONObject): Notification {
    val channelId = option(options, "notificationChannelId", "com.munimlocation.notification_channel_id", "munim-location")
    val channelName = option(options, "notificationChannelName", "com.munimlocation.notification_channel_name", "Location tracking")
    if (Build.VERSION.SDK_INT >= 26) {
      val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
      if (manager.getNotificationChannel(channelId) == null) {
        manager.createNotificationChannel(
          NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_LOW).apply {
            setShowBadge(false)
          }
        )
      }
    }
    val iconName = option(options, "notificationIconResourceName", "com.munimlocation.notification_icon", "")
    val icon = iconName.takeIf { it.isNotEmpty() }?.let { name ->
      resources.getIdentifier(name, "drawable", packageName).takeIf { it != 0 }
        ?: resources.getIdentifier(name, "mipmap", packageName).takeIf { it != 0 }
    } ?: applicationInfo.icon
    val launch = packageManager.getLaunchIntentForPackage(packageName)?.let {
      PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
    val builder = NotificationCompat.Builder(this, channelId)
      .setContentTitle(option(options, "notificationTitle", "com.munimlocation.notification_title", "Location tracking"))
      .setContentText(
        option(options, "notificationText", "com.munimlocation.notification_text", "Tracking your location in the background")
      )
      .setSmallIcon(icon)
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .setCategory(NotificationCompat.CATEGORY_SERVICE)
      .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
    launch?.let { builder.setContentIntent(it) }
    option(options, "notificationColor", "com.munimlocation.notification_color", "").takeIf { it.isNotEmpty() }?.let {
      try {
        builder.setColor(Color.parseColor(it))
      } catch (_: Throwable) {
      }
    }
    return builder.build()
  }

  companion object {
    private const val TAG = "MunimLocationService"
    private const val NOTIFICATION_ID = 0x4d4c

    /** The running service, if any (same process). */
    @Volatile
    var current: MunimLocationService? = null
      private set
  }
}
