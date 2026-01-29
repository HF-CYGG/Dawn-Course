plugins {
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.kotlinAndroid)
    alias(libs.plugins.hiltAndroid)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.dawncourse.feature.widget"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }
}

dependencies {
    implementation(project(":core:domain"))
    implementation(project(":core:data"))
    implementation(project(":core:ui"))

    // Glance
    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)
    
    // WorkManager
    implementation(libs.work.runtime.ktx)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    
    // Coroutines
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)
    
    // Compose (Glance uses Compose runtime)
    implementation(platform(libs.compose.bom))
    implementation(libs.ui)
    implementation(libs.ui.tooling.preview)
}
