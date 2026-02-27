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
            force("org.apache.commons:commons-compress:1.28.0")
            force("commons-io:commons-io:2.21.0")
            force("org.jdom:jdom2:2.0.6.1")
            force("org.bitbucket.b_c:jose4j:0.9.6")
            force("org.bouncycastle:bcprov-jdk18on:1.83")
            force("org.bouncycastle:bcpkix-jdk18on:1.83")
            // Fix Netty vulnerabilities
            force("io.netty:netty-codec-http2:4.1.108.Final")
            force("io.netty:netty-handler:4.1.108.Final")
        }
    }
}
