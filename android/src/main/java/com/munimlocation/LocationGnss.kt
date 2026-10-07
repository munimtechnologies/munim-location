package com.munimlocation

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssMeasurementsEvent
import android.location.GnssNavigationMessage
import android.location.GnssStatus
import android.location.OnNmeaMessageListener
import android.os.Build
import com.margelo.nitro.munimlocation.GnssInfo
import com.margelo.nitro.munimlocation.GnssOptions

/** Android GNSS status, NMEA, and opt-in raw measurements / navigation messages. */
@SuppressLint("MissingPermission")
object LocationGnss {
  private var statusCallback: GnssStatus.Callback? = null
  private var nmeaListener: OnNmeaMessageListener? = null
  private var measurementsCallback: GnssMeasurementsEvent.Callback? = null
  private var navigationCallback: GnssNavigationMessage.Callback? = null

  fun hasGps(context: Context): Boolean =
    context.packageManager.hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS)

  private fun constellation(type: Int): String = when (type) {
    GnssStatus.CONSTELLATION_GPS -> "gps"
    GnssStatus.CONSTELLATION_SBAS -> "sbas"
    GnssStatus.CONSTELLATION_GLONASS -> "glonass"
    GnssStatus.CONSTELLATION_QZSS -> "qzss"
    GnssStatus.CONSTELLATION_BEIDOU -> "beidou"
    GnssStatus.CONSTELLATION_GALILEO -> "galileo"
    GnssStatus.CONSTELLATION_IRNSS -> "irnss"
    else -> "unknown"
  }

  private inline fun capability(block: () -> Boolean): Boolean = try {
    block()
  } catch (_: Throwable) {
    false
  }

  fun info(context: Context): GnssInfo {
    val manager = LocationEngine.locationManager(context)
    var model: String? = null
    var year: Double? = null
    if (Build.VERSION.SDK_INT >= 28) {
      model = try {
        manager.gnssHardwareModelName
      } catch (_: Throwable) {
        null
      }
      year = try {
        manager.gnssYearOfHardware.toDouble()
      } catch (_: Throwable) {
        null
      }
    }
    if (Build.VERSION.SDK_INT >= 31) {
      val caps = manager.gnssCapabilities
      return GnssInfo(
        supported = hasGps(context),
        hardwareModelName = model,
        yearOfHardware = year,
        hasMeasurements = capability { caps.hasMeasurements() },
        hasNavigationMessages = capability { caps.hasNavigationMessages() },
        hasAntennaInfo = capability { caps.hasAntennaInfo() },
        hasSatelliteBlocklist = capability { caps.hasSatelliteBlocklist() },
        hasMeasurementCorrections = capability { caps.hasMeasurementCorrections() },
        hasLowPowerMode = capability { caps.hasLowPowerMode() },
        hasSatellitePvt = Build.VERSION.SDK_INT >= 33 && capability { caps.hasSatellitePvt() },
        hasAccumulatedDeltaRange = Build.VERSION.SDK_INT >= 34 &&
          capability { caps.hasAccumulatedDeltaRange() == android.location.GnssCapabilities.CAPABILITY_SUPPORTED }
      )
    }
    val gps = hasGps(context)
    return GnssInfo(
      supported = gps,
      hardwareModelName = model,
      yearOfHardware = year,
      hasMeasurements = gps,
      hasNavigationMessages = gps,
      hasAntennaInfo = false,
      hasSatelliteBlocklist = false,
      hasMeasurementCorrections = false,
      hasLowPowerMode = false,
      hasSatellitePvt = false,
      hasAccumulatedDeltaRange = false
    )
  }

  fun start(context: Context, options: GnssOptions) {
    if (!hasGps(context)) throw LocationException("E_UNSUPPORTED", "This device has no GNSS receiver")
    if (!LocationPermissions.hasFine(context)) {
      throw LocationException("E_LOCATION_PERMISSION_DENIED", "GNSS data needs ACCESS_FINE_LOCATION")
    }
    stop(context)
    val manager = LocationEngine.locationManager(context)
    val handler = LocationEngine.handler

    if (options.status) {
      val callback = object : GnssStatus.Callback() {
        override fun onStarted() = LocationEvents.emit("gnssStarted", emptyMap())
        override fun onStopped() = LocationEvents.emit("gnssStopped", emptyMap())
        override fun onFirstFix(ttffMillis: Int) =
          LocationEvents.emit("gnssFirstFix", mapOf("ttffMs" to ttffMillis))

        override fun onSatelliteStatusChanged(status: GnssStatus) {
          var used = 0
          val satellites = (0 until status.satelliteCount).map { index ->
            if (status.usedInFix(index)) used++
            val satellite = mutableMapOf<String, Any?>(
              "svid" to status.getSvid(index),
              "constellation" to constellation(status.getConstellationType(index)),
              "cn0DbHz" to status.getCn0DbHz(index).toDouble(),
              "elevationDegrees" to status.getElevationDegrees(index).toDouble(),
              "azimuthDegrees" to status.getAzimuthDegrees(index).toDouble(),
              "usedInFix" to status.usedInFix(index),
              "hasAlmanac" to status.hasAlmanacData(index),
              "hasEphemeris" to status.hasEphemerisData(index)
            )
            if (Build.VERSION.SDK_INT >= 26 && status.hasCarrierFrequencyHz(index)) {
              satellite["carrierFrequencyHz"] = status.getCarrierFrequencyHz(index).toDouble()
            }
            if (Build.VERSION.SDK_INT >= 30 && status.hasBasebandCn0DbHz(index)) {
              satellite["basebandCn0DbHz"] = status.getBasebandCn0DbHz(index).toDouble()
            }
            satellite
          }
          LocationEvents.emit(
            "gnssStatus",
            mapOf(
              "satellites" to satellites,
              "satelliteCount" to status.satelliteCount,
              "usedInFixCount" to used,
              "timestamp" to System.currentTimeMillis().toDouble()
            )
          )
        }
      }
      if (Build.VERSION.SDK_INT >= 30) {
        manager.registerGnssStatusCallback(LocationEngine.executor, callback)
      } else {
        @Suppress("DEPRECATION")
        manager.registerGnssStatusCallback(callback, handler)
      }
      statusCallback = callback
    }

    if (options.nmea) {
      val listener = OnNmeaMessageListener { message, timestamp ->
        LocationEvents.emit("nmea", mapOf("message" to message.trim(), "timestamp" to timestamp.toDouble()))
      }
      if (Build.VERSION.SDK_INT >= 30) {
        manager.addNmeaListener(LocationEngine.executor, listener)
      } else {
        @Suppress("DEPRECATION")
        manager.addNmeaListener(listener, handler)
      }
      nmeaListener = listener
    }

    if (options.measurements) {
      val callback = object : GnssMeasurementsEvent.Callback() {
        override fun onGnssMeasurementsReceived(event: GnssMeasurementsEvent) {
          val clock = event.clock
          val clockMap = mutableMapOf<String, Any?>(
            "timeNanos" to clock.timeNanos.toDouble(),
            "biasNanos" to (if (clock.hasBiasNanos()) clock.biasNanos else null),
            "fullBiasNanos" to (if (clock.hasFullBiasNanos()) clock.fullBiasNanos.toDouble() else null),
            "driftNanosPerSecond" to (if (clock.hasDriftNanosPerSecond()) clock.driftNanosPerSecond else null),
            "hardwareClockDiscontinuityCount" to clock.hardwareClockDiscontinuityCount
          )
          val measurements = event.measurements.map { measurement ->
            val item = mutableMapOf<String, Any?>(
              "svid" to measurement.svid,
              "constellation" to constellation(measurement.constellationType),
              "cn0DbHz" to measurement.cn0DbHz,
              "timeOffsetNanos" to measurement.timeOffsetNanos,
              "state" to measurement.state,
              "receivedSvTimeNanos" to measurement.receivedSvTimeNanos.toDouble(),
              "receivedSvTimeUncertaintyNanos" to measurement.receivedSvTimeUncertaintyNanos.toDouble(),
              "pseudorangeRateMetersPerSecond" to measurement.pseudorangeRateMetersPerSecond,
              "pseudorangeRateUncertaintyMetersPerSecond" to measurement.pseudorangeRateUncertaintyMetersPerSecond,
              "accumulatedDeltaRangeState" to measurement.accumulatedDeltaRangeState,
              "accumulatedDeltaRangeMeters" to measurement.accumulatedDeltaRangeMeters,
              "accumulatedDeltaRangeUncertaintyMeters" to measurement.accumulatedDeltaRangeUncertaintyMeters,
              "multipathIndicator" to measurement.multipathIndicator
            )
            if (measurement.hasCarrierFrequencyHz()) item["carrierFrequencyHz"] = measurement.carrierFrequencyHz.toDouble()
            if (Build.VERSION.SDK_INT >= 30 && measurement.hasBasebandCn0DbHz()) {
              item["basebandCn0DbHz"] = measurement.basebandCn0DbHz
            }
            item
          }
          LocationEvents.emit("gnssMeasurements", mapOf("clock" to clockMap, "measurements" to measurements))
        }
      }
      if (Build.VERSION.SDK_INT >= 30) {
        manager.registerGnssMeasurementsCallback(LocationEngine.executor, callback)
      } else {
        @Suppress("DEPRECATION")
        manager.registerGnssMeasurementsCallback(callback, handler)
      }
      measurementsCallback = callback
    }

    if (options.navigationMessages) {
      val callback = object : GnssNavigationMessage.Callback() {
        override fun onGnssNavigationMessageReceived(message: GnssNavigationMessage) {
          LocationEvents.emit(
            "gnssNavigationMessage",
            mapOf(
              "svid" to message.svid,
              "type" to message.type,
              "status" to message.status,
              "messageId" to message.messageId,
              "submessageId" to message.submessageId,
              "data" to message.data.joinToString("") { "%02x".format(it) }
            )
          )
        }
      }
      if (Build.VERSION.SDK_INT >= 30) {
        manager.registerGnssNavigationMessageCallback(LocationEngine.executor, callback)
      } else {
        @Suppress("DEPRECATION")
        manager.registerGnssNavigationMessageCallback(callback, handler)
      }
      navigationCallback = callback
    }
  }

  fun stop(context: Context) {
    val manager = LocationEngine.locationManager(context)
    statusCallback?.let { manager.unregisterGnssStatusCallback(it) }
    nmeaListener?.let { manager.removeNmeaListener(it) }
    measurementsCallback?.let { manager.unregisterGnssMeasurementsCallback(it) }
    navigationCallback?.let { manager.unregisterGnssNavigationMessageCallback(it) }
    statusCallback = null
    nmeaListener = null
    measurementsCallback = null
    navigationCallback = null
  }
}
