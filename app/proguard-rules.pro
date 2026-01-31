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
