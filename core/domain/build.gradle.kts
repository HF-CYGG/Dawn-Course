plugins {
    alias(libs.plugins.androidLibrary)
}

android {
    namespace = "com.dawncourse.core.domain"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.coroutines.core)
    implementation(libs.javax.inject) // Need to ensure javax.inject is available or use hilt-core

    // 单元测试（纯 JVM 测试）
    testImplementation(libs.junit)
}
