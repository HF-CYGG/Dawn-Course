plugins {
    alias(libs.plugins.androidLibrary)
    // kotlinAndroid 插件已移除：AGP 9.0+ 内置 Kotlin 支持
    alias(libs.plugins.hiltAndroid)
    alias(libs.plugins.ksp)
    alias(libs.plugins.composeCompiler)
}

android {
    namespace = "com.dawncourse.feature.update"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("proguard-rules.pro")
    }

    buildFeatures {
        compose = true
    }
    // composeOptions.kotlinCompilerExtensionVersion 已移除：
    // Compose 编译器版本现在由 composeCompiler 插件（版本随 Kotlin 一同发布）管理

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    // kotlinOptions 块已移除：built-in Kotlin 下 jvmTarget 默认等于
    // 上面的 compileOptions.targetCompatibility，无需重复声明
}

dependencies {
    implementation(project(":core:domain"))
    implementation(project(":core:ui"))

    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    ksp(libs.hilt.compiler)

    implementation(platform(libs.compose.bom))
    implementation(libs.ui)
    implementation(libs.ui.graphics)
    implementation(libs.ui.tooling.preview)
    implementation(libs.material3)
    implementation(libs.material.icons.extended)

    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.retrofit)
    implementation(libs.converter.gson)
    implementation(libs.okhttp)
    implementation(libs.logging.interceptor)
}
