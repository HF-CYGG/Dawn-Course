// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.kotlinAndroid) apply false
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.hiltAndroid) apply false
    alias(libs.plugins.ksp) apply false
}

allprojects {
    configurations.all {
        resolutionStrategy {
            force("com.google.code.gson:gson:2.10.1")
            force("com.google.guava:guava:33.0.0-android")
            force("com.google.protobuf:protobuf-java:3.25.3")
            force("org.apache.commons:commons-compress:1.26.0")
            // Fix Netty vulnerabilities
            force("io.netty:netty-codec-http2:4.1.107.Final")
            force("io.netty:netty-handler:4.1.107.Final")
        }
    }
}
