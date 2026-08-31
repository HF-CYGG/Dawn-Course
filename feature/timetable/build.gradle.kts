plugins {
    alias(libs.plugins.androidLibrary)
    // kotlinAndroid 插件已移除：AGP 9.0+ 内置 Kotlin 支持
    alias(libs.plugins.hiltAndroid)
    alias(libs.plugins.ksp)
    alias(libs.plugins.composeCompiler)
}

android {
    namespace = "com.dawncourse.feature.timetable"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
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

// 原先通过 kotlinOptions.freeCompilerArgs 传给 Compose 编译器插件的
// stabilityConfigurationPath 参数，现由 composeCompiler 插件的专属扩展配置
composeCompiler {
    // TODO: stabilityConfigurationFiles 是较新版本 Compose 编译器插件的属性名（ListProperty）。
    // 若本地编译报“找不到该属性”，请改为旧版 API：stabilityConfigurationFile.set(...)（单数、RegularFileProperty）
    stabilityConfigurationFiles.add(rootProject.layout.projectDirectory.file("compose_compiler_config.conf"))
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
    implementation(libs.coil.compose)
    implementation(libs.work.runtime.ktx)

    // 单元测试（纯 JVM 测试）
    testImplementation(libs.junit)
}
