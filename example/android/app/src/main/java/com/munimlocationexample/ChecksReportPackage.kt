package com.munimlocationexample

import android.util.Log
import com.facebook.react.BaseReactPackage
import com.facebook.react.bridge.NativeModule
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.module.model.ReactModuleInfo
import com.facebook.react.module.model.ReactModuleInfoProvider
import java.io.File

/**
 * Writes the "Run checks" report to the app's files directory and logs it
 * under the MUNIM_LOCATION_CHECKS tag (chunked: logcat truncates long lines).
 */
class ChecksReportModule(context: ReactApplicationContext) : ReactContextBaseJavaModule(context) {
  override fun getName(): String = NAME

  @ReactMethod
  fun write(json: String, promise: Promise) {
    try {
      val file = File(reactApplicationContext.filesDir, "munim-location-checks.json")
      file.writeText(json)
      val chunks = json.chunked(3000)
      chunks.forEachIndexed { index, chunk ->
        Log.i(TAG, "part ${index + 1}/${chunks.size} $chunk")
      }
      Log.i(TAG, "written to ${file.absolutePath}")
      promise.resolve(file.absolutePath)
    } catch (error: Exception) {
      promise.reject("E_WRITE", error.message, error)
    }
  }

  companion object {
    const val NAME = "ChecksReport"
    const val TAG = "MUNIM_LOCATION_CHECKS"
  }
}

class ChecksReportPackage : BaseReactPackage() {
  override fun getModule(name: String, reactContext: ReactApplicationContext): NativeModule? =
    if (name == ChecksReportModule.NAME) ChecksReportModule(reactContext) else null

  override fun getReactModuleInfoProvider(): ReactModuleInfoProvider = ReactModuleInfoProvider {
    mapOf(
      ChecksReportModule.NAME to ReactModuleInfo(
        ChecksReportModule.NAME,
        ChecksReportModule::class.java.name,
        false,
        false,
        false,
        false,
      )
    )
  }
}
