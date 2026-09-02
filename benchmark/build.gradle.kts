plugins {
    alias(libs.plugins.androidTest)
}

android {
    namespace = "com.dawncourse.benchmark"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // AndroidX Benchmark 1.4.1 只有在显式开启时才会导出 BenchmarkData JSON。
        testInstrumentationRunnerArguments["androidx.benchmark.output.enable"] = "true"
    }

    buildTypes {
        create("benchmark") {
            // 测试 APK 可调试；被测 app 的 benchmark 变体保持非 debuggable。
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

androidComponents {
    beforeVariants(selector().all()) { variantBuilder ->
        variantBuilder.enable = variantBuilder.buildType == "benchmark"
    }
}

dependencies {
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.uiautomator)
}

tasks.register<Exec>("aggregateMacrobenchmarkResults") {
    group = "verification"
    description = "汇总 AndroidX Macrobenchmark JSON 为 TTID/TTFD/FrameTiming P50/P95/P99。"
    commandLine(
        "python",
        rootProject.file("scripts/qa/aggregate_macrobenchmark_results.py").absolutePath,
        "--input-dir",
        layout.buildDirectory.dir("outputs/connected_android_test_additional_output").get().asFile.absolutePath,
        "--output",
        layout.buildDirectory.file("reports/macrobenchmark/summary.json").get().asFile.absolutePath
    )
}
