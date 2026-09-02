import java.util.Properties
import java.io.FileInputStream
import java.util.zip.ZipFile
import org.gradle.api.tasks.Exec

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.baselineProfile)
    alias(libs.plugins.hiltAndroid)
    alias(libs.plugins.ksp)
    alias(libs.plugins.composeCompiler)
}

val releaseSmokeEnabled = providers.gradleProperty("dawn.releaseSmoke")
    .map { it.toBooleanStrict() }
    .orElse(false)

val DAWN_COURSE_RULE_PREFIX = "Lcom/dawncourse/"
val BENCHMARK_RULE_PREFIX = "Lcom/dawncourse/app/benchmark/"

android {
    namespace = "com.dawncourse.app"
    compileSdk = 37

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
            // 本地 minified 冒烟显式使用 debug 签名；普通 release 永不自动降级签名。
            signingConfig = signingConfigs.getByName(
                if (releaseSmokeEnabled.get()) "debug" else "release"
            )
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        // Macrobenchmark 使用接近 release 的配置，但只使用本地 debug 签名，绝不用于发布。
        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
        // 以下变体由 Baseline Profile 插件用于生成/验证；仅覆盖签名，关键性能开关由插件控制。
        create("benchmarkRelease") {
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
        create("nonMinifiedRelease") {
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // kotlinOptions 块已移除：built-in Kotlin 下 jvmTarget 默认等于
    // 上面的 compileOptions.targetCompatibility，无需重复声明
    buildFeatures {
        compose = true
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
    // 仅 benchmark 相关变体合入数据种子 Provider；release manifest 完全不注册测试组件。
    sourceSets {
        getByName("benchmark") {
            kotlin.srcDir("src/benchmark/java")
            manifest.srcFile("src/benchmark/AndroidManifest.xml")
        }
        getByName("benchmarkRelease") {
            kotlin.srcDir("src/benchmark/java")
        }
        getByName("nonMinifiedRelease") {
            kotlin.srcDir("src/benchmark/java")
        }
    }
}

androidComponents {
    // Baseline Profile 插件创建的 synthetic 变体不会自动采用同名目录的 Manifest。
    // 通过 Variant API 精确合入测试 Provider，避免任何测试组件进入生产 release。
    onVariants(selector().withBuildType("benchmarkRelease")) { variant ->
        variant.sources.manifests.addStaticManifestFile(
            "src/benchmark/AndroidManifest.xml"
        )
    }
    onVariants(selector().withBuildType("nonMinifiedRelease")) { variant ->
        variant.sources.manifests.addStaticManifestFile(
            "src/benchmark/AndroidManifest.xml"
        )
    }
}

baselineProfile {
    // 设备测试显式触发，避免普通 assembleRelease 意外启动 instrumentation。
    automaticGenerationDuringBuild = false
    saveInSrc = true
}

tasks.register("verifyGeneratedBaselineProfileSource") {
    group = "verification"
    description = "确认生成规则进入 release Profile 合并输入，并验证测试组件隔离。"
    dependsOn(
        "mergeReleaseArtProfile",
        "mergeReleaseStartupProfile",
        "processBenchmarkReleaseManifestForPackage",
        "processNonMinifiedReleaseManifestForPackage",
        "processReleaseManifestForPackage"
    )
    doLast {
        val profileDirectory = layout.projectDirectory
            .dir("src/release/generated/baselineProfiles")
            .asFile
        val baselineProfile = profileDirectory.resolve("baseline-prof.txt")
        val startupProfile = profileDirectory.resolve("startup-prof.txt")
        // AGP 会把 src/release/generated/baselineProfiles 下的文本规则并入 release 的
        // ART / Startup Profile 合并产物，最终打进 APK 的 assets/dexopt。这里校验的是
        // 这条「消费」链路的合并输出（mergeReleaseArtProfile / mergeReleaseStartupProfile），
        // 而不是 baselineprofile 插件「生成」链路的 intermediates/baselineprofiles 目录——
        // 后者仅在连设备生成 Profile 时才产出，clean 状态下的 assembleRelease 不会创建它，
        // 之前正是因此让本门禁在 CI 上误报失败。
        val mergedArtProfileRoot = layout.buildDirectory
            .dir("intermediates/merged_art_profile/release")
            .get().asFile
        val mergedStartupProfileRoot = layout.buildDirectory
            .dir("intermediates/merged_startup_profile/release")
            .get().asFile
        fun locateMergedProfile(root: File, fileName: String): File {
            val matches = root.walkTopDown()
                .filter { it.isFile && it.name == fileName }
                .toList()
            check(matches.size == 1) {
                "release 合并输出中应恰好有一个 $fileName，实际：" +
                    matches.map(File::getAbsolutePath)
            }
            return matches.single()
        }
        val profileToMerged = linkedMapOf(
            baselineProfile to locateMergedProfile(mergedArtProfileRoot, "baseline-prof.txt"),
            startupProfile to locateMergedProfile(mergedStartupProfileRoot, "startup-prof.txt")
        )
        profileToMerged.forEach { (profile, mergedProfile) ->
            check(profile.isFile && profile.length() > 0L) {
                "缺少非空的生成 Profile：${profile.absolutePath}"
            }
            val rules = profile.readLines().filter(String::isNotBlank)
            check(rules.isNotEmpty()) { "生成 Profile 没有规则：${profile.name}" }
            check(rules.any { it.contains(DAWN_COURSE_RULE_PREFIX) && !it.contains(BENCHMARK_RULE_PREFIX) }) {
                "生成 Profile 缺少 Dawn Course 的生产规则：${profile.name}"
            }
            check(rules.none { it.contains(BENCHMARK_RULE_PREFIX) }) {
                "生成 Profile 泄漏 benchmark-only 规则：${profile.name}"
            }
            check(mergedProfile.isFile && mergedProfile.length() > 0L) {
                "release 合并输出缺少非空 Profile：${mergedProfile.absolutePath}"
            }
            // AGP 先原样并入应用规则，再在后续 expand 任务里展开通配符，因此合并输出必然
            // 逐行包含全部来源规则；库自带规则只会让合并输出更大，不影响这里的子集校验。
            val mergedRules = mergedProfile.readLines().filterTo(HashSet(), String::isNotBlank)
            val missing = rules.filterNot(mergedRules::contains)
            check(missing.isEmpty()) {
                "生成 Profile 的规则未全部进入 release 合并输出：${profile.name}，" +
                    "缺失 ${missing.size} 条，例如 ${missing.take(3)}"
            }
        }

        val packagedManifestRoot = layout.buildDirectory
            .dir("intermediates/packaged_manifests")
            .get().asFile
        fun packagedManifest(variant: String): File =
            packagedManifestRoot.walkTopDown()
                .filter { it.isFile && it.name == "AndroidManifest.xml" }
                .single { it.invariantSeparatorsPath.contains("/$variant/") }
        listOf("benchmarkRelease", "nonMinifiedRelease").forEach { variant ->
            check(packagedManifest(variant).readText().contains("BenchmarkSeedProvider")) {
                "$variant 未包含 benchmark-only 数据种子 Provider。"
            }
        }
        check(!packagedManifest("release").readText().contains("BenchmarkSeedProvider")) {
            "生产 release Manifest 不得包含 BenchmarkSeedProvider。"
        }
    }
}

tasks.register("verifyPackagedReleaseBaselineProfile") {
    group = "verification"
    description = "确认真实 minified release APK 嵌入本次生成的 Baseline/Startup Profile。"
    dependsOn("assembleRelease", "verifyGeneratedBaselineProfileSource")
    doLast {
        val releaseApk = layout.buildDirectory.dir("outputs/apk/release").get().asFile
            .listFiles { file -> file.extension == "apk" }
            ?.singleOrNull()
            ?: error("应恰好生成一个 release APK。")
        ZipFile(releaseApk).use { apk ->
            listOf(
                "assets/dexopt/baseline.prof",
                "assets/dexopt/baseline.profm"
            ).forEach { entryName ->
                check(apk.getEntry(entryName)?.size?.let { it > 0L } == true) {
                    "release APK 缺少非空的 $entryName。"
                }
            }
        }
    }
}

tasks.register("verifyGeneratedBaselineProfile") {
    group = "verification"
    description = "执行 release Profile 来源、合并输入与最终 APK 的完整门禁。"
    dependsOn("verifyPackagedReleaseBaselineProfile")
}

tasks.matching {
    it.name == "mergeReleaseArtProfile" || it.name == "mergeReleaseStartupProfile"
}.configureEach {
    // 仅当采集任务也进入任务图时，确保新文本规则先于 release 的 Profile 合并。
    mustRunAfter("generateReleaseBaselineProfile")
}

tasks.register("generateAndVerifyBaselineProfile") {
    group = "verification"
    description = "在连接设备上生成 release Profile，再执行全部内容与嵌入门禁。"
    dependsOn("generateReleaseBaselineProfile", "verifyGeneratedBaselineProfile")
}

// PowerShell 7 consistently reads the UTF-8 QA scripts on Windows/Linux runners.
val releaseSmokePowerShell = "pwsh"

tasks.register<Exec>("verifyReleaseSmokeSizeBudget") {
    group = "verification"
    description = "用仓库内固定、可审计的 releaseSmoke 基线校验通用 APK 体积预算。"
    dependsOn("assembleRelease")
    doFirst {
        commandLine(
            releaseSmokePowerShell,
            "-NoProfile",
            "-File",
            rootProject.file("scripts/qa/verify-apk-size-budget.ps1").absolutePath,
            "-ApkPath",
            layout.buildDirectory.file("outputs/apk/release/app-release.apk").get().asFile.absolutePath,
            "-BaselineManifestPath",
            rootProject.file("scripts/qa/release-smoke-size-baseline.json").absolutePath
        )
    }
}

tasks.register<Exec>("verifyReleaseSmokeNativePackaging") {
    group = "verification"
    description = "校验 releaseSmoke 的四 ABI、SQLCipher/QuickJS 库矩阵及 16 KB ZIP/ELF 对齐。"
    dependsOn("assembleRelease")
    doFirst {
        commandLine(
            releaseSmokePowerShell,
            "-NoProfile",
            "-File",
            rootProject.file("scripts/qa/verify-native-page-size.ps1").absolutePath,
            "-ApkPath",
            layout.buildDirectory.file("outputs/apk/release/app-release.apk").get().asFile.absolutePath
        )
    }
}

tasks.matching {
    it.name == "verifyGeneratedBaselineProfileSource" ||
        it.name == "verifyPackagedReleaseBaselineProfile" ||
        it.name == "verifyGeneratedBaselineProfile"
}.configureEach {
    mustRunAfter("generateReleaseBaselineProfile")
}

tasks.register("releaseSmoke") {
    group = "verification"
    description = "使用 debug 签名构建与正式 release 等价的 minified 本地冒烟 APK；产物禁止分发。"
    if (releaseSmokeEnabled.get()) {
        dependsOn(
            "verifyGeneratedBaselineProfile",
            "verifyReleaseSmokeSizeBudget",
            "verifyReleaseSmokeNativePackaging"
        )
    }
    doLast {
        check(releaseSmokeEnabled.get()) {
            "releaseSmoke 必须显式传入 -Pdawn.releaseSmoke=true；正式 release 不允许降级签名。"
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
    implementation(libs.hilt.lifecycle.viewmodel.compose)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.work)
    ksp(libs.hilt.compiler)
    ksp(libs.androidx.hilt.compiler)

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
    implementation(libs.androidx.startup.runtime)
    
    implementation(libs.core.splashscreen)

    // 系统事件恢复策略的纯 JVM 单元测试。
    testImplementation(libs.junit)

    // 此依赖只把生成器产出的文本规则接入 AGP；不会添加生产 runtime 依赖。
    baselineProfile(project(":baselineprofile"))

    // Benchmark-only Provider 直接构造 Room，依赖只存在于三类性能测试变体。
    add("benchmarkImplementation", libs.room.ktx)
    add("benchmarkReleaseImplementation", libs.room.ktx)
    add("nonMinifiedReleaseImplementation", libs.room.ktx)
}
