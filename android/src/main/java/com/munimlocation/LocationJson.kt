package com.munimlocation

import android.os.Build
import com.margelo.nitro.munimlocation.GeofenceRegion
import com.margelo.nitro.munimlocation.Heading
import com.margelo.nitro.munimlocation.Location
import org.json.JSONArray
import org.json.JSONObject

/** Converters between platform objects, Nitro structs, and JSON payloads. */
object LocationJson {
  fun location(location: android.location.Location): Location {
    val mock = if (Build.VERSION.SDK_INT >= 31) {
      location.isMock
    } else {
      @Suppress("DEPRECATION")
      location.isFromMockProvider
    }
    var mslAltitude: Double? = null
    var mslAccuracy: Double? = null
    if (Build.VERSION.SDK_INT >= 34) {
      if (location.hasMslAltitude()) mslAltitude = location.mslAltitudeMeters
      if (location.hasMslAltitudeAccuracy()) mslAccuracy = location.mslAltitudeAccuracyMeters.toDouble()
    }
    var verticalAccuracy: Double? = null
    var speedAccuracy: Double? = null
    var bearingAccuracy: Double? = null
    if (Build.VERSION.SDK_INT >= 26) {
      if (location.hasVerticalAccuracy()) verticalAccuracy = location.verticalAccuracyMeters.toDouble()
      if (location.hasSpeedAccuracy()) speedAccuracy = location.speedAccuracyMetersPerSecond.toDouble()
      if (location.hasBearingAccuracy()) bearingAccuracy = location.bearingAccuracyDegrees.toDouble()
    }
    var realtimeUncertainty: Double? = null
    if (Build.VERSION.SDK_INT >= 29 && location.hasElapsedRealtimeUncertaintyNanos()) {
      realtimeUncertainty = location.elapsedRealtimeUncertaintyNanos
    }
    val satellites = location.extras?.let { extras ->
      if (extras.containsKey("satellites")) extras.getInt("satellites").toDouble() else null
    }
    return Location(
      latitude = location.latitude,
      longitude = location.longitude,
      altitude = if (location.hasAltitude()) location.altitude else null,
      ellipsoidalAltitude = null,
      mslAltitude = mslAltitude,
      mslAltitudeAccuracy = mslAccuracy,
      horizontalAccuracy = if (location.hasAccuracy()) location.accuracy.toDouble() else -1.0,
      verticalAccuracy = verticalAccuracy,
      speed = if (location.hasSpeed()) location.speed.toDouble() else null,
      speedAccuracy = speedAccuracy,
      course = if (location.hasBearing()) location.bearing.toDouble() else null,
      courseAccuracy = bearingAccuracy,
      timestamp = location.time.toDouble(),
      elapsedRealtimeNanos = location.elapsedRealtimeNanos.toDouble(),
      elapsedRealtimeUncertaintyNanos = realtimeUncertainty,
      floor = null,
      provider = location.provider,
      isMock = mock,
      isSimulatedBySoftware = null,
      isProducedByAccessory = null,
      satelliteCount = satellites
    )
  }

  fun map(location: Location): Map<String, Any?> = mapOf(
    "latitude" to location.latitude,
    "longitude" to location.longitude,
    "altitude" to location.altitude,
    "mslAltitude" to location.mslAltitude,
    "mslAltitudeAccuracy" to location.mslAltitudeAccuracy,
    "horizontalAccuracy" to location.horizontalAccuracy,
    "verticalAccuracy" to location.verticalAccuracy,
    "speed" to location.speed,
    "speedAccuracy" to location.speedAccuracy,
    "course" to location.course,
    "courseAccuracy" to location.courseAccuracy,
    "timestamp" to location.timestamp,
    "elapsedRealtimeNanos" to location.elapsedRealtimeNanos,
    "elapsedRealtimeUncertaintyNanos" to location.elapsedRealtimeUncertaintyNanos,
    "provider" to location.provider,
    "isMock" to location.isMock,
    "satelliteCount" to location.satelliteCount
  )

  fun map(location: android.location.Location): Map<String, Any?> = map(location(location))

  fun locations(list: List<android.location.Location>): Map<String, Any?> =
    mapOf("locations" to list.map { map(it) })

  fun map(heading: Heading): Map<String, Any?> = mapOf(
    "magneticHeading" to heading.magneticHeading,
    "trueHeading" to heading.trueHeading,
    "headingAccuracy" to heading.headingAccuracy,
    "timestamp" to heading.timestamp,
    "source" to heading.source
  )

  fun geofenceJson(region: GeofenceRegion): JSONObject = JSONObject()
    .put("identifier", region.identifier)
    .put("latitude", region.latitude)
    .put("longitude", region.longitude)
    .put("radius", region.radius)
    .put("notifyOnEntry", region.notifyOnEntry)
    .put("notifyOnExit", region.notifyOnExit)
    .put("notifyOnDwell", region.notifyOnDwell)
    .put("loiteringDelayMs", region.loiteringDelayMs)
    .put("expirationMs", region.expirationMs)
    .put("notifyOnStartIfInside", region.notifyOnStartIfInside)

  fun geofence(json: JSONObject): GeofenceRegion = GeofenceRegion(
    identifier = json.getString("identifier"),
    latitude = json.getDouble("latitude"),
    longitude = json.getDouble("longitude"),
    radius = json.getDouble("radius"),
    notifyOnEntry = json.optBoolean("notifyOnEntry", true),
    notifyOnExit = json.optBoolean("notifyOnExit", true),
    notifyOnDwell = json.optBoolean("notifyOnDwell", false),
    loiteringDelayMs = json.optDouble("loiteringDelayMs", 0.0),
    expirationMs = json.optDouble("expirationMs", 0.0),
    notifyOnStartIfInside = json.optBoolean("notifyOnStartIfInside", false)
  )

  fun toJson(value: Map<String, Any?>): JSONObject {
    val result = JSONObject()
    value.forEach { (key, item) ->
      val converted = convert(item)
      if (converted != null) result.put(key, converted)
    }
    return result
  }

  private fun convert(value: Any?): Any? = when (value) {
    null -> null
    is Double -> if (value.isFinite()) value else null
    is Float -> if (value.isFinite()) value.toDouble() else null
    is Number, is Boolean, is String -> value
    is Map<*, *> -> {
      @Suppress("UNCHECKED_CAST")
      toJson(value as Map<String, Any?>)
    }
    is List<*> -> JSONArray().also { array -> value.forEach { array.put(convert(it) ?: JSONObject.NULL) } }
    is Array<*> -> JSONArray().also { array -> value.forEach { array.put(convert(it) ?: JSONObject.NULL) } }
    is JSONObject, is JSONArray -> value
    else -> value.toString()
  }
}
