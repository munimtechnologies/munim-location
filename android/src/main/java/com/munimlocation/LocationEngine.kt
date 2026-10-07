package com.munimlocation

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationRequestCompat
import androidx.core.os.CancellationSignal
import com.facebook.react.bridge.BaseActivityEventListener
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.Granularity
import com.google.android.gms.location.LocationAvailability
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.gms.location.LocationSettingsStatusCodes
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.margelo.nitro.NitroModules
import com.margelo.nitro.munimlocation.AndroidBackgroundMode
import com.margelo.nitro.munimlocation.AndroidBackgroundOptions
import com.margelo.nitro.munimlocation.CurrentPositionOptions
import com.margelo.nitro.munimlocation.GeofenceRegion
import com.margelo.nitro.munimlocation.GeofenceState
import com.margelo.nitro.munimlocation.LastKnownPositionOptions
import com.margelo.nitro.munimlocation.Location
import com.margelo.nitro.munimlocation.LocationAccuracy
import com.margelo.nitro.munimlocation.LocationGranularity
import com.margelo.nitro.munimlocation.LocationPriority
import com.margelo.nitro.munimlocation.LocationSettingsResult
import com.margelo.nitro.munimlocation.MockLocation
import com.margelo.nitro.munimlocation.ProviderStatus
import com.margelo.nitro.munimlocation.WatchOptions
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Process-wide location engine shared by the Nitro object, the foreground
 * service, and the broadcast receivers.
 */
@SuppressLint("MissingPermission")
object LocationEngine {
  private const val TAG = "MunimLocation"
  const val ACTION_LOCATION = "com.munimlocation.ACTION_LOCATION"
  const val ACTION_SIGNIFICANT = "com.munimlocation.ACTION_SIGNIFICANT"
  const val ACTION_GEOFENCE = "com.munimlocation.ACTION_GEOFENCE"
  private const val MAX_GEOFENCES = 100

  private val thread: HandlerThread by lazy { HandlerThread("MunimLocation").apply { start() } }
  val looper: Looper get() = thread.looper
  val handler: Handler by lazy { Handler(looper) }
  val executor: Executor = Executor { handler.post(it) }
  private val mainHandler = Handler(Looper.getMainLooper())
  private val watchIds = AtomicInteger(0)
  private val watches = ConcurrentHashMap<Int, Any>()
  private val settingsRequestCodes = AtomicInteger(42_000)

  @Volatile
  private var appContext: Context? = null

  fun context(): Context {
    appContext?.let { return it }
    val fromNitro = NitroModules.applicationContext?.applicationContext
      ?: throw LocationException("E_LOCATION_ERROR", "React Native context is not ready")
    appContext = fromNitro
    return fromNitro
  }

  fun attach(context: Context) {
    if (appContext == null) appContext = context.applicationContext
  }

  fun locationManager(context: Context = context()): LocationManager =
    context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

  fun playServicesAvailable(context: Context = context()): Boolean = try {
    GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
  } catch (_: Throwable) {
    false
  }

  fun fused(context: Context = context()): FusedLocationProviderClient =
    LocationServices.getFusedLocationProviderClient(context)

  fun geofencing(context: Context = context()): GeofencingClient =
    LocationServices.getGeofencingClient(context)

  // ---------- Mapping ----------

  fun priority(priority: LocationPriority, accuracy: LocationAccuracy): Int = when (priority) {
    LocationPriority.HIGHACCURACY -> Priority.PRIORITY_HIGH_ACCURACY
    LocationPriority.BALANCED -> Priority.PRIORITY_BALANCED_POWER_ACCURACY
    LocationPriority.LOWPOWER -> Priority.PRIORITY_LOW_POWER
    LocationPriority.PASSIVE -> Priority.PRIORITY_PASSIVE
    LocationPriority.AUTO -> when (accuracy) {
      LocationAccuracy.BESTFORNAVIGATION, LocationAccuracy.BEST, LocationAccuracy.NEARESTTENMETERS ->
        Priority.PRIORITY_HIGH_ACCURACY
      LocationAccuracy.HUNDREDMETERS -> Priority.PRIORITY_BALANCED_POWER_ACCURACY
      LocationAccuracy.KILOMETER, LocationAccuracy.THREEKILOMETERS, LocationAccuracy.REDUCED ->
        Priority.PRIORITY_LOW_POWER
    }
  }

  private fun granularity(granularity: LocationGranularity, accuracy: LocationAccuracy): Int = when {
    accuracy == LocationAccuracy.REDUCED -> Granularity.GRANULARITY_COARSE
    granularity == LocationGranularity.FINE -> Granularity.GRANULARITY_FINE
    granularity == LocationGranularity.COARSE -> Granularity.GRANULARITY_COARSE
    else -> Granularity.GRANULARITY_PERMISSION_LEVEL
  }

  private fun quality(priority: Int): Int = when (priority) {
    Priority.PRIORITY_HIGH_ACCURACY -> LocationRequestCompat.QUALITY_HIGH_ACCURACY
    Priority.PRIORITY_BALANCED_POWER_ACCURACY -> LocationRequestCompat.QUALITY_BALANCED_POWER_ACCURACY
    else -> LocationRequestCompat.QUALITY_LOW_POWER
  }

  fun locationRequest(options: WatchOptions): LocationRequest {
    val priority = priority(options.priority, options.accuracy)
    val interval = options.intervalMs.toLong().coerceAtLeast(0)
    val builder = LocationRequest.Builder(priority, interval)
      .setGranularity(granularity(options.granularity, options.accuracy))
      .setWaitForAccurateLocation(options.waitForAccurateLocation)
    if (options.fastestIntervalMs > 0) builder.setMinUpdateIntervalMillis(options.fastestIntervalMs.toLong())
    if (options.distanceFilter > 0) builder.setMinUpdateDistanceMeters(options.distanceFilter.toFloat())
    if (options.maxUpdateDelayMs > 0) builder.setMaxUpdateDelayMillis(options.maxUpdateDelayMs.toLong())
    if (options.minUpdateAgeMs > 0) builder.setMaxUpdateAgeMillis(options.minUpdateAgeMs.toLong())
    if (options.maxUpdates > 0) builder.setMaxUpdates(options.maxUpdates.toInt())
    return builder.build()
  }

  private fun compatRequest(options: WatchOptions): LocationRequestCompat {
    val builder = LocationRequestCompat.Builder(options.intervalMs.toLong().coerceAtLeast(0))
      .setQuality(quality(priority(options.priority, options.accuracy)))
    if (options.fastestIntervalMs > 0) builder.setMinUpdateIntervalMillis(options.fastestIntervalMs.toLong())
    if (options.distanceFilter > 0) builder.setMinUpdateDistanceMeters(options.distanceFilter.toFloat())
    if (options.maxUpdateDelayMs > 0) builder.setMaxUpdateDelayMillis(options.maxUpdateDelayMs.toLong())
    if (options.maxUpdates > 0) builder.setMaxUpdates(options.maxUpdates.toInt())
    return builder.build()
  }

  private fun bestProvider(manager: LocationManager, priority: Int): String? {
    val enabled = manager.getProviders(true)
    val order = when (priority) {
      Priority.PRIORITY_HIGH_ACCURACY -> listOf("fused", LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
      Priority.PRIORITY_PASSIVE -> listOf(LocationManager.PASSIVE_PROVIDER)
      else -> listOf("fused", LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
    }
    return order.firstOrNull { enabled.contains(it) } ?: enabled.firstOrNull()
  }

  private fun requireForeground(context: Context) {
    if (!LocationPermissions.servicesEnabled(context)) {
      throw LocationException("E_LOCATION_SERVICES_DISABLED", "Location services are turned off")
    }
    if (!LocationPermissions.hasForeground(context)) {
      throw LocationException("E_LOCATION_PERMISSION_DENIED", "Location permission has not been granted")
    }
  }

  // ---------- Positions ----------

  fun currentPosition(options: CurrentPositionOptions, resolve: (Location) -> Unit, reject: (Throwable) -> Unit) {
    val context = context()
    try {
      requireForeground(context)
    } catch (error: Throwable) {
      reject(error)
      return
    }
    val done = AtomicBoolean(false)
    val finish: (Location?, Throwable?) -> Unit = { location, error ->
      if (done.compareAndSet(false, true)) {
        when {
          location != null -> resolve(location)
          error != null -> reject(error)
          else -> reject(LocationException("E_LOCATION_UNAVAILABLE", "No location is available"))
        }
      }
    }
    val timeout = (if (options.timeoutMs > 0) options.timeoutMs else 30_000.0).toLong()
    val priority = priority(options.priority, options.accuracy)

    if (playServicesAvailable(context)) {
      val tokenSource = CancellationTokenSource()
      val builder = CurrentLocationRequest.Builder()
        .setPriority(priority)
        .setGranularity(granularity(options.granularity, options.accuracy))
        .setDurationMillis((if (options.durationMs > 0) options.durationMs else options.timeoutMs).toLong().coerceAtLeast(1_000))
        .setMaxUpdateAgeMillis(options.maximumAgeMs.toLong().coerceAtLeast(0))
      fused(context).getCurrentLocation(builder.build(), tokenSource.token)
        .addOnSuccessListener(executor) { location ->
          finish(location?.let { LocationJson.location(it) }, null)
        }
        .addOnFailureListener(executor) { error -> finish(null, mapError(error)) }
      handler.postDelayed({
        if (!done.get()) {
          tokenSource.cancel()
          finish(null, LocationException("E_LOCATION_TIMEOUT", "Timed out waiting for a location"))
        }
      }, timeout)
      return
    }

    // No Google Play services: framework LocationManager.
    val manager = locationManager(context)
    if (options.maximumAgeMs > 0) {
      val cached = manager.getProviders(true).mapNotNull { manager.getLastKnownLocation(it) }.maxByOrNull { it.time }
      if (cached != null && ageMs(cached) <= options.maximumAgeMs) {
        finish(LocationJson.location(cached), null)
        return
      }
    }
    val provider = bestProvider(manager, priority)
    if (provider == null) {
      finish(null, LocationException("E_LOCATION_UNAVAILABLE", "No enabled location provider"))
      return
    }
    val signal = CancellationSignal()
    LocationManagerCompat.getCurrentLocation(manager, provider, signal, executor) { location ->
      finish(location?.let { LocationJson.location(it) }, null)
    }
    handler.postDelayed({
      if (!done.get()) {
        signal.cancel()
        finish(null, LocationException("E_LOCATION_TIMEOUT", "Timed out waiting for a location"))
      }
    }, timeout)
  }

  fun ageMs(location: android.location.Location): Double =
    (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000.0

  fun lastKnown(options: LastKnownPositionOptions, resolve: (Location?) -> Unit, reject: (Throwable) -> Unit) {
    val context = context()
    if (!LocationPermissions.hasForeground(context)) {
      resolve(null)
      return
    }
    val accept: (android.location.Location?) -> Unit = { location ->
      when {
        location == null -> resolve(null)
        options.maximumAgeMs > 0 && ageMs(location) > options.maximumAgeMs -> resolve(null)
        options.requiredAccuracy > 0 && (!location.hasAccuracy() || location.accuracy > options.requiredAccuracy) ->
          resolve(null)
        else -> resolve(LocationJson.location(location))
      }
    }
    if (playServicesAvailable(context)) {
      fused(context).lastLocation
        .addOnSuccessListener(executor) { accept(it) }
        .addOnFailureListener(executor) { reject(mapError(it)) }
    } else {
      val manager = locationManager(context)
      accept(manager.getProviders(true).mapNotNull { manager.getLastKnownLocation(it) }.maxByOrNull { it.time })
    }
  }

  fun watch(options: WatchOptions): Int {
    val id = watchIds.incrementAndGet()
    val context = context()
    try {
      requireForeground(context)
    } catch (error: Throwable) {
      handler.post {
        LocationEvents.emit("locationError", errorBody(error) + mapOf("watchId" to id))
      }
      return id
    }
    if (playServicesAvailable(context)) {
      val callback = object : LocationCallback() {
        private var delivered = 0

        override fun onLocationResult(result: LocationResult) {
          delivered += result.locations.size
          LocationEvents.emit("location", mapOf("watchId" to id) + LocationJson.locations(result.locations))
          if (options.maxUpdates > 0 && delivered >= options.maxUpdates) clearWatch(id)
        }

        override fun onLocationAvailability(availability: LocationAvailability) {
          LocationEvents.emit(
            "locationAvailability",
            mapOf("watchId" to id, "available" to availability.isLocationAvailable)
          )
        }
      }
      watches[id] = callback
      fused(context).requestLocationUpdates(locationRequest(options), executor, callback)
        .addOnFailureListener(executor) { error ->
          watches.remove(id)
          LocationEvents.emit("locationError", errorBody(mapError(error)) + mapOf("watchId" to id))
        }
    } else {
      val manager = locationManager(context)
      val provider = bestProvider(manager, priority(options.priority, options.accuracy))
      if (provider == null) {
        LocationEvents.emit(
          "locationError",
          mapOf("watchId" to id, "code" to "E_LOCATION_UNAVAILABLE", "message" to "No enabled location provider")
        )
        return id
      }
      val listener = object : LocationListenerCompat {
        override fun onLocationChanged(location: android.location.Location) {
          LocationEvents.emit("location", mapOf("watchId" to id) + LocationJson.locations(listOf(location)))
        }

        override fun onProviderDisabled(provider: String) {
          LocationEvents.emit("locationAvailability", mapOf("watchId" to id, "available" to false))
        }

        override fun onProviderEnabled(provider: String) {
          LocationEvents.emit("locationAvailability", mapOf("watchId" to id, "available" to true))
        }
      }
      watches[id] = listener
      LocationManagerCompat.requestLocationUpdates(manager, provider, compatRequest(options), executor, listener)
    }
    return id
  }

  fun clearWatch(id: Int) {
    val entry = watches.remove(id) ?: return
    val context = context()
    when (entry) {
      is LocationCallback -> fused(context).removeLocationUpdates(entry)
      is LocationListenerCompat -> LocationManagerCompat.removeUpdates(locationManager(context), entry)
    }
  }

  fun clearAllWatches() {
    watches.keys.toList().forEach { clearWatch(it) }
  }

  // ---------- Providers and settings ----------

  fun providerStatus(context: Context = context()): ProviderStatus {
    val manager = locationManager(context)
    val providers = try {
      manager.allProviders
    } catch (_: Throwable) {
      emptyList()
    }
    fun enabled(name: String) = try {
      providers.contains(name) && manager.isProviderEnabled(name)
    } catch (_: Throwable) {
      false
    }
    return ProviderStatus(
      locationServicesEnabled = LocationManagerCompat.isLocationEnabled(manager),
      gpsEnabled = enabled(LocationManager.GPS_PROVIDER),
      networkEnabled = enabled(LocationManager.NETWORK_PROVIDER),
      passiveEnabled = enabled(LocationManager.PASSIVE_PROVIDER),
      fusedEnabled = enabled("fused") || playServicesAvailable(context),
      airplaneModeOn = Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1,
      providers = providers.toTypedArray()
    )
  }

  private fun settingsRequest(priority: LocationPriority, needBle: Boolean): LocationSettingsRequest {
    val request = LocationRequest.Builder(priority(priority, LocationAccuracy.BEST), 10_000).build()
    return LocationSettingsRequest.Builder()
      .addLocationRequest(request)
      .setNeedBle(needBle)
      .setAlwaysShow(true)
      .build()
  }

  private fun fallbackSettings(context: Context, satisfied: Boolean, resolvable: Boolean, code: Int, message: String): LocationSettingsResult {
    val status = providerStatus(context)
    return LocationSettingsResult(
      satisfied = satisfied,
      resolvable = resolvable,
      locationPresent = status.providers.isNotEmpty(),
      locationUsable = status.locationServicesEnabled,
      gpsPresent = status.providers.contains(LocationManager.GPS_PROVIDER),
      gpsUsable = status.gpsEnabled,
      networkLocationPresent = status.providers.contains(LocationManager.NETWORK_PROVIDER),
      networkLocationUsable = status.networkEnabled,
      blePresent = context.packageManager.hasSystemFeature("android.hardware.bluetooth_le"),
      bleUsable = false,
      statusCode = code.toDouble(),
      message = message
    )
  }

  fun checkSettings(priority: LocationPriority, needBle: Boolean, resolve: (LocationSettingsResult) -> Unit) {
    val context = context()
    if (!playServicesAvailable(context)) {
      val enabled = LocationPermissions.servicesEnabled(context)
      resolve(fallbackSettings(context, enabled, false, if (enabled) 0 else 6, if (enabled) "" else "Location services are turned off"))
      return
    }
    LocationServices.getSettingsClient(context).checkLocationSettings(settingsRequest(priority, needBle))
      .addOnSuccessListener(executor) { response ->
        val states = response.locationSettingsStates
        resolve(
          LocationSettingsResult(
            satisfied = true,
            resolvable = false,
            locationPresent = states?.isLocationPresent ?: true,
            locationUsable = states?.isLocationUsable ?: true,
            gpsPresent = states?.isGpsPresent ?: false,
            gpsUsable = states?.isGpsUsable ?: false,
            networkLocationPresent = states?.isNetworkLocationPresent ?: false,
            networkLocationUsable = states?.isNetworkLocationUsable ?: false,
            blePresent = states?.isBlePresent ?: false,
            bleUsable = states?.isBleUsable ?: false,
            statusCode = 0.0,
            message = ""
          )
        )
      }
      .addOnFailureListener(executor) { error ->
        val code = (error as? ApiException)?.statusCode ?: CommonStatusCodes.ERROR
        resolve(
          fallbackSettings(
            context,
            satisfied = false,
            resolvable = error is ResolvableApiException,
            code = code,
            message = error.message ?: "Location settings are not satisfied"
          )
        )
      }
  }

  fun resolveSettings(priority: LocationPriority, needBle: Boolean, resolve: (Boolean) -> Unit, reject: (Throwable) -> Unit) {
    val context = context()
    if (!playServicesAvailable(context)) {
      resolve(LocationPermissions.servicesEnabled(context))
      return
    }
    LocationServices.getSettingsClient(context).checkLocationSettings(settingsRequest(priority, needBle))
      .addOnSuccessListener(executor) { resolve(true) }
      .addOnFailureListener(executor) { error ->
        if (error !is ResolvableApiException) {
          val code = (error as? ApiException)?.statusCode
          if (code == LocationSettingsStatusCodes.SETTINGS_CHANGE_UNAVAILABLE) {
            resolve(false)
          } else {
            reject(LocationException("E_LOCATION_SETTINGS", error.message ?: "Location settings check failed"))
          }
          return@addOnFailureListener
        }
        val reactContext = NitroModules.applicationContext
        val activity = reactContext?.currentActivity
        if (reactContext == null || activity == null) {
          reject(LocationException("E_NO_ACTIVITY", "The settings dialog needs a foreground activity"))
          return@addOnFailureListener
        }
        val requestCode = settingsRequestCodes.incrementAndGet()
        val listener = object : BaseActivityEventListener() {
          override fun onActivityResult(activity: Activity, code: Int, resultCode: Int, data: Intent?) {
            if (code != requestCode) return
            reactContext.removeActivityEventListener(this)
            val satisfied = resultCode == Activity.RESULT_OK
            LocationEvents.emit("locationSettingsResolved", mapOf("satisfied" to satisfied))
            resolve(satisfied)
          }
        }
        reactContext.addActivityEventListener(listener)
        mainHandler.post {
          try {
            error.startResolutionForResult(activity, requestCode)
          } catch (failure: Throwable) {
            reactContext.removeActivityEventListener(listener)
            reject(LocationException("E_LOCATION_SETTINGS", failure.message ?: "Unable to show the settings dialog"))
          }
        }
      }
  }

  fun isLocationAvailable(resolve: (Boolean) -> Unit) {
    val context = context()
    if (!LocationPermissions.servicesEnabled(context) || !LocationPermissions.hasForeground(context)) {
      resolve(false)
      return
    }
    if (!playServicesAvailable(context)) {
      resolve(locationManager(context).getProviders(true).isNotEmpty())
      return
    }
    fused(context).locationAvailability
      .addOnSuccessListener(executor) { resolve(it.isLocationAvailable) }
      .addOnFailureListener(executor) { resolve(false) }
  }

  // ---------- Background (foreground service or PendingIntent) ----------

  fun pendingIntent(context: Context, action: String, requestCode: Int): PendingIntent {
    val intent = Intent(context, MunimLocationReceiver::class.java).setAction(action)
    var flags = PendingIntent.FLAG_UPDATE_CURRENT
    if (Build.VERSION.SDK_INT >= 31) flags = flags or PendingIntent.FLAG_MUTABLE
    return PendingIntent.getBroadcast(context, requestCode, intent, flags)
  }

  fun backgroundOptionsJson(options: WatchOptions, android: AndroidBackgroundOptions): JSONObject = JSONObject()
    .put("accuracy", options.accuracy.name)
    .put("priority", options.priority.name)
    .put("granularity", options.granularity.name)
    .put("distanceFilter", options.distanceFilter)
    .put("intervalMs", options.intervalMs)
    .put("fastestIntervalMs", options.fastestIntervalMs)
    .put("maxUpdateDelayMs", options.maxUpdateDelayMs)
    .put("minUpdateAgeMs", options.minUpdateAgeMs)
    .put("waitForAccurateLocation", options.waitForAccurateLocation)
    .put("mode", android.mode.name)
    .put("notificationTitle", android.notificationTitle)
    .put("notificationText", android.notificationText)
    .put("notificationChannelId", android.notificationChannelId)
    .put("notificationChannelName", android.notificationChannelName)
    .put("notificationIconResourceName", android.notificationIconResourceName)
    .put("notificationColor", android.notificationColor)
    .put("restartOnBoot", android.restartOnBoot)
    .put("stopOnTaskRemoved", android.stopOnTaskRemoved)

  fun backgroundRequest(json: JSONObject): LocationRequest {
    val accuracy = runCatching { LocationAccuracy.valueOf(json.optString("accuracy")) }.getOrDefault(LocationAccuracy.BEST)
    val priority = runCatching { LocationPriority.valueOf(json.optString("priority")) }.getOrDefault(LocationPriority.AUTO)
    val granularity = runCatching { LocationGranularity.valueOf(json.optString("granularity")) }
      .getOrDefault(LocationGranularity.PERMISSIONLEVEL)
    val builder = LocationRequest.Builder(priority(priority, accuracy), json.optDouble("intervalMs", 10_000.0).toLong())
      .setGranularity(granularity(granularity, accuracy))
      .setWaitForAccurateLocation(json.optBoolean("waitForAccurateLocation", false))
    json.optDouble("fastestIntervalMs", 0.0).takeIf { it > 0 }?.let { builder.setMinUpdateIntervalMillis(it.toLong()) }
    json.optDouble("distanceFilter", 0.0).takeIf { it > 0 }?.let { builder.setMinUpdateDistanceMeters(it.toFloat()) }
    json.optDouble("maxUpdateDelayMs", 0.0).takeIf { it > 0 }?.let { builder.setMaxUpdateDelayMillis(it.toLong()) }
    json.optDouble("minUpdateAgeMs", 0.0).takeIf { it > 0 }?.let { builder.setMaxUpdateAgeMillis(it.toLong()) }
    return builder.build()
  }

  fun startBackground(options: WatchOptions, android: AndroidBackgroundOptions) {
    val context = context()
    requireForeground(context)
    val json = backgroundOptionsJson(options, android)
    if (android.mode == AndroidBackgroundMode.FOREGROUNDSERVICE) {
      if (Build.VERSION.SDK_INT >= 34 &&
        !LocationPermissions.declaredInManifest(context, Manifest.permission.FOREGROUND_SERVICE_LOCATION)
      ) {
        throw LocationException(
          "E_FOREGROUND_SERVICE",
          "Declare android.permission.FOREGROUND_SERVICE_LOCATION (the Expo config plugin adds it with isAndroidForegroundServiceEnabled)"
        )
      }
      if (!LocationPermissions.declaredInManifest(context, Manifest.permission.FOREGROUND_SERVICE)) {
        throw LocationException("E_FOREGROUND_SERVICE", "Declare android.permission.FOREGROUND_SERVICE")
      }
    } else if (!playServicesAvailable(context)) {
      throw LocationException("E_UNSUPPORTED", "PendingIntent delivery needs Google Play services")
    }
    stopBackgroundInternal(context, persist = false)
    LocationStore.setString(context, LocationStore.BACKGROUND_OPTIONS, json.toString())
    LocationStore.setBoolean(context, LocationStore.BACKGROUND_RUNNING, true)
    resumeBackground(context, fromBoot = false)
  }

  /** Starts background delivery from persisted options. */
  fun resumeBackground(context: Context, fromBoot: Boolean) {
    val json = LocationStore.backgroundOptions(context) ?: return
    val mode = runCatching { AndroidBackgroundMode.valueOf(json.optString("mode")) }
      .getOrDefault(AndroidBackgroundMode.FOREGROUNDSERVICE)
    if (mode == AndroidBackgroundMode.FOREGROUNDSERVICE) {
      try {
        ContextCompat.startForegroundService(context, Intent(context, MunimLocationService::class.java))
        return
      } catch (error: Throwable) {
        if (!fromBoot) throw LocationException("E_FOREGROUND_SERVICE", error.message ?: "Unable to start the foreground service")
        Log.w(TAG, "Foreground service refused after boot; using PendingIntent delivery", error)
      }
    }
    if (playServicesAvailable(context)) {
      fused(context).requestLocationUpdates(backgroundRequest(json), pendingIntent(context, ACTION_LOCATION, 1))
    }
  }

  fun stopBackground() {
    stopBackgroundInternal(context(), persist = true)
  }

  private fun stopBackgroundInternal(context: Context, persist: Boolean) {
    if (persist) LocationStore.setBoolean(context, LocationStore.BACKGROUND_RUNNING, false)
    try {
      context.stopService(Intent(context, MunimLocationService::class.java))
    } catch (_: Throwable) {
    }
    if (playServicesAvailable(context)) {
      fused(context).removeLocationUpdates(pendingIntent(context, ACTION_LOCATION, 1))
    }
  }

  fun startSignificantChanges(context: Context = context()) {
    if (!LocationPermissions.hasForeground(context)) {
      throw LocationException("E_LOCATION_PERMISSION_DENIED", "Location permission has not been granted")
    }
    LocationStore.setBoolean(context, LocationStore.SIGNIFICANT_RUNNING, true)
    val request = LocationRequest.Builder(Priority.PRIORITY_LOW_POWER, 5 * 60_000L)
      .setMinUpdateDistanceMeters(500f)
      .setMinUpdateIntervalMillis(60_000L)
      .build()
    if (playServicesAvailable(context)) {
      fused(context).requestLocationUpdates(request, pendingIntent(context, ACTION_SIGNIFICANT, 2))
    } else {
      val manager = locationManager(context)
      val provider = if (manager.allProviders.contains(LocationManager.PASSIVE_PROVIDER)) {
        LocationManager.PASSIVE_PROVIDER
      } else {
        LocationManager.NETWORK_PROVIDER
      }
      manager.requestLocationUpdates(provider, 5 * 60_000L, 500f, pendingIntent(context, ACTION_SIGNIFICANT, 2))
    }
  }

  fun stopSignificantChanges() {
    val context = context()
    LocationStore.setBoolean(context, LocationStore.SIGNIFICANT_RUNNING, false)
    val intent = pendingIntent(context, ACTION_SIGNIFICANT, 2)
    if (playServicesAvailable(context)) fused(context).removeLocationUpdates(intent)
    try {
      locationManager(context).removeUpdates(intent)
    } catch (_: Throwable) {
    }
  }

  // ---------- Geofences ----------

  private fun geofencePendingIntent(context: Context) = pendingIntent(context, ACTION_GEOFENCE, 3)

  private fun buildGeofence(region: GeofenceRegion): Geofence {
    var transitions = 0
    if (region.notifyOnEntry || region.notifyOnStartIfInside) transitions = transitions or Geofence.GEOFENCE_TRANSITION_ENTER
    if (region.notifyOnExit) transitions = transitions or Geofence.GEOFENCE_TRANSITION_EXIT
    if (region.notifyOnDwell) transitions = transitions or Geofence.GEOFENCE_TRANSITION_DWELL
    if (transitions == 0) transitions = Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT
    return Geofence.Builder()
      .setRequestId(region.identifier)
      .setCircularRegion(region.latitude, region.longitude, region.radius.toFloat())
      .setTransitionTypes(transitions)
      .setLoiteringDelay(region.loiteringDelayMs.toInt().coerceAtLeast(0))
      .setExpirationDuration(if (region.expirationMs > 0) region.expirationMs.toLong() else Geofence.NEVER_EXPIRE)
      .build()
  }

  private fun geofencingRequest(region: GeofenceRegion): GeofencingRequest {
    var initial = 0
    if (region.notifyOnStartIfInside) initial = initial or GeofencingRequest.INITIAL_TRIGGER_ENTER
    if (region.notifyOnStartIfInside && region.notifyOnDwell) initial = initial or GeofencingRequest.INITIAL_TRIGGER_DWELL
    return GeofencingRequest.Builder()
      .setInitialTrigger(initial)
      .addGeofence(buildGeofence(region))
      .build()
  }

  fun addGeofence(region: GeofenceRegion, resolve: () -> Unit, reject: (Throwable) -> Unit) {
    val context = context()
    if (!playServicesAvailable(context)) {
      reject(LocationException("E_UNSUPPORTED", "Geofencing needs Google Play services"))
      return
    }
    if (!LocationPermissions.hasFine(context)) {
      reject(LocationException("E_LOCATION_PERMISSION_DENIED", "Geofencing needs ACCESS_FINE_LOCATION"))
      return
    }
    if (Build.VERSION.SDK_INT >= 29 && !LocationPermissions.hasBackground(context)) {
      reject(LocationException("E_LOCATION_PERMISSION_DENIED", "Geofencing on Android 10+ needs ACCESS_BACKGROUND_LOCATION"))
      return
    }
    val stored = LocationStore.geofences(context)
    if (!stored.has(region.identifier) && stored.length() >= MAX_GEOFENCES) {
      reject(LocationException("E_GEOFENCE_LIMIT", "Android monitors at most $MAX_GEOFENCES geofences per app"))
      return
    }
    geofencing(context).addGeofences(geofencingRequest(region), geofencePendingIntent(context))
      .addOnSuccessListener(executor) {
        val geofences = LocationStore.geofences(context)
        val json = LocationJson.geofenceJson(region)
        if (region.expirationMs > 0) json.put("expiresAt", System.currentTimeMillis() + region.expirationMs)
        geofences.put(region.identifier, json)
        LocationStore.saveGeofences(context, geofences)
        LocationStore.setGeofenceState(context, region.identifier, null)
        resolve()
      }
      .addOnFailureListener(executor) { reject(mapError(it, geofence = true)) }
  }

  /** Re-registers stored geofences (after reboot or Play services data reset). */
  fun restoreGeofences(context: Context) {
    if (!playServicesAvailable(context) || !LocationPermissions.hasFine(context)) return
    val stored = LocationStore.geofences(context)
    val now = System.currentTimeMillis()
    val keys = stored.keys().asSequence().toList()
    for (key in keys) {
      val json = stored.optJSONObject(key) ?: continue
      val expiresAt = json.optDouble("expiresAt", 0.0)
      if (expiresAt > 0 && expiresAt <= now) {
        stored.remove(key)
        continue
      }
      var region = LocationJson.geofence(json)
      if (expiresAt > 0) region = region.copy(expirationMs = expiresAt - now)
      geofencing(context).addGeofences(geofencingRequest(region), geofencePendingIntent(context))
    }
    LocationStore.saveGeofences(context, stored)
  }

  fun removeGeofence(identifier: String, resolve: () -> Unit) {
    val context = context()
    val stored = LocationStore.geofences(context)
    stored.remove(identifier)
    LocationStore.saveGeofences(context, stored)
    LocationStore.setGeofenceState(context, identifier, null)
    if (!playServicesAvailable(context)) {
      resolve()
      return
    }
    geofencing(context).removeGeofences(listOf(identifier))
      .addOnCompleteListener(executor) { resolve() }
  }

  fun removeAllGeofences(resolve: () -> Unit) {
    val context = context()
    LocationStore.saveGeofences(context, JSONObject())
    LocationStore.setString(context, "geofence.states", null)
    if (!playServicesAvailable(context)) {
      resolve()
      return
    }
    geofencing(context).removeGeofences(geofencePendingIntent(context))
      .addOnCompleteListener(executor) { resolve() }
  }

  fun monitoredGeofences(): Array<GeofenceRegion> {
    val context = context()
    val stored = LocationStore.geofences(context)
    val now = System.currentTimeMillis()
    return stored.keys().asSequence()
      .mapNotNull { stored.optJSONObject(it) }
      .filter { json -> json.optDouble("expiresAt", 0.0).let { it <= 0 || it > now } }
      .map { LocationJson.geofence(it) }
      .sortedBy { it.identifier }
      .toList()
      .toTypedArray()
  }

  fun geofenceState(identifier: String, resolve: (GeofenceState) -> Unit) {
    val context = context()
    val json = LocationStore.geofences(context).optJSONObject(identifier)
    if (json == null) {
      resolve(GeofenceState.UNKNOWN)
      return
    }
    when (LocationStore.geofenceStates(context).optString(identifier)) {
      "inside" -> return resolve(GeofenceState.INSIDE)
      "outside" -> return resolve(GeofenceState.OUTSIDE)
    }
    // No transition seen yet: compare the last known fix with the circle.
    lastKnown(
      LastKnownPositionOptions(maximumAgeMs = 10 * 60_000.0, requiredAccuracy = 0.0),
      { location ->
        if (location == null) {
          resolve(GeofenceState.UNKNOWN)
        } else {
          val results = FloatArray(1)
          android.location.Location.distanceBetween(
            location.latitude, location.longitude,
            json.getDouble("latitude"), json.getDouble("longitude"), results
          )
          resolve(if (results[0] <= json.getDouble("radius")) GeofenceState.INSIDE else GeofenceState.OUTSIDE)
        }
      },
      { resolve(GeofenceState.UNKNOWN) }
    )
  }

  // ---------- Mock locations ----------

  fun setMockEnabled(enabled: Boolean, resolve: (Boolean) -> Unit, reject: (Throwable) -> Unit) {
    val context = context()
    val manager = locationManager(context)
    try {
      if (enabled) {
        try {
          manager.removeTestProvider(LocationManager.GPS_PROVIDER)
        } catch (_: Throwable) {
        }
        @Suppress("DEPRECATION")
        manager.addTestProvider(
          LocationManager.GPS_PROVIDER,
          false, true, false, false, true, true, true,
          android.location.Criteria.POWER_LOW,
          android.location.Criteria.ACCURACY_FINE
        )
        manager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true)
      } else {
        try {
          manager.removeTestProvider(LocationManager.GPS_PROVIDER)
        } catch (_: Throwable) {
        }
      }
    } catch (error: SecurityException) {
      reject(
        LocationException(
          "E_MOCK_LOCATION",
          "Select this app as the mock location app in Developer options (or adb shell appops set ${context.packageName} android:mock_location allow)"
        )
      )
      return
    } catch (error: Throwable) {
      reject(LocationException("E_MOCK_LOCATION", error.message ?: "Unable to change the test provider"))
      return
    }
    if (!playServicesAvailable(context)) {
      resolve(enabled)
      return
    }
    fused(context).setMockMode(enabled)
      .addOnSuccessListener(executor) { resolve(enabled) }
      .addOnFailureListener(executor) { error ->
        reject(LocationException("E_MOCK_LOCATION", error.message ?: "Fused mock mode refused"))
      }
  }

  fun setMock(mock: MockLocation, resolve: () -> Unit, reject: (Throwable) -> Unit) {
    val context = context()
    val location = android.location.Location(LocationManager.GPS_PROVIDER).apply {
      latitude = mock.latitude
      longitude = mock.longitude
      altitude = mock.altitude
      accuracy = mock.accuracy.toFloat()
      speed = mock.speed.toFloat()
      bearing = mock.bearing.toFloat()
      time = System.currentTimeMillis()
      elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
      if (Build.VERSION.SDK_INT >= 26) {
        verticalAccuracyMeters = mock.accuracy.toFloat()
        speedAccuracyMetersPerSecond = 0.5f
        bearingAccuracyDegrees = 1f
      }
    }
    try {
      locationManager(context).setTestProviderLocation(LocationManager.GPS_PROVIDER, location)
    } catch (error: Throwable) {
      if (!playServicesAvailable(context)) {
        reject(LocationException("E_MOCK_LOCATION", "Call setMockLocationEnabled(true) first: ${error.message}"))
        return
      }
    }
    if (!playServicesAvailable(context)) {
      resolve()
      return
    }
    fused(context).setMockLocation(location)
      .addOnSuccessListener(executor) { resolve() }
      .addOnFailureListener(executor) { error ->
        reject(LocationException("E_MOCK_LOCATION", error.message ?: "Fused mock location refused"))
      }
  }

  // ---------- Errors ----------

  fun mapError(error: Throwable, geofence: Boolean = false): Throwable {
    if (error is LocationException) return error
    if (error is SecurityException) {
      return LocationException("E_LOCATION_PERMISSION_DENIED", error.message ?: "Location permission has not been granted")
    }
    val status = (error as? ApiException)?.statusCode
    if (geofence && status != null) {
      val message = when (status) {
        1000 -> "Geofence service is not available (location turned off or Play services restricted)"
        1001 -> "Too many geofences (100 per app)"
        1002 -> "Too many PendingIntents"
        1004 -> "Insufficient location permission (fine + background required)"
        1005 -> "Too many geofence requests in a short time"
        else -> error.message ?: "Geofencing failed"
      }
      return LocationException(if (status == 1001) "E_GEOFENCE_LIMIT" else "E_GEOFENCE", "$message ($status)")
    }
    return LocationException("E_LOCATION_ERROR", error.message ?: error.toString())
  }

  fun errorBody(error: Throwable): Map<String, Any?> {
    val message = error.message ?: error.toString()
    val match = Regex("^(E_[A-Z_]+): (.*)$", RegexOption.DOT_MATCHES_ALL).find(message)
    return if (match != null) {
      mapOf("code" to match.groupValues[1], "message" to match.groupValues[2])
    } else {
      mapOf("code" to "E_LOCATION_ERROR", "message" to message)
    }
  }
}
