plugins {
    alias(libs.plugins.androidLibrary)
    // kotlinAndroid 插件已移除：AGP 9.0+ 内置 Kotlin 支持
    alias(libs.plugins.ksp)
    alias(libs.plugins.hiltAndroid)
}

android {
    namespace = "com.dawncourse.core.data"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    // kotlinOptions 块已移除：built-in Kotlin 下 jvmTarget 默认等于
    // 上面的 compileOptions.targetCompatibility，无需重复声明
}

dependencies {
    implementation(project(":core:domain"))

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)
    
    implementation(libs.datastore.preferences)
    implementation(libs.security.crypto)
    implementation(libs.gson)
    implementation(libs.okhttp)

    implementation(libs.javax.inject)
}
