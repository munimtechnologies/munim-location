package com.munimlocation

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/** Persisted settings: what to restore after process death or reboot. */
object LocationStore {
  private const val PREFS = "munim-location"

  private fun prefs(context: Context): SharedPreferences =
    context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  fun getBoolean(context: Context, key: String): Boolean = prefs(context).getBoolean(key, false)

  fun setBoolean(context: Context, key: String, value: Boolean) {
    prefs(context).edit().putBoolean(key, value).apply()
  }

  fun getString(context: Context, key: String): String? = prefs(context).getString(key, null)

  fun setString(context: Context, key: String, value: String?) {
    prefs(context).edit().apply {
      if (value == null) remove(key) else putString(key, value)
    }.apply()
  }

  // ---------- Background ----------

  const val BACKGROUND_RUNNING = "background.running"
  const val BACKGROUND_OPTIONS = "background.options"
  const val SIGNIFICANT_RUNNING = "significant.running"
  const val ASKED_FOREGROUND = "asked.foreground"
  const val ASKED_BACKGROUND = "asked.background"

  fun backgroundOptions(context: Context): JSONObject? =
    getString(context, BACKGROUND_OPTIONS)?.let {
      try {
        JSONObject(it)
      } catch (_: Throwable) {
        null
      }
    }

  // ---------- Geofences ----------

  private const val GEOFENCES = "geofences"
  private const val GEOFENCE_STATES = "geofence.states"

  fun geofences(context: Context): JSONObject =
    getString(context, GEOFENCES)?.let {
      try {
        JSONObject(it)
      } catch (_: Throwable) {
        null
      }
    } ?: JSONObject()

  fun saveGeofences(context: Context, geofences: JSONObject) {
    setString(context, GEOFENCES, geofences.toString())
  }

  fun geofenceStates(context: Context): JSONObject =
    getString(context, GEOFENCE_STATES)?.let {
      try {
        JSONObject(it)
      } catch (_: Throwable) {
        null
      }
    } ?: JSONObject()

  fun setGeofenceState(context: Context, identifier: String, state: String?) {
    val states = geofenceStates(context)
    if (state == null) states.remove(identifier) else states.put(identifier, state)
    setString(context, GEOFENCE_STATES, states.toString())
  }
}
