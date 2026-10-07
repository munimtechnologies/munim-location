package com.munimlocation

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import com.google.android.gms.location.DeviceOrientation
import com.google.android.gms.location.DeviceOrientationListener
import com.google.android.gms.location.DeviceOrientationRequest
import com.google.android.gms.location.LocationServices
import com.margelo.nitro.munimlocation.Heading
import com.margelo.nitro.munimlocation.HeadingOptions
import com.margelo.nitro.munimlocation.HeadingOrientation
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/** Compass (rotation-vector sensor or FusedOrientationProviderClient) and barometer. */
@SuppressLint("MissingPermission")
object LocationSensors {
  private var headingOptions: HeadingOptions? = null
  private var sensorListener: SensorEventListener? = null
  private var fusedListener: DeviceOrientationListener? = null
  private var lastEmitted: Double? = null
  private var declination: Float? = null
  private var declinationAt = 0L
  private val waiters = mutableListOf<(Heading) -> Unit>()
  private var userRequested = false
  private val lock = Any()

  private var pressureListener: SensorEventListener? = null
  private var basePressureAltitude: Double? = null

  private fun sensorManager(context: Context) =
    context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

  fun headingAvailable(context: Context): Boolean {
    val manager = sensorManager(context)
    return manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) != null ||
      manager.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR) != null ||
      (manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null &&
        manager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD) != null)
  }

  fun fusedOrientationAvailable(context: Context): Boolean =
    Build.VERSION.SDK_INT >= 33 && LocationEngine.playServicesAvailable(context)

  fun altimeterAvailable(context: Context): Boolean =
    sensorManager(context).getDefaultSensor(Sensor.TYPE_PRESSURE) != null

  // ---------- Heading ----------

  fun start(context: Context, options: HeadingOptions) {
    synchronized(lock) {
      userRequested = true
      headingOptions = options
      ensureRunning(context, options)
    }
  }

  fun stop(context: Context) {
    synchronized(lock) {
      userRequested = false
      if (waiters.isEmpty()) stopListening(context)
    }
  }

  fun current(context: Context, timeoutMs: Double, resolve: (Heading) -> Unit, reject: (Throwable) -> Unit) {
    if (!headingAvailable(context)) {
      reject(LocationException("E_UNSUPPORTED", "This device has no compass sensors"))
      return
    }
    val done = AtomicBoolean(false)
    synchronized(lock) {
      waiters.add { heading -> if (done.compareAndSet(false, true)) resolve(heading) }
      ensureRunning(
        context,
        headingOptions ?: HeadingOptions(0.0, HeadingOrientation.PORTRAIT, true, true, 100.0)
      )
    }
    LocationEngine.handler.postDelayed({
      if (done.compareAndSet(false, true)) {
        synchronized(lock) {
          waiters.clear()
          if (!userRequested) stopListening(context)
        }
        reject(LocationException("E_LOCATION_TIMEOUT", "Timed out waiting for a heading"))
      }
    }, timeoutMs.toLong().coerceAtLeast(500))
  }

  private fun ensureRunning(context: Context, options: HeadingOptions) {
    stopListening(context)
    lastEmitted = null
    if (options.useFusedOrientation && fusedOrientationAvailable(context)) {
      try {
        startFused(context, options)
        return
      } catch (_: Throwable) {
        // Fall back to the platform sensors.
      }
    }
    startSensors(context, options)
  }

  private fun startFused(context: Context, options: HeadingOptions) {
    val listener = DeviceOrientationListener { orientation: DeviceOrientation ->
      // headingDegrees is relative to true north per the Fused Orientation Provider.
      val trueHeading = orientation.headingDegrees.toDouble()
      val decl = declination(context)
      val magnetic = if (decl != null) normalize(trueHeading - decl) else trueHeading
      deliver(
        Heading(
          magneticHeading = magnetic,
          trueHeading = trueHeading,
          headingAccuracy = orientation.headingErrorDegrees.toDouble(),
          x = null,
          y = null,
          z = null,
          timestamp = System.currentTimeMillis().toDouble(),
          source = "fusedOrientation"
        )
      )
    }
    val request = DeviceOrientationRequest.Builder(options.samplingPeriodMs.toLong().coerceAtLeast(10) * 1000).build()
    LocationServices.getFusedOrientationProviderClient(context)
      .requestOrientationUpdates(request, LocationEngine.executor, listener)
    fusedListener = listener
  }

  private fun startSensors(context: Context, options: HeadingOptions) {
    val manager = sensorManager(context)
    val rotation = manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
      ?: manager.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)
    val period = (options.samplingPeriodMs * 1000).toInt().coerceAtLeast(SensorManager.SENSOR_DELAY_FASTEST)
    if (rotation != null) {
      val source = if (rotation.type == Sensor.TYPE_ROTATION_VECTOR) "rotationVector" else "geomagneticRotationVector"
      val listener = object : SensorEventListener {
        private var accuracyStatus = SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM

        override fun onSensorChanged(event: SensorEvent) {
          val matrix = FloatArray(9)
          SensorManager.getRotationMatrixFromVector(matrix, event.values)
          val reported = if (event.values.size > 4 && event.values[4] >= 0) Math.toDegrees(event.values[4].toDouble()) else -1.0
          emitFromMatrix(context, matrix, options.orientation, source, reported, accuracyStatus)
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
          accuracyStatus = accuracy
          if (accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE && userRequested) {
            LocationEvents.emit("headingCalibrationNeeded", mapOf("timestamp" to System.currentTimeMillis().toDouble()))
          }
        }
      }
      manager.registerListener(listener, rotation, period, LocationEngine.handler)
      sensorListener = listener
      return
    }
    val accelerometer = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
    val magnetometer = manager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD) ?: return
    val listener = object : SensorEventListener {
      private val gravity = FloatArray(3)
      private val geomagnetic = FloatArray(3)
      private var hasGravity = false
      private var hasMagnetic = false
      private var accuracyStatus = SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM

      override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
          System.arraycopy(event.values, 0, gravity, 0, 3)
          hasGravity = true
        } else {
          System.arraycopy(event.values, 0, geomagnetic, 0, 3)
          hasMagnetic = true
        }
        if (!hasGravity || !hasMagnetic) return
        val matrix = FloatArray(9)
        if (SensorManager.getRotationMatrix(matrix, null, gravity, geomagnetic)) {
          emitFromMatrix(context, matrix, options.orientation, "accelerometerMagnetometer", -1.0, accuracyStatus)
        }
      }

      override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        if (sensor.type == Sensor.TYPE_MAGNETIC_FIELD) accuracyStatus = accuracy
      }
    }
    manager.registerListener(listener, accelerometer, period, LocationEngine.handler)
    manager.registerListener(listener, magnetometer, period, LocationEngine.handler)
    sensorListener = listener
  }

  private fun emitFromMatrix(
    context: Context,
    matrix: FloatArray,
    orientation: HeadingOrientation,
    source: String,
    reportedAccuracy: Double,
    accuracyStatus: Int
  ) {
    val remapped = FloatArray(9)
    val (axisX, axisY) = when (orientation) {
      HeadingOrientation.LANDSCAPELEFT -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
      HeadingOrientation.LANDSCAPERIGHT -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
      HeadingOrientation.PORTRAITUPSIDEDOWN -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
      else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
    }
    SensorManager.remapCoordinateSystem(matrix, axisX, axisY, remapped)
    val values = FloatArray(3)
    SensorManager.getOrientation(remapped, values)
    val magnetic = normalize(Math.toDegrees(values[0].toDouble()))
    val decl = declination(context)
    val accuracy = when {
      reportedAccuracy >= 0 -> reportedAccuracy
      accuracyStatus == SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> 10.0
      accuracyStatus == SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> 20.0
      accuracyStatus == SensorManager.SENSOR_STATUS_ACCURACY_LOW -> 40.0
      else -> -1.0
    }
    deliver(
      Heading(
        magneticHeading = magnetic,
        trueHeading = if (decl != null) normalize(magnetic + decl) else -1.0,
        headingAccuracy = accuracy,
        x = null,
        y = null,
        z = null,
        timestamp = System.currentTimeMillis().toDouble(),
        source = source
      )
    )
  }

  private fun deliver(heading: Heading) {
    val pending: List<(Heading) -> Unit>
    val emit: Boolean
    synchronized(lock) {
      pending = waiters.toList()
      waiters.clear()
      val filter = headingOptions?.headingFilter ?: 0.0
      val previous = lastEmitted
      emit = userRequested && (previous == null || filter <= 0 || angularDistance(previous, heading.magneticHeading) >= filter)
      if (emit) lastEmitted = heading.magneticHeading
      if (pending.isNotEmpty() && !userRequested) stopListening(LocationEngine.context())
    }
    pending.forEach { it(heading) }
    if (emit) LocationEvents.emit("heading", LocationJson.map(heading))
  }

  private fun stopListening(context: Context) {
    sensorListener?.let { sensorManager(context).unregisterListener(it) }
    sensorListener = null
    fusedListener?.let {
      try {
        LocationServices.getFusedOrientationProviderClient(context).removeOrientationUpdates(it)
      } catch (_: Throwable) {
      }
    }
    fusedListener = null
  }

  /** Magnetic declination at the last known fix, refreshed every 10 minutes. */
  private fun declination(context: Context): Float? {
    val now = System.currentTimeMillis()
    if (declination != null && now - declinationAt < 10 * 60_000) return declination
    declinationAt = now
    try {
      if (!LocationPermissions.hasForeground(context)) return declination
      val manager = LocationEngine.locationManager(context)
      val location = manager.getProviders(true).mapNotNull { manager.getLastKnownLocation(it) }.maxByOrNull { it.time }
        ?: return declination
      declination = GeomagneticField(
        location.latitude.toFloat(),
        location.longitude.toFloat(),
        location.altitude.toFloat(),
        now
      ).declination
    } catch (_: Throwable) {
    }
    return declination
  }

  private fun normalize(degrees: Double): Double = ((degrees % 360) + 360) % 360

  private fun angularDistance(a: Double, b: Double): Double {
    val difference = abs(a - b) % 360
    return if (difference > 180) 360 - difference else difference
  }

  // ---------- Barometer ----------

  fun startAltitude(context: Context) {
    stopAltitude(context)
    val manager = sensorManager(context)
    val sensor = manager.getDefaultSensor(Sensor.TYPE_PRESSURE)
    if (sensor == null) {
      LocationEvents.emit("altitudeError", mapOf("code" to "E_UNSUPPORTED", "message" to "This device has no barometer"))
      return
    }
    basePressureAltitude = null
    val listener = object : SensorEventListener {
      override fun onSensorChanged(event: SensorEvent) {
        val hectopascals = event.values[0]
        val altitude = SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, hectopascals).toDouble()
        val base = basePressureAltitude ?: altitude.also { basePressureAltitude = it }
        LocationEvents.emit(
          "altitude",
          mapOf(
            "kind" to "pressure",
            "pressure" to hectopascals / 10.0,
            "altitude" to altitude,
            "relativeAltitude" to altitude - base,
            "timestamp" to System.currentTimeMillis().toDouble()
          )
        )
      }

      override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
    }
    manager.registerListener(listener, sensor, 200_000, LocationEngine.handler)
    pressureListener = listener
  }

  fun stopAltitude(context: Context) {
    pressureListener?.let { sensorManager(context).unregisterListener(it) }
    pressureListener = null
  }
}
