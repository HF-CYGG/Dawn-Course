# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Keep all domain models to prevent R8 from stripping/renaming them
# which might cause issues with reflection or JSON serialization if used
-keep class com.dawncourse.core.domain.model.** { *; }

# Keep Hilt generated classes (usually handled by Hilt plugin, but good to be safe)
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }

# Keep Room entities and DAOs
-keep class androidx.room.** { *; }
-keep class * extends androidx.room.RoomDatabase

# Keep Kotlin Coroutines
-keep class kotlinx.coroutines.** { *; }

# Keep DataStore
-keep class androidx.datastore.** { *; }

# --- Update Feature Rules ---
# Retrofit & Gson for Update Check
-keep interface com.dawncourse.feature.update.UpdateApi { *; }
-keep class com.dawncourse.feature.update.UpdateInfo { *; }
-keep class com.dawncourse.feature.update.UpdateType { *; }

# Keep generic signatures for Retrofit
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes EnclosingMethod
-keepattributes InnerClasses

# Prevent R8 from being too aggressive with Retrofit interfaces
-keep interface retrofit2.** { *; }
-keep class retrofit2.** { *; }
