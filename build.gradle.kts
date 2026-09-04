import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.compose.compiler.gradle.ComposeCompilerGradlePluginExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension

// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.androidTest) apply false
    alias(libs.plugins.baselineProfile) apply false
    alias(libs.plugins.hiltAndroid) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.composeCompiler) apply false
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

/** Kotlin 源码词法状态；scanner 用它跳过所有非代码载荷。 */
enum class KotlinLexicalState {
    CODE,
    LINE_COMMENT,
    BLOCK_COMMENT,
    STRING,
    RAW_STRING,
    CHARACTER,
}

/**
 * 对单份 Kotlin 源码执行无依赖崩溃风险扫描。
 *
 * @param source 原始 Kotlin 源码。
 * @return 包含行号和风险类型的诊断列表。
 */
fun scanKotlinCrashHazards(source: String): List<String> {
    val code = StringBuilder(source.length)
    var state = KotlinLexicalState.CODE
    var blockCommentDepth = 0
    var index = 0

    /** 保留换行以维持诊断行号，其余非代码字符统一掩码为空格。 */
    fun appendMasked(character: Char) {
        code.append(if (character == '\n' || character == '\r') character else ' ')
    }

    /** 连续掩码固定长度的分隔符。 */
    fun maskDelimiter(length: Int) {
        repeat(length) { offset -> appendMasked(source[index + offset]) }
        index += length
    }

    while (index < source.length) {
        when (state) {
            KotlinLexicalState.CODE -> when {
                source.startsWith("//", index) -> {
                    maskDelimiter(2)
                    state = KotlinLexicalState.LINE_COMMENT
                }
                source.startsWith("/*", index) -> {
                    maskDelimiter(2)
                    blockCommentDepth = 1
                    state = KotlinLexicalState.BLOCK_COMMENT
                }
                source.startsWith("\"\"\"", index) -> {
                    maskDelimiter(3)
                    state = KotlinLexicalState.RAW_STRING
                }
                source[index] == '"' -> {
                    appendMasked(source[index])
                    index += 1
                    state = KotlinLexicalState.STRING
                }
                source[index] == '\'' -> {
                    appendMasked(source[index])
                    index += 1
                    state = KotlinLexicalState.CHARACTER
                }
                else -> {
                    code.append(source[index])
                    index += 1
                }
            }
            KotlinLexicalState.LINE_COMMENT -> {
                val character = source[index]
                appendMasked(character)
                index += 1
                if (character == '\n') state = KotlinLexicalState.CODE
            }
            KotlinLexicalState.BLOCK_COMMENT -> when {
                source.startsWith("/*", index) -> {
                    maskDelimiter(2)
                    blockCommentDepth += 1
                }
                source.startsWith("*/", index) -> {
                    maskDelimiter(2)
                    blockCommentDepth -= 1
                    if (blockCommentDepth == 0) state = KotlinLexicalState.CODE
                }
                else -> {
                    appendMasked(source[index])
                    index += 1
                }
            }
            KotlinLexicalState.RAW_STRING -> if (source.startsWith("\"\"\"", index)) {
                maskDelimiter(3)
                state = KotlinLexicalState.CODE
            } else {
                appendMasked(source[index])
                index += 1
            }
            KotlinLexicalState.STRING,
            KotlinLexicalState.CHARACTER,
            -> {
                val closingDelimiter = if (state == KotlinLexicalState.STRING) '"' else '\''
                val character = source[index]
                appendMasked(character)
                index += 1
                if (character == '\\' && index < source.length) {
                    appendMasked(source[index])
                    index += 1
                } else if (character == closingDelimiter) {
                    state = KotlinLexicalState.CODE
                }
            }
        }
    }

    val codeOnly = code.toString()
    val patterns = listOf(
        "!!" to Regex("!!"),
        "Context as Activity" to Regex(
            """(?:\(\s*)?(?:\b(?:context|ctx|[A-Za-z_][A-Za-z0-9_]*Context)\b|\bLocalContext\s*\.\s*current\b|\brequireContext\s*\(\s*\))(?:\s*\))?\s+as\s+(?:[A-Za-z_][A-Za-z0-9_.]*\.)?(?:Activity|[A-Za-z_][A-Za-z0-9_]*Activity)\b""",
        ),
    )

    val patternHazards = patterns.flatMap { (name, pattern) ->
        pattern.findAll(codeOnly).map { match ->
            val line = codeOnly.take(match.range.first).count { character -> character == '\n' } + 1
            "line $line: $name"
        }.toList()
    }
    val absHashCodeHazards = Regex("""\babs\s*\(""").findAll(codeOnly).mapNotNull { match ->
        val openingParenthesis = codeOnly.indexOf('(', match.range.first)
        var depth = 1
        var cursor = openingParenthesis + 1
        while (cursor < codeOnly.length && depth > 0) {
            when (codeOnly[cursor]) {
                '(' -> depth += 1
                ')' -> depth -= 1
            }
            cursor += 1
        }
        if (
            depth == 0 &&
            Regex("""\bhashCode\s*\(\s*\)""").containsMatchIn(
                codeOnly.substring(openingParenthesis + 1, cursor - 1),
            )
        ) {
            val line = codeOnly.take(match.range.first).count { character -> character == '\n' } + 1
            "line $line: abs(hashCode())"
        } else {
            null
        }
    }.toList()

    return (patternHazards + absHashCodeHazards).sorted()
}

val kotlinCrashHazardScanner: (String) -> List<String> = ::scanKotlinCrashHazards
extensions.extraProperties["kotlinCrashHazardScanner"] = kotlinCrashHazardScanner

// Kotlin 崩溃风险 scanner 的无依赖 fixture 测试；生产 scanner 在 RED 后实现。
tasks.register("verifyKotlinCrashHazardScannerFixtures") {
    group = "verification"
    description = "验证 Kotlin 崩溃风险 scanner 能区分真实代码与注释、字符串和 Qidi raw JavaScript。"
    doLast {
        @Suppress("UNCHECKED_CAST")
        val scan = rootProject.extensions.extraProperties["kotlinCrashHazardScanner"]
            as (String) -> List<String>

        check(scan("val value = nullable!!").any { hazard -> hazard.contains("!!") })
        check(scan("val value = kotlin.math.abs(key.hashCode())").any { hazard -> hazard.contains("abs") })
        check(
            scan(
                """
                val value = kotlin.math.abs(
                    key.hashCode(),
                )
                """.trimIndent(),
            ).any { hazard -> hazard.contains("abs") },
        )
        check(scan("val value = Math.abs(Foo(key).hashCode())").any { hazard -> hazard.contains("abs") })
        check(scan("val activity = applicationContext as MainActivity").any { hazard -> hazard.contains("Activity") })
        check(scan("val activity = context as Activity").any { hazard -> hazard.contains("Activity") })
        check(scan("val activity = ctx as Activity").any { hazard -> hazard.contains("Activity") })
        check(scan("val activity = requireContext() as Activity").any { hazard -> hazard.contains("Activity") })
        check(scan("val activity = (context) as Activity").any { hazard -> hazard.contains("Activity") })
        check(scan("val activity = LocalContext.current as Activity").any { hazard -> hazard.contains("Activity") })
        check(scan("val activity = context as? Activity").isEmpty())
        check(scan("val activity = ctx as? Activity").isEmpty())
        check(scan("val activity = requireContext() as? Activity").isEmpty())
        check(
            scan(
                """
                // nullable!!
                val ordinary = "applicationContext as MainActivity"
                val character = '!'
                val qidiScript = \"\"\"
                    var hasSelect = !!(window.y || window.t);
                    var hash = abs(value.hashCode());
                \"\"\"
                /* context as Activity */
                """.trimIndent(),
            ).isEmpty(),
        )
    }
}

val productionKotlinSourceTrees = subprojects
    .filter { subproject ->
        val relativePath = subproject.projectDir.relativeTo(rootDir).invariantSeparatorsPath
        relativePath == "app" || relativePath.startsWith("core/") || relativePath.startsWith("feature/")
    }
    .map { subproject ->
        subproject.fileTree(subproject.file("src/main")) {
            include("**/*.kt")
        }
    }

val verifyKotlinCrashHazards = tasks.register("verifyKotlinCrashHazards") {
    group = "verification"
    description = "词法扫描生产 Kotlin，阻止非空断言、abs(hashCode()) 与 Context 强转 Activity。"
    dependsOn("verifyKotlinCrashHazardScannerFixtures")
    inputs.files(productionKotlinSourceTrees)
    doLast {
        val violations = productionKotlinSourceTrees
            .flatMap { tree -> tree.files }
            .sortedBy { file -> file.invariantSeparatorsPath }
            .flatMap { file ->
                scanKotlinCrashHazards(file.readText()).map { hazard ->
                    "${file.relativeTo(rootDir).invariantSeparatorsPath}:$hazard"
                }
            }
        check(violations.isEmpty()) {
            "发现 Kotlin 崩溃风险：\n${violations.joinToString("\n")}"
        }
    }
}

subprojects {
    tasks.matching { task -> task.name == "check" }.configureEach {
        dependsOn(verifyKotlinCrashHazards)
    }
}

// 引入 git-hooks 配置
apply(from = "git-hooks.gradle.kts")
