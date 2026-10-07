package com.munimlocation

import android.content.Context
import android.location.Geocoder
import android.os.Build
import com.margelo.nitro.munimlocation.Address
import com.margelo.nitro.munimlocation.GeocodeOptions
import java.util.Locale

/** Android Geocoder, with the API 33 asynchronous listener when available. */
object LocationGeocoder {
  fun available(): Boolean = Geocoder.isPresent()

  private fun locale(options: GeocodeOptions): Locale =
    if (options.locale.isEmpty()) Locale.getDefault() else Locale.forLanguageTag(options.locale)

  private fun max(options: GeocodeOptions) = if (options.maxResults > 0) options.maxResults.toInt() else 5

  fun geocode(context: Context, query: String, options: GeocodeOptions, resolve: (Array<Address>) -> Unit, reject: (Throwable) -> Unit) {
    if (!available()) {
      reject(LocationException("E_UNSUPPORTED", "No geocoder backend is installed on this device"))
      return
    }
    val geocoder = Geocoder(context, locale(options))
    if (Build.VERSION.SDK_INT >= 33) {
      geocoder.getFromLocationName(query, max(options), object : Geocoder.GeocodeListener {
        override fun onGeocode(addresses: MutableList<android.location.Address>) {
          resolve(addresses.map { map(it) }.toTypedArray())
        }

        override fun onError(errorMessage: String?) {
          reject(LocationException("E_GEOCODE", errorMessage ?: "Geocoding failed"))
        }
      })
      return
    }
    LocationEngine.handler.post {
      try {
        @Suppress("DEPRECATION")
        val addresses = geocoder.getFromLocationName(query, max(options)) ?: emptyList()
        resolve(addresses.map { map(it) }.toTypedArray())
      } catch (error: Throwable) {
        reject(LocationException("E_GEOCODE", error.message ?: "Geocoding failed"))
      }
    }
  }

  fun reverse(
    context: Context,
    latitude: Double,
    longitude: Double,
    options: GeocodeOptions,
    resolve: (Array<Address>) -> Unit,
    reject: (Throwable) -> Unit
  ) {
    if (!available()) {
      reject(LocationException("E_UNSUPPORTED", "No geocoder backend is installed on this device"))
      return
    }
    val geocoder = Geocoder(context, locale(options))
    if (Build.VERSION.SDK_INT >= 33) {
      geocoder.getFromLocation(latitude, longitude, max(options), object : Geocoder.GeocodeListener {
        override fun onGeocode(addresses: MutableList<android.location.Address>) {
          resolve(addresses.map { map(it) }.toTypedArray())
        }

        override fun onError(errorMessage: String?) {
          reject(LocationException("E_GEOCODE", errorMessage ?: "Reverse geocoding failed"))
        }
      })
      return
    }
    LocationEngine.handler.post {
      try {
        @Suppress("DEPRECATION")
        val addresses = geocoder.getFromLocation(latitude, longitude, max(options)) ?: emptyList()
        resolve(addresses.map { map(it) }.toTypedArray())
      } catch (error: Throwable) {
        reject(LocationException("E_GEOCODE", error.message ?: "Reverse geocoding failed"))
      }
    }
  }

  private fun map(address: android.location.Address): Address {
    val lines = (0..address.maxAddressLineIndex).mapNotNull { address.getAddressLine(it) }
    return Address(
      latitude = if (address.hasLatitude()) address.latitude else null,
      longitude = if (address.hasLongitude()) address.longitude else null,
      name = address.featureName,
      streetNumber = address.subThoroughfare,
      street = address.thoroughfare,
      subLocality = address.subLocality,
      locality = address.locality,
      subAdministrativeArea = address.subAdminArea,
      administrativeArea = address.adminArea,
      postalCode = address.postalCode,
      country = address.countryName,
      isoCountryCode = address.countryCode,
      timeZone = null,
      formattedAddress = lines.joinToString(", ").ifEmpty { null },
      shortAddress = null,
      areasOfInterest = address.premises?.let { arrayOf(it) },
      inlandWater = null,
      ocean = null,
      phone = address.phone,
      url = address.url
    )
  }
}
