pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

buildscript {
    repositories {
        mavenCentral()
        google()
    }
    configurations.classpath {
        resolutionStrategy {
            // 强制 Netty 使用安全版本 (>= 4.1.125.Final)
            force("io.netty:netty-codec-http2:4.1.125.Final")
            force("io.netty:netty-handler:4.1.125.Final")
            force("io.netty:netty-codec:4.1.125.Final")
            force("io.netty:netty-common:4.1.125.Final")
            force("io.netty:netty-codec-http:4.1.125.Final")
            force("io.netty:netty-transport-native-epoll:4.1.125.Final")
            force("io.netty:netty-transport-native-unix-common:4.1.125.Final")
            
            // 强制 Bouncy Castle 使用安全版本 (jdk15on: 1.70, jdk18on: 1.83)
            // 旧插件可能仍依赖 jdk15on
            force("org.bouncycastle:bcprov-jdk15on:1.70")
            force("org.bouncycastle:bcpkix-jdk15on:1.70")
            force("org.bouncycastle:bcprov-jdk18on:1.83")
            force("org.bouncycastle:bcpkix-jdk18on:1.83")
            
            // 强制 Apache Commons IO (>= 2.18.0)
            force("commons-io:commons-io:2.18.0")
            
            // 强制 Apache Commons Compress (>= 1.28.0)
            force("org.apache.commons:commons-compress:1.28.0")
            
            // 强制 Protobuf-java (>= 3.25.5)
            force("com.google.protobuf:protobuf-java:3.25.5")
            
            // 强制 JDOM2 (>= 2.0.6.1)
            force("org.jdom:jdom2:2.0.6.1")
            
            // 强制 Jose4j (>= 0.9.6)
            force("org.bitbucket.b_c:jose4j:0.9.6")
            
            // 强制 Guava (>= 33.0.0-android)
            force("com.google.guava:guava:33.0.0-android")
            
            // 强制 Gson (>= 2.10.1)
            force("com.google.code.gson:gson:2.10.1")
        }
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "DawnCourse"
include(":app")
include(":core:data")
include(":core:domain")
include(":core:ui")
include(":feature:timetable")
include(":feature:import")
include(":feature:widget") // Phase 3
include(":feature:settings") // Phase 4
include(":feature:update")

