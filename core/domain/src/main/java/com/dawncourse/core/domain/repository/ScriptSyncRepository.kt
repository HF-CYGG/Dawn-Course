package com.dawncourse.core.domain.repository

import com.dawncourse.core.domain.model.RemoteScriptDescriptor

/**
 * 脚本获取结果
 *
 * @property content 脚本内容
 * @property fromCloud 是否来自云端
 * @property source 来源标识（cloud_primary / cloud_fallback / local_cache / assets）
 */
data class ScriptFetchResult(
    val content: String,
    val fromCloud: Boolean,
    val source: String,
    val releaseId: String = "",
    val scriptKey: String = "",
    val scopeKind: String = "global",
    val scopeId: String = "",
    val schoolSystemType: String = "UNKNOWN",
    val version: Int = 0,
    val pendingActivation: Boolean = false,
    val dependencyContents: List<String> = emptyList()
)

/**
 * 脚本同步仓库接口
 * 用于从云端拉取最新的解析与导航脚本，应对教务系统的频繁变动。
 */
interface ScriptSyncRepository {
    /** 下载并验签 manifest 指定候选，只写入 staging。 */
    suspend fun prepareScriptCandidate(
        descriptor: RemoteScriptDescriptor,
        pullTaskId: String = ""
    ): ScriptFetchResult

    /** 真实解析成功后将 staging 候选原子提升为 active。 */
    suspend fun activatePreparedScript(result: ScriptFetchResult): Boolean

    /** 真实解析失败后隔离 staging 候选，active 保持不变。 */
    suspend fun quarantinePreparedScript(result: ScriptFetchResult, reason: String): Boolean

    /** 已激活 release 真实解析失败时回滚到 previous stable。 */
    suspend fun rollbackActiveScript(result: ScriptFetchResult, reason: String): Boolean

    /**
     * 获取指定名称的脚本内容
     * @param scriptName 脚本文件名，例如 "zhengfang.js" 或 "zf_nav.js"
     * @param category 脚本分类，如 "parsers" 或 "js"
     * @return 脚本的完整字符串内容
     */
    suspend fun getScript(
        scriptName: String,
        category: String = "js",
        pullTaskId: String = ""
    ): String

    /**
     * 获取脚本并返回来源信息
     *
     * 该接口用于上层感知“是否成功拉取云端脚本”，
     * 以便在降级到本地缓存/内置脚本时进行用户提示。
     */
    suspend fun getScriptWithInfo(
        scriptName: String,
        category: String = "js",
        pullTaskId: String = ""
    ): ScriptFetchResult
    
    /**
     * 强制从云端更新脚本
     * @param scriptName 脚本文件名
     * @param category 脚本分类
     * @return 更新后的脚本内容，若失败则返回本地缓存或 assets 默认内容
     */
    suspend fun fetchAndCacheScript(
        scriptName: String,
        category: String = "js",
        pullTaskId: String = ""
    ): String

    suspend fun getScriptVersion(scriptName: String, category: String = "js"): Int?

    /**
     * 列出当前设备可用的解析器候选
     *
     * 数据来自云端 manifest（含签名校验），并已按 killSwitch、应用版本区间与灰度比例过滤。
     * 该接口的意义在于：服务端按 schoolId 发布的学校专属脚本能够真正被对应学校的用户执行，
     * 而不是被客户端写死的解析器列表挡在门外。
     *
     * 契约版本门控由调用方负责（调用方才知道自身支持的契约版本）。
     *
     * @param schoolSystemType 教务系统类型，留空则使用本地记录的学校上下文
     * @param schoolId 学校标识，留空则使用本地记录的学校上下文
     * @return 按优先级降序排列的候选列表；manifest 不可用时返回空列表，调用方应回落到内置列表
     */
    suspend fun listParserCandidates(
        schoolSystemType: String = "",
        schoolId: String = ""
    ): List<RemoteScriptDescriptor>

    suspend fun reportScriptParseFeedback(
        scriptName: String,
        category: String = "parsers",
        success: Boolean,
        errorMessage: String? = null,
        sourceUrl: String? = null,
        parseSessionId: String? = null,
        isSessionFinal: Boolean = false,
        finalResult: String? = null,
        failureType: String? = null,
        schoolSystemType: String? = null,
        attemptedParsers: List<String> = emptyList()
    ): Boolean
}
