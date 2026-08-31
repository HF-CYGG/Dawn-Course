import java.util.Properties
import java.io.FileInputStream
import com.android.build.api.variant.impl.VariantOutputImpl

plugins {
    alias(libs.plugins.androidApplication)
    // kotlinAndroid 插件已移除：AGP 9.0+ 内置 Kotlin 支持
    alias(libs.plugins.hiltAndroid)
    alias(libs.plugins.ksp)
    alias(libs.plugins.composeCompiler)
}

android {
    namespace = "com.dawncourse.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.dawncourse.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 138
        versionName = "1.0.5.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        create("release") {
            val keystorePropertiesFile = rootProject.file("local.properties")
            val keystoreProperties = Properties()
            if (keystorePropertiesFile.exists()) {
                keystoreProperties.load(FileInputStream(keystorePropertiesFile))
            }
            
            if (keystoreProperties.containsKey("storeFile")) {
                storeFile = file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    // kotlinOptions 块已移除：built-in Kotlin 下 jvmTarget 默认等于
    // 上面的 compileOptions.targetCompatibility，无需重复声明
    buildFeatures {
        compose = true
    }
    // composeOptions.kotlinCompilerExtensionVersion 已移除：
    // Compose 编译器版本现在由 composeCompiler 插件（版本随 Kotlin 一同发布）管理
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        // 16 KB 内存页对齐（部分 2024 年后发布的设备，如高通骁龙 8 Elite Gen 5 机型，
        // 已切换为 16 KB page size；未按 16 KB 对齐的 .so 会在 dlopen 时抛
        // UnsatisfiedLinkError）。
        //
        // 注意：这里显式声明 useLegacyPackaging = false 只是保证 AGP 不压缩、
        // 页对齐地打包 .so 文件本身，是必要条件，不是充分条件——
        // .so 内部 ELF LOAD 段的实际对齐方式取决于编译该 .so 时使用的 NDK 版本
        // （需 NDK r28+，或 r26/r27 显式加对齐链接参数）。当前已升级到 AGP 9.0.1
        // （官方完整支持 16 KB 对齐的最低版本是 8.5.1），打包层面的对齐已生效；
        // 但 quickjs-android 0.9.0 这个原生库本身是否已按 16 KB 对齐编译，
        // 仍需要在真机上验证（参见 feature/import 模块的相关说明）。
        jniLibs {
            useLegacyPackaging = false
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
        xmlReport = true
        htmlReport = true
    }
}

// 重命名 release 包体的输出文件名。
//
// 原先使用的 applicationVariants.all { outputs.all { ... ApkVariantOutput ... } } 是
// AGP 9.0 起被移除访问权限的旧 Variant API（BaseExtension 体系），必须改用
// androidComponents（新 Variant API，AGP 7+ 起就是推荐写法，不受本次 AGP 9 DSL
// 变更影响）。
androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        variant.outputs.forEach { output ->
            (output as? VariantOutputImpl)?.outputFileName?.set("Dawn Course.apk")
        }
    }
}

// 原先通过 kotlinOptions.freeCompilerArgs 传给 Compose 编译器插件的
// stabilityConfigurationPath 参数，现由 composeCompiler 插件的专属扩展配置
composeCompiler {
    // TODO: stabilityConfigurationFiles 是较新版本 Compose 编译器插件的属性名（ListProperty）。
    // 若本地编译报“找不到该属性”，请改为旧版 API：stabilityConfigurationFile.set(...)（单数、RegularFileProperty）
    stabilityConfigurationFiles.add(rootProject.layout.projectDirectory.file("compose_compiler_config.conf"))
}

dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:domain"))
    implementation(project(":core:ui"))
    implementation(project(":feature:timetable"))
    implementation(project(":feature:import"))
    implementation(project(":feature:widget"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:update"))

    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    ksp(libs.hilt.compiler)

    implementation(platform(libs.compose.bom))
    implementation(libs.ui)
    implementation(libs.ui.graphics)
    implementation(libs.ui.tooling.preview)
    implementation(libs.material3)
    // CrashReportDialog 使用 Icons.Rounded.Warning / ContentCopy，二者属于扩展图标集
    implementation(libs.material.icons.extended)

    implementation(libs.activity.compose)
    implementation(libs.navigation.compose)
    implementation(libs.coil.compose)
    implementation(libs.work.runtime.ktx)
    
    implementation("androidx.core:core-splashscreen:1.0.1")
}
