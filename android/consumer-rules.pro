# munim-location consumer ProGuard / R8 rules, applied to every app that
# depends on this library (consumerProguardFiles in build.gradle).

# The Nitro hybrid object and its generated structs and enums are created
# and read from C++ through JNI, so R8 cannot see their uses.
-keep class com.margelo.nitro.munimlocation.** { *; }

# Background delivery: the foreground service, the Headless JS task service,
# and the receivers are started by the system (manifest, PendingIntents,
# boot) with no app code on the stack. Persisted options are restored from
# JSON with enum valueOf(), which breaks if enum constants are renamed.
-keep class com.munimlocation.** { *; }

# Headless JS: React Native starts HeadlessJsTaskService subclasses by class
# name from the manifest.
-keep class * extends com.facebook.react.HeadlessJsTaskService { *; }
