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
    // kotlinOptions 块已移除：built-in Kotlin 下由顶层 kotlin.compilerOptions 配置，
    // 且 jvmTarget 默认等于上面的 compileOptions.targetCompatibility，无需重复声明
}

dependencies {
    implementation(libs.coroutines.core)
    implementation(libs.javax.inject) // Need to ensure javax.inject is available or use hilt-core

    // 单元测试（纯 JVM 测试）
    testImplementation(libs.junit)
}
