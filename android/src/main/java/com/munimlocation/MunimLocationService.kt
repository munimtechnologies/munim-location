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

  override fun onBind(intent: Intent?): IBinder? = null

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
    if (LocationEngine.playServicesAvailable(this)) {
      val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
          LocationEvents.deliverBackground(
            this@MunimLocationService,
            "backgroundLocation",
            LocationJson.locations(result.locations)
          )
        }
      }
      callback = locationCallback
      LocationEngine.fused(this).requestLocationUpdates(
        LocationEngine.backgroundRequest(options),
        LocationEngine.executor,
        locationCallback
      )
    } else {
      val manager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
      val provider = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        .firstOrNull { manager.isProviderEnabled(it) } ?: return
      val compatListener = LocationListenerCompat { location ->
        LocationEvents.deliverBackground(this, "backgroundLocation", LocationJson.locations(listOf(location)))
      }
      listener = compatListener
      val request = LocationRequestCompat.Builder(options.optDouble("intervalMs", 10_000.0).toLong())
        .setMinUpdateDistanceMeters(options.optDouble("distanceFilter", 0.0).toFloat())
        .build()
      LocationManagerCompat.requestLocationUpdates(manager, provider, request, LocationEngine.executor, compatListener)
    }
  }

  private fun stopUpdates() {
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
    super.onDestroy()
  }

  private fun metaString(key: String): String? = try {
    val info = if (Build.VERSION.SDK_INT >= 33) {
      packageManager.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong()))
    } else {
      @Suppress("DEPRECATION")
      packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
    }
    info.metaData?.get(key)?.toString()
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
  }
}
