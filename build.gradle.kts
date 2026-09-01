import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.compose.compiler.gradle.ComposeCompilerGradlePluginExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension

// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.androidTest) apply false
    alias(libs.plugins.baselineProfile) apply false
    alias(libs.plugins.hiltAndroid) apply false
    alias(libs.plugins.ksp) apply false
}

fun Project.configureComposeCompiler() {
    extensions.configure<ComposeCompilerGradlePluginExtension> {
        stabilityConfigurationFile.set(
            rootProject.layout.projectDirectory.file("compose_compiler_config.conf")
        )
    }
}

fun Project.configureAndroidJvm17() {
    extensions.configure<KotlinAndroidProjectExtension> {
        compilerOptions {
            // Java 已统一为 17，Kotlin 也必须显式对齐，避免产物字节码版本分叉。
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
}

subprojects {
    pluginManager.withPlugin("org.jetbrains.kotlin.plugin.compose") {
        configureComposeCompiler()
    }
    pluginManager.withPlugin("org.jetbrains.kotlin.android") {
        configureAndroidJvm17()
    }
}

allprojects {
    configurations.all {
        resolutionStrategy {
            // Security Vulnerability Fixes (Versions managed in libs.versions.toml for Dependabot visibility)
            force("io.netty:netty-codec-http2:${libs.versions.netty.get()}")
            force("io.netty:netty-handler:${libs.versions.netty.get()}")
            force("io.netty:netty-codec:${libs.versions.netty.get()}")
            force("org.bouncycastle:bcprov-jdk18on:${libs.versions.bouncycastle.get()}")
            force("org.bouncycastle:bcpkix-jdk18on:${libs.versions.bouncycastle.get()}")
            force("org.bitbucket.b_c:jose4j:${libs.versions.jose4j.get()}")
            force("org.apache.commons:commons-compress:${libs.versions.commons.compress.get()}")
            force("commons-io:commons-io:${libs.versions.commons.io.get()}")
            force("com.google.protobuf:protobuf-java:${libs.versions.protobuf.get()}")
            force("org.jdom:jdom2:${libs.versions.jdom.get()}")
            force("com.google.guava:guava:${libs.versions.guava.get()}")
            force("com.google.code.gson:gson:${libs.versions.gson.get()}")
        }
    }
}

// 引入 git-hooks 配置
apply(from = "git-hooks.gradle.kts")
