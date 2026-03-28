package com.dawncourse.core.domain.repository

/**
 * 脚本同步仓库接口
 * 用于从云端拉取最新的解析与导航脚本，应对教务系统的频繁变动。
 */
interface ScriptSyncRepository {
    /**
     * 获取指定名称的脚本内容
     * @param scriptName 脚本文件名，例如 "zhengfang.js" 或 "zf_nav.js"
     * @param category 脚本分类，如 "parsers" 或 "js"
     * @return 脚本的完整字符串内容
     */
    suspend fun getScript(scriptName: String, category: String = "js"): String
    
    /**
     * 强制从云端更新脚本
     * @param scriptName 脚本文件名
     * @param category 脚本分类
     * @return 更新后的脚本内容，若失败则返回本地缓存或 assets 默认内容
     */
    suspend fun fetchAndCacheScript(scriptName: String, category: String = "js"): String
}
