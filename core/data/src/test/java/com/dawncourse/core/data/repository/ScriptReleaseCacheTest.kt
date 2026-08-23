package com.dawncourse.core.data.repository

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 验证远端脚本只有真实解析成功后才替换 active。 */
class ScriptReleaseCacheTest {

    @Test
    fun `staging 不会提前覆盖 active`() {
        val cache = ScriptReleaseCache(Files.createTempDirectory("script-cache").toFile())
        val key = "parser/parsers/zhengfang.js/system/ZF"

        cache.stage(release(key, "rel-1", "old"))
        assertTrue(cache.activate(key, "rel-1"))
        cache.stage(release(key, "rel-2", "new"))

        assertEquals("old", cache.readActive(key)?.content)
        assertEquals("new", cache.readStaging(key, "rel-2")?.content)
    }

    @Test
    fun `成功激活会覆盖 active 并保留一个 previous stable`() {
        val cache = ScriptReleaseCache(Files.createTempDirectory("script-cache").toFile())
        val key = "parser/parsers/zhengfang.js/school/school-a"
        cache.stage(release(key, "rel-1", "old"))
        cache.activate(key, "rel-1")
        cache.stage(release(key, "rel-2", "new"))

        assertTrue(cache.activate(key, "rel-2"))
        assertEquals("new", cache.readActive(key)?.content)
        assertEquals("old", cache.readPreviousStable(key)?.content)
    }

    @Test
    fun `失败隔离 staging 且 active 保持不变`() {
        val cache = ScriptReleaseCache(Files.createTempDirectory("script-cache").toFile())
        val key = "parser/parsers/zhengfang.js/school/school-a"
        cache.stage(release(key, "rel-1", "old"))
        cache.activate(key, "rel-1")
        cache.stage(release(key, "rel-2", "broken"))

        assertTrue(cache.quarantine(key, "rel-2", "schema_invalid"))
        assertEquals("old", cache.readActive(key)?.content)
        assertNull(cache.readStaging(key, "rel-2"))
        assertTrue(cache.isQuarantined(key, "rel-2"))
    }

    @Test
    fun `不同 scriptKey 使用独立 active`() {
        val cache = ScriptReleaseCache(Files.createTempDirectory("script-cache").toFile())
        val systemKey = "parser/parsers/zhengfang.js/system/ZF"
        val schoolKey = "parser/parsers/zhengfang.js/school/school-a"
        cache.stage(release(systemKey, "rel-system", "system"))
        cache.stage(release(schoolKey, "rel-school", "school"))

        cache.activate(systemKey, "rel-system")
        cache.activate(schoolKey, "rel-school")

        assertEquals("system", cache.readActive(systemKey)?.content)
        assertEquals("school", cache.readActive(schoolKey)?.content)
        assertFalse(cache.readActive(systemKey)?.releaseId == cache.readActive(schoolKey)?.releaseId)
    }

    @Test
    fun `相同 release 和内容 hash 已激活时可直接复用`() {
        val cache = ScriptReleaseCache(Files.createTempDirectory("script-cache").toFile())
        val key = "parser/parsers/zhengfang.js/system/ZF"
        cache.stage(release(key, "rel-1", "stable"))
        cache.activate(key, "rel-1")

        assertTrue(cache.matchesActive(key, "rel-1", sha256("stable")))
        assertFalse(cache.matchesActive(key, "rel-1", sha256("changed")))
        assertFalse(cache.matchesActive(key, "rel-2", sha256("stable")))
    }

    @Test
    fun `V2 首次激活后持久标记旧缓存已淘汰`() {
        val root = Files.createTempDirectory("script-cache").toFile()
        val cache = ScriptReleaseCache(root)

        assertFalse(cache.isLegacySuperseded("parsers", "zhengfang.js"))
        cache.markLegacySuperseded("parsers", "zhengfang.js")

        assertTrue(ScriptReleaseCache(root).isLegacySuperseded("parsers", "zhengfang.js"))
    }

    @Test
    fun `active 失败时回滚 previous stable 并隔离失败版本`() {
        val cache = ScriptReleaseCache(Files.createTempDirectory("script-cache").toFile())
        val key = "parser/parsers/zhengfang.js/school/school-a"
        cache.stage(release(key, "rel-1", "stable"))
        cache.activate(key, "rel-1")
        cache.stage(release(key, "rel-2", "broken"))
        cache.activate(key, "rel-2")

        assertTrue(cache.rollbackActive(key, "rel-2", "schema_invalid"))
        assertEquals("stable", cache.readActive(key)?.content)
        assertNull(cache.readPreviousStable(key))
        assertTrue(cache.isQuarantined(key, "rel-2"))
    }

    @Test
    fun `缺少 previous stable 时回滚失败且 active 保持不变`() {
        val cache = ScriptReleaseCache(Files.createTempDirectory("script-cache").toFile())
        val key = "parser/parsers/zhengfang.js/system/ZF"
        cache.stage(release(key, "rel-1", "only-active"))
        cache.activate(key, "rel-1")

        assertFalse(cache.rollbackActive(key, "rel-1", "empty_result"))
        assertEquals("only-active", cache.readActive(key)?.content)
        assertFalse(cache.isQuarantined(key, "rel-1"))
    }

    private fun release(scriptKey: String, releaseId: String, content: String): CachedScriptRelease {
        return CachedScriptRelease(
            scriptKey = scriptKey,
            releaseId = releaseId,
            content = content,
            metaRaw = "meta-$releaseId",
            dependencies = listOf("dependency-$releaseId")
        )
    }

    private fun sha256(content: String): String {
        return java.security.MessageDigest.getInstance("SHA-256")
            .digest(content.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}
