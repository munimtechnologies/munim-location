package com.munimlocation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.os.Build
import android.util.Log
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofenceStatusCodes
import com.google.android.gms.location.GeofencingEvent
import com.google.android.gms.location.LocationResult

/** PendingIntent target for background locations, significant changes, and geofences. */
class MunimLocationReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    LocationEngine.attach(context)
    when (intent.action) {
      LocationEngine.ACTION_LOCATION -> deliverLocations(context, intent, "backgroundLocation")
      LocationEngine.ACTION_SIGNIFICANT -> deliverLocations(context, intent, "significantLocationChange")
      LocationEngine.ACTION_GEOFENCE -> deliverGeofence(context, intent)
    }
  }

  private fun deliverLocations(context: Context, intent: Intent, name: String) {
    val locations = when {
      LocationResult.hasResult(intent) -> LocationResult.extractResult(intent)?.locations.orEmpty()
      intent.hasExtra(LocationManager.KEY_LOCATION_CHANGED) -> {
        val location = if (Build.VERSION.SDK_INT >= 33) {
          intent.getParcelableExtra(LocationManager.KEY_LOCATION_CHANGED, android.location.Location::class.java)
        } else {
          @Suppress("DEPRECATION")
          intent.getParcelableExtra(LocationManager.KEY_LOCATION_CHANGED)
        }
        listOfNotNull(location)
      }
      else -> emptyList()
    }
    if (locations.isEmpty()) return
    LocationEvents.deliverBackground(context, name, LocationJson.locations(locations))
  }

  private fun deliverGeofence(context: Context, intent: Intent) {
    val event = GeofencingEvent.fromIntent(intent) ?: return
    if (event.hasError()) {
      Log.w(TAG, "Geofence error ${event.errorCode}")
      LocationEvents.deliverBackground(
        context,
        "geofenceError",
        mapOf(
          "code" to "E_GEOFENCE",
          "message" to GeofenceStatusCodes.getStatusCodeString(event.errorCode),
          "nativeCode" to event.errorCode
        )
      )
      return
    }
    val transition = when (event.geofenceTransition) {
      Geofence.GEOFENCE_TRANSITION_ENTER -> "enter"
      Geofence.GEOFENCE_TRANSITION_EXIT -> "exit"
      Geofence.GEOFENCE_TRANSITION_DWELL -> "dwell"
      else -> return
    }
    val stored = LocationStore.geofences(context)
    for (geofence in event.triggeringGeofences.orEmpty()) {
      val identifier = geofence.requestId
      val config = stored.optJSONObject(identifier)
      LocationStore.setGeofenceState(context, identifier, if (transition == "exit") "outside" else "inside")
      // ENTER is registered for notifyOnStartIfInside too; respect notifyOnEntry.
      if (transition == "enter" && config != null && !config.optBoolean("notifyOnEntry", true)) {
        val initial = !config.optBoolean("initialized", false)
        if (!initial || !config.optBoolean("notifyOnStartIfInside", false)) continue
      }
      if (config != null && !config.optBoolean("initialized", false)) {
        config.put("initialized", true)
        stored.put(identifier, config)
        LocationStore.saveGeofences(context, stored)
      }
      val body = mutableMapOf<String, Any?>(
        "identifier" to identifier,
        "transition" to transition,
        "kind" to "circle",
        "timestamp" to System.currentTimeMillis().toDouble()
      )
      event.triggeringLocation?.let { body["location"] = LocationJson.map(it) }
      LocationEvents.deliverBackground(context, "geofenceTransition", body)
    }
  }

  companion object {
    private const val TAG = "MunimLocationReceiver"
  }
}

/** Restores background tracking, significant changes, and geofences after reboot or update. */
class MunimLocationBootReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val action = intent.action ?: return
    if (action != Intent.ACTION_BOOT_COMPLETED &&
      action != Intent.ACTION_MY_PACKAGE_REPLACED &&
      action != "android.intent.action.QUICKBOOT_POWERON"
    ) {
      return
    }
    LocationEngine.attach(context)
    try {
      LocationEngine.restoreGeofences(context)
      if (LocationStore.getBoolean(context, LocationStore.SIGNIFICANT_RUNNING)) {
        LocationEngine.startSignificantChanges(context)
      }
      val options = LocationStore.backgroundOptions(context)
      if (LocationStore.getBoolean(context, LocationStore.BACKGROUND_RUNNING) &&
        options?.optBoolean("restartOnBoot", false) == true
      ) {
        LocationEngine.resumeBackground(context, fromBoot = true)
      }
      LocationEvents.appendPending(
        context,
        "backgroundLaunch",
        LocationJson.toJson(
          mapOf("reason" to if (action == Intent.ACTION_MY_PACKAGE_REPLACED) "packageReplaced" else "boot", "timestamp" to System.currentTimeMillis().toDouble())
        ).toString()
      )
    } catch (error: Throwable) {
      Log.w("MunimLocationBoot", "Restore after $action failed", error)
    }
  }
}
