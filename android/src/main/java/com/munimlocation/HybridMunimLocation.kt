package com.munimlocation

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Geocoder
import android.location.LocationManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.facebook.react.bridge.LifecycleEventListener
import com.margelo.nitro.NitroModules
import com.margelo.nitro.core.Promise
import com.margelo.nitro.munimlocation.AccuracyAuthorization
import com.margelo.nitro.munimlocation.Address
import com.margelo.nitro.munimlocation.AndroidBackgroundOptions
import com.margelo.nitro.munimlocation.AndroidLocationPermission
import com.margelo.nitro.munimlocation.BackgroundStatus
import com.margelo.nitro.munimlocation.BeaconConstraint
import com.margelo.nitro.munimlocation.CurrentPositionOptions
import com.margelo.nitro.munimlocation.GeocodeOptions
import com.margelo.nitro.munimlocation.GeofenceRegion
import com.margelo.nitro.munimlocation.GeofenceState
import com.margelo.nitro.munimlocation.GnssInfo
import com.margelo.nitro.munimlocation.GnssOptions
import com.margelo.nitro.munimlocation.Heading
import com.margelo.nitro.munimlocation.HeadingOptions
import com.margelo.nitro.munimlocation.HybridMunimLocationSpec
import com.margelo.nitro.munimlocation.LastKnownPositionOptions
import com.margelo.nitro.munimlocation.Location
import com.margelo.nitro.munimlocation.LocationCapabilities
import com.margelo.nitro.munimlocation.LocationPriority
import com.margelo.nitro.munimlocation.LocationSettingsResult
import com.margelo.nitro.munimlocation.MockLocation
import com.margelo.nitro.munimlocation.PermissionStatus
import com.margelo.nitro.munimlocation.ProviderStatus
import com.margelo.nitro.munimlocation.ServiceSessionAuthorization
import com.margelo.nitro.munimlocation.WatchOptions

class HybridMunimLocation : HybridMunimLocationSpec() {
  private val context: Context
    get() = LocationEngine.context()

  private var lastPermissionKey: String? = null
  private var providerReceiver: BroadcastReceiver? = null
  private var lifecycleListener: LifecycleEventListener? = null

  init {
    NitroModules.applicationContext?.let { LocationEngine.attach(it) }
  }

  private fun <T> promise(body: (resolve: (T) -> Unit, reject: (Throwable) -> Unit) -> Unit): Promise<T> {
    val promise = Promise<T>()
    try {
      body({ promise.resolve(it) }, { promise.reject(it) })
    } catch (error: Throwable) {
      promise.reject(LocationEngine.mapError(error))
    }
    return promise
  }

  private fun unsupported(what: String) =
    LocationException("E_UNSUPPORTED", "$what is not supported on Android")

  // ---------- Events ----------

  override fun setEventListener(listener: (name: String, payload: String) -> Unit) {
    LocationEvents.setListener(listener)
    registerSystemObservers()
  }

  override fun removeEventListener() {
    LocationEvents.removeListener()
  }

  override fun getPendingBackgroundEvents(): String = LocationEvents.pendingJson(context)

  override fun clearPendingBackgroundEvents() {
    LocationEvents.clearPending(context)
  }

  private fun permissionKey(status: PermissionStatus) =
    "${status.status}|${status.accuracy}|${status.locationServicesEnabled}"

  private fun emitPermissionIfChanged() {
    val status = LocationPermissions.status(context)
    val key = permissionKey(status)
    if (lastPermissionKey != null && lastPermissionKey != key) {
      LocationEvents.emit("authorizationChanged", permissionMap(status))
    }
    lastPermissionKey = key
  }

  private fun permissionMap(status: PermissionStatus): Map<String, Any?> = mapOf(
    "status" to statusString(status),
    "accuracy" to if (status.accuracy == AccuracyAuthorization.FULL) "full" else "reduced",
    "foreground" to status.foreground,
    "background" to status.background,
    "precise" to status.precise,
    "canAskAgain" to status.canAskAgain,
    "locationServicesEnabled" to status.locationServicesEnabled
  )

  private fun statusString(status: PermissionStatus) = when (status.status.name) {
    "NOTDETERMINED" -> "notDetermined"
    "WHENINUSE" -> "whenInUse"
    "ALWAYS" -> "always"
    "RESTRICTED" -> "restricted"
    else -> "denied"
  }

  /** Provider / airplane-mode broadcasts and permission changes on resume. */
  private fun registerSystemObservers() {
    if (providerReceiver == null) {
      val receiver = object : BroadcastReceiver() {
        override fun onReceive(receiverContext: Context, intent: Intent) {
          when (intent.action) {
            LocationManager.PROVIDERS_CHANGED_ACTION, LocationManager.MODE_CHANGED_ACTION -> {
              val status = LocationEngine.providerStatus(receiverContext)
              LocationEvents.emit(
                "providerChanged",
                mapOf(
                  "locationServicesEnabled" to status.locationServicesEnabled,
                  "gpsEnabled" to status.gpsEnabled,
                  "networkEnabled" to status.networkEnabled
                )
              )
              emitPermissionIfChanged()
            }
            Intent.ACTION_AIRPLANE_MODE_CHANGED -> LocationEvents.emit(
              "airplaneModeChanged",
              mapOf(
                "airplaneModeOn" to (Settings.Global.getInt(receiverContext.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1)
              )
            )
          }
        }
      }
      val filter = IntentFilter().apply {
        addAction(LocationManager.PROVIDERS_CHANGED_ACTION)
        addAction(LocationManager.MODE_CHANGED_ACTION)
        addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
      }
      try {
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        providerReceiver = receiver
      } catch (_: Throwable) {
      }
    }
    if (lifecycleListener == null) {
      val reactContext = NitroModules.applicationContext ?: return
      val listener = object : LifecycleEventListener {
        override fun onHostResume() = emitPermissionIfChanged()
        override fun onHostPause() {}
        override fun onHostDestroy() {}
      }
      reactContext.addLifecycleEventListener(listener)
      lifecycleListener = listener
    }
    lastPermissionKey = permissionKey(LocationPermissions.status(context))
  }

  // ---------- Permissions ----------

  override fun getPermissionStatus(): Promise<PermissionStatus> =
    Promise.resolved(LocationPermissions.status(context))

  override fun requestForegroundPermission(precise: Boolean): Promise<PermissionStatus> = promise { resolve, reject ->
    val permissions = if (precise) {
      arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    } else {
      arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION)
    }
    val current = LocationPermissions.status(context)
    if ((precise && current.precise) || (!precise && current.foreground)) {
      resolve(current)
      return@promise
    }
    LocationPermissions.request(permissions, {
      LocationStore.setBoolean(context, LocationStore.ASKED_FOREGROUND, true)
      emitPermissionIfChanged()
      resolve(LocationPermissions.status(context))
    }, reject)
  }

  override fun requestBackgroundPermission(): Promise<PermissionStatus> = promise { resolve, reject ->
    val current = LocationPermissions.status(context)
    if (Build.VERSION.SDK_INT < 29 || current.background || !current.foreground) {
      // Below Android 10 background is implied; without foreground access
      // Android ignores the background request.
      resolve(current)
      return@promise
    }
    LocationPermissions.request(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), {
      LocationStore.setBoolean(context, LocationStore.ASKED_BACKGROUND, true)
      emitPermissionIfChanged()
      resolve(LocationPermissions.status(context))
    }, reject)
  }

  override fun requestTemporaryFullAccuracy(purposeKey: String): Promise<AccuracyAuthorization> =
    Promise.resolved(if (LocationPermissions.hasFine(context)) AccuracyAuthorization.FULL else AccuracyAuthorization.REDUCED)

  override fun shouldShowRationale(permission: AndroidLocationPermission): Boolean =
    LocationPermissions.shouldShowRationale(permission)

  override fun openAppSettings(): Promise<Boolean> = Promise.resolved(LocationPermissions.openAppSettings(context))

  override fun openLocationSettings(): Promise<Boolean> = Promise.resolved(LocationPermissions.openLocationSettings(context))

  // ---------- Services ----------

  override fun isLocationServicesEnabled(): Promise<Boolean> =
    Promise.resolved(LocationPermissions.servicesEnabled(context))

  override fun getProviderStatus(): Promise<ProviderStatus> = Promise.resolved(LocationEngine.providerStatus(context))

  override fun checkLocationSettings(priority: LocationPriority, needBle: Boolean): Promise<LocationSettingsResult> =
    promise { resolve, _ -> LocationEngine.checkSettings(priority, needBle, resolve) }

  override fun requestLocationSettingsResolution(priority: LocationPriority, needBle: Boolean): Promise<Boolean> =
    promise { resolve, reject -> LocationEngine.resolveSettings(priority, needBle, resolve, reject) }

  // iOS-only session APIs: '' marks them unsupported.
  override fun startServiceSession(authorization: ServiceSessionAuthorization, fullAccuracyPurposeKey: String): String = ""

  override fun stopServiceSession(sessionId: String) {}

  override fun startBackgroundActivitySession(): String = ""

  override fun stopBackgroundActivitySession(sessionId: String) {}

  // ---------- Positions ----------

  override fun getCurrentPosition(options: CurrentPositionOptions): Promise<Location> =
    promise { resolve, reject -> LocationEngine.currentPosition(options, resolve, reject) }

  override fun getLastKnownPosition(options: LastKnownPositionOptions): Promise<Location?> =
    promise { resolve, reject -> LocationEngine.lastKnown(options, resolve, reject) }

  override fun watchPosition(options: WatchOptions): Double = LocationEngine.watch(options).toDouble()

  override fun clearWatch(watchId: Double) = LocationEngine.clearWatch(watchId.toInt())

  override fun clearAllWatches() = LocationEngine.clearAllWatches()

  // ---------- Background ----------

  override fun startBackgroundUpdates(options: WatchOptions, android: AndroidBackgroundOptions): Promise<Boolean> =
    promise { resolve, _ ->
      LocationEngine.startBackground(options, android)
      resolve(true)
    }

  override fun stopBackgroundUpdates(): Promise<Unit> = promise { resolve, _ ->
    LocationEngine.stopBackground()
    resolve(Unit)
  }

  override fun startSignificantLocationChanges(): Promise<Boolean> = promise { resolve, _ ->
    LocationEngine.startSignificantChanges()
    resolve(true)
  }

  override fun stopSignificantLocationChanges() = LocationEngine.stopSignificantChanges()

  override fun startVisitMonitoring(): Promise<Boolean> =
    Promise.rejected(unsupported("Visit monitoring (CLVisit)"))

  override fun stopVisitMonitoring() {}

  override fun getBackgroundStatus(): BackgroundStatus {
    val running = LocationStore.getBoolean(context, LocationStore.BACKGROUND_RUNNING)
    val mode = LocationStore.backgroundOptions(context)?.optString("mode")
    return BackgroundStatus(
      running = running,
      mode = if (!running) "" else if (mode == "PENDINGINTENT") "pendingIntent" else "foregroundService",
      significantChanges = LocationStore.getBoolean(context, LocationStore.SIGNIFICANT_RUNNING),
      visits = false,
      geofenceCount = LocationStore.geofences(context).length().toDouble(),
      pendingEventCount = LocationEvents.pendingCount(context).toDouble()
    )
  }

  // ---------- Geofencing ----------

  override fun addGeofence(region: GeofenceRegion): Promise<Unit> =
    promise { resolve, reject -> LocationEngine.addGeofence(region, { resolve(Unit) }, reject) }

  override fun removeGeofence(identifier: String): Promise<Unit> =
    promise { resolve, _ -> LocationEngine.removeGeofence(identifier) { resolve(Unit) } }

  override fun removeAllGeofences(): Promise<Unit> =
    promise { resolve, _ -> LocationEngine.removeAllGeofences { resolve(Unit) } }

  override fun getMonitoredGeofences(): Promise<Array<GeofenceRegion>> =
    Promise.resolved(LocationEngine.monitoredGeofences())

  override fun requestGeofenceState(identifier: String): Promise<GeofenceState> =
    promise { resolve, _ -> LocationEngine.geofenceState(identifier, resolve) }

  override fun getMaxMonitoredGeofences(): Double = 100.0

  // ---------- Beacons (iOS only) ----------

  override fun startBeaconRanging(constraint: BeaconConstraint) {
    LocationEvents.emit(
      "beaconRangingError",
      mapOf(
        "identifier" to constraint.identifier,
        "code" to "E_UNSUPPORTED",
        "message" to "iBeacon ranging is iOS-only; scan BLE advertisements (for example with munim-bluetooth) on Android"
      )
    )
  }

  override fun stopBeaconRanging(identifier: String) {}

  override fun startBeaconMonitoring(constraint: BeaconConstraint, notifyEntryStateOnDisplay: Boolean): Promise<Unit> =
    Promise.rejected(unsupported("iBeacon region monitoring"))

  override fun stopBeaconMonitoring(identifier: String) {}

  // ---------- Heading and altitude ----------

  override fun startHeadingUpdates(options: HeadingOptions) = LocationSensors.start(context, options)

  override fun stopHeadingUpdates() = LocationSensors.stop(context)

  override fun getCurrentHeading(timeoutMs: Double): Promise<Heading> =
    promise { resolve, reject -> LocationSensors.current(context, timeoutMs, resolve, reject) }

  override fun dismissHeadingCalibrationDisplay() {}

  override fun startAltitudeUpdates(absolute: Boolean) = LocationSensors.startAltitude(context)

  override fun stopAltitudeUpdates() = LocationSensors.stopAltitude(context)

  // ---------- GNSS ----------

  override fun getGnssInfo(): Promise<GnssInfo> = promise { resolve, _ -> resolve(LocationGnss.info(context)) }

  override fun startGnssUpdates(options: GnssOptions): Promise<Unit> = promise { resolve, _ ->
    LocationGnss.start(context, options)
    resolve(Unit)
  }

  override fun stopGnssUpdates() = LocationGnss.stop(context)

  // ---------- Geocoding ----------

  override fun isGeocoderAvailable(): Boolean = Geocoder.isPresent()

  override fun geocode(address: String, options: GeocodeOptions): Promise<Array<Address>> =
    promise { resolve, reject -> LocationGeocoder.geocode(context, address, options, resolve, reject) }

  override fun reverseGeocode(latitude: Double, longitude: Double, options: GeocodeOptions): Promise<Array<Address>> =
    promise { resolve, reject -> LocationGeocoder.reverse(context, latitude, longitude, options, resolve, reject) }

  // ---------- Mock locations ----------

  override fun setMockLocationEnabled(enabled: Boolean): Promise<Boolean> =
    promise { resolve, reject -> LocationEngine.setMockEnabled(enabled, resolve, reject) }

  override fun setMockLocation(location: MockLocation): Promise<Unit> =
    promise { resolve, reject -> LocationEngine.setMock(location, { resolve(Unit) }, reject) }

  // ---------- Utilities ----------

  override fun isLocationAvailable(): Promise<Boolean> =
    promise { resolve, _ -> LocationEngine.isLocationAvailable(resolve) }

  override fun getCapabilities(): Promise<LocationCapabilities> = promise { resolve, _ ->
    val context = context
    val play = LocationEngine.playServicesAvailable(context)
    val gnss = LocationGnss.info(context)
    val gps = LocationGnss.hasGps(context)
    resolve(
      LocationCapabilities(
        platform = "android",
        osVersion = Build.VERSION.RELEASE ?: Build.VERSION.SDK_INT.toString(),
        locationServicesEnabled = LocationPermissions.servicesEnabled(context),
        headingAvailable = LocationSensors.headingAvailable(context),
        significantChangeAvailable = true,
        visitsAvailable = false,
        regionMonitoringAvailable = play,
        maxMonitoredRegions = 100.0,
        geofencingEngine = if (play) "geofencingClient" else "none",
        beaconRangingAvailable = false,
        beaconMonitoringAvailable = false,
        liveUpdatesAvailable = false,
        serviceSessionAvailable = false,
        backgroundActivitySessionAvailable = false,
        temporaryFullAccuracyAvailable = false,
        altimeterAvailable = LocationSensors.altimeterAvailable(context),
        absoluteAltitudeAvailable = false,
        gnssStatusAvailable = gps,
        nmeaAvailable = gps,
        gnssMeasurementsAvailable = gnss.hasMeasurements,
        gnssNavigationMessagesAvailable = gnss.hasNavigationMessages,
        fusedLocationAvailable = play,
        fusedOrientationAvailable = LocationSensors.fusedOrientationAvailable(context),
        geocoderAvailable = Geocoder.isPresent(),
        mockLocationAvailable = true,
        backgroundLocationModeEnabled =
          LocationPermissions.declaredInManifest(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) ||
            LocationPermissions.declaredInManifest(context, "android.permission.FOREGROUND_SERVICE_LOCATION")
      )
    )
  }
}
