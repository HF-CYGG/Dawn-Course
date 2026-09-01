plugins {
    alias(libs.plugins.androidLibrary)
    // kotlinAndroid 插件已移除：AGP 9.0+ 内置 Kotlin 支持
    alias(libs.plugins.hiltAndroid)
    alias(libs.plugins.ksp)
    alias(libs.plugins.composeCompiler)
}

android {
    namespace = "com.dawncourse.feature.import_module" // 使用 import_module 避免关键字冲突
    compileSdk = 37

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
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    // kotlinOptions 块已移除：built-in Kotlin 下 jvmTarget 默认等于
    // 上面的 compileOptions.targetCompatibility，无需重复声明
    buildFeatures {
        compose = true
    }
    // composeOptions.kotlinCompilerExtensionVersion 已移除：
    // Compose 编译器版本现在由 composeCompiler 插件（版本随 Kotlin 一同发布）管理
}

dependencies {
    implementation(project(":core:domain"))
    implementation(project(":core:ui"))
    
    // QuickJS 引擎
    implementation(libs.quickjs.android)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.ui)
    implementation(libs.ui.graphics)
    implementation(libs.ui.tooling.preview)
    implementation(libs.material3)
    implementation(libs.material.icons.extended)
    implementation(libs.activity.compose)
    
    // Lifecycle
    implementation(libs.lifecycle.runtime.ktx)

    // HTML 解析 (Jsoup)
    implementation(libs.jsoup)

    // 单元测试（纯 JVM）
    testImplementation(libs.junit)
    // 提供真实的 org.json 实现，替换掉 Android SDK 里只会抛异常的桩，
    // 使 ImportModels / QiangZhiApiEngine 相关逻辑可在 JVM 单测中运行
    testImplementation("org.json:json:20240303")
}

// ---------------- JS 解析器单元测试（Node）----------------
// feature/import/src/test/js/parse_weeks.test.cjs 用纯 Node（零依赖）加载
// common_parser_utils.js / zhengfang.js 并断言周次解析行为（issue #109 回归）。
// CI 的 ubuntu runner 自带 node；本地需自行安装 node。
val jsParserTest = tasks.register<Exec>("jsParserTest") {
    group = "verification"
    description = "运行 JS 解析器周次解析单元测试 (Node)"
    workingDir = projectDir
    commandLine("node", "src/test/js/parse_weeks.test.cjs")
}

// 让 CI 实际会跑的 testDebugUnitTest 以及标准的 check 都带上 jsParserTest
tasks.matching { it.name == "testDebugUnitTest" || it.name == "test" }.configureEach {
    dependsOn(jsParserTest)
}
tasks.named("check") {
    dependsOn(jsParserTest)
}
