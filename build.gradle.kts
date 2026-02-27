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
