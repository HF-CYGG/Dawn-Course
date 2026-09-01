plugins {
    alias(libs.plugins.androidLibrary)
    // kotlinAndroid 插件已移除：AGP 9.0+ 内置 Kotlin 支持
}

android {
    namespace = "com.dawncourse.core.domain"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
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
