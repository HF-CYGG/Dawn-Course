import java.util.Properties
import java.io.FileInputStream

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlinAndroid)
    alias(libs.plugins.hiltAndroid)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.dawncourse.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.dawncourse.app"
        minSdk = 26
        targetSdk = 34
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
    kotlinOptions {
        jvmTarget = "1.8"
        freeCompilerArgs += listOf(
            "-P",
            "plugin:androidx.compose.compiler.plugins.kotlin:stabilityConfigurationPath=${project.rootDir}/compose_compiler_config.conf"
        )
    }
    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.11"
    }
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
        // （需 NDK r28+，或 r26/r27 显式加对齐链接参数）。
        // 当前 AGP 版本 (8.3.1) 也早于官方完整支持 16 KB 对齐的 8.5.1，
        // 完整生效需配合 Phase 3 的 AGP 升级一并验证。
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

    applicationVariants.all {
        outputs.all {
            val output = this as? com.android.build.gradle.api.ApkVariantOutput
            if (buildType.name == "release") {
                output?.outputFileName = "Dawn Course.apk"
            }
        }
    }
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
