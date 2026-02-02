# Keep Retrofit Interfaces
-keep interface com.dawncourse.feature.update.UpdateApi { *; }

# Keep Data Models (Gson)
-keep class com.dawncourse.feature.update.UpdateInfo { *; }
-keep class com.dawncourse.feature.update.UpdateType { *; }

# Keep Generic Signatures (Crucial for Retrofit Call<T>)
-keepattributes Signature
-keepattributes *Annotation*
