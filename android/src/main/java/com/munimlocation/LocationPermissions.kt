package com.munimlocation

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.facebook.react.modules.core.PermissionAwareActivity
import com.facebook.react.modules.core.PermissionListener
import com.margelo.nitro.NitroModules
import com.margelo.nitro.munimlocation.AccuracyAuthorization
import com.margelo.nitro.munimlocation.AndroidLocationPermission
import com.margelo.nitro.munimlocation.AuthorizationStatus
import com.margelo.nitro.munimlocation.PermissionStatus
import java.util.concurrent.atomic.AtomicInteger

object LocationPermissions {
  private val requestCodes = AtomicInteger(41_000)

  fun granted(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

  fun hasFine(context: Context) = granted(context, Manifest.permission.ACCESS_FINE_LOCATION)

  fun hasForeground(context: Context) =
    hasFine(context) || granted(context, Manifest.permission.ACCESS_COARSE_LOCATION)

  fun hasBackground(context: Context): Boolean =
    if (Build.VERSION.SDK_INT >= 29) {
      granted(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    } else {
      hasForeground(context)
    }

  fun servicesEnabled(context: Context): Boolean {
    val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
    return LocationManagerCompat.isLocationEnabled(manager)
  }

  fun declaredInManifest(context: Context, permission: String): Boolean = try {
    val info = if (Build.VERSION.SDK_INT >= 33) {
      context.packageManager.getPackageInfo(
        context.packageName,
        PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong())
      )
    } else {
      @Suppress("DEPRECATION")
      context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
    }
    info.requestedPermissions?.contains(permission) == true
  } catch (_: Throwable) {
    false
  }

  private fun rationale(activity: Activity?, permission: String): Boolean =
    activity?.shouldShowRequestPermissionRationale(permission) ?: false

  fun shouldShowRationale(permission: AndroidLocationPermission): Boolean {
    val activity = NitroModules.applicationContext?.currentActivity ?: return false
    val name = when (permission) {
      AndroidLocationPermission.FINE -> Manifest.permission.ACCESS_FINE_LOCATION
      AndroidLocationPermission.COARSE -> Manifest.permission.ACCESS_COARSE_LOCATION
      AndroidLocationPermission.BACKGROUND ->
        if (Build.VERSION.SDK_INT >= 29) Manifest.permission.ACCESS_BACKGROUND_LOCATION else return false
    }
    return rationale(activity, name)
  }

  fun status(context: Context): PermissionStatus {
    val fine = hasFine(context)
    val foreground = hasForeground(context)
    val background = foreground && hasBackground(context)
    val activity = NitroModules.applicationContext?.currentActivity
    val askedForeground = LocationStore.getBoolean(context, LocationStore.ASKED_FOREGROUND)
    val status = when {
      background -> AuthorizationStatus.ALWAYS
      foreground -> AuthorizationStatus.WHENINUSE
      askedForeground -> AuthorizationStatus.DENIED
      else -> AuthorizationStatus.NOTDETERMINED
    }
    val canAskAgain = when {
      !foreground -> !askedForeground ||
        rationale(activity, Manifest.permission.ACCESS_FINE_LOCATION) ||
        rationale(activity, Manifest.permission.ACCESS_COARSE_LOCATION)
      // Upgrades: approximate -> precise, and foreground -> background
      // (Android 11+ sends the user to the app's location settings page).
      !fine -> true
      !background && Build.VERSION.SDK_INT >= 29 -> true
      else -> false
    }
    return PermissionStatus(
      status = status,
      accuracy = if (fine) AccuracyAuthorization.FULL else AccuracyAuthorization.REDUCED,
      foreground = foreground,
      background = background,
      precise = fine,
      canAskAgain = canAskAgain,
      locationServicesEnabled = servicesEnabled(context)
    )
  }

  /**
   * Shows the system permission dialog through React Native's
   * PermissionAwareActivity and calls [onDone] after the user answers.
   */
  fun request(permissions: Array<String>, onDone: () -> Unit, onError: (Throwable) -> Unit) {
    val activity = NitroModules.applicationContext?.currentActivity
    val aware = activity as? PermissionAwareActivity
    if (aware == null) {
      onError(LocationException("E_NO_ACTIVITY", "Permission requests need a foreground React Native activity"))
      return
    }
    val code = requestCodes.incrementAndGet()
    activity.runOnUiThread {
      aware.requestPermissions(permissions, code, PermissionListener { requestCode, _, _ ->
        if (requestCode != code) return@PermissionListener false
        onDone()
        true
      })
    }
  }

  fun openAppSettings(context: Context): Boolean = try {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
      .setData(Uri.fromParts("package", context.packageName, null))
      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
    true
  } catch (_: Throwable) {
    false
  }

  fun openLocationSettings(context: Context): Boolean = try {
    context.startActivity(
      Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
    true
  } catch (_: Throwable) {
    false
  }
}

/** Errors surface in JavaScript as `CODE: message` (see toLocationError). */
class LocationException(code: String, message: String) : Exception("$code: $message")
