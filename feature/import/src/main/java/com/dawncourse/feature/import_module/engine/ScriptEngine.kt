package com.dawncourse.feature.import_module.engine

import app.cash.quickjs.QuickJs
import javax.inject.Inject
import javax.inject.Singleton

/**
 * JS 脚本引擎管理器
 *
 * 负责 QuickJS 实例的创建、管理和销毁。
 * 封装了 QuickJS 的底层操作，提供更友好的执行接口。
 */
@Singleton
class ScriptEngine @Inject constructor() {

    /**
     * 执行 JS 脚本并返回解析结果
     *
     * @param script JS 脚本内容
     * @param html 待解析的 HTML 内容
     * @return 解析后的 JSON 字符串（后续可反序列化为 Course 列表）
     */
    fun parseHtml(script: String, html: String): String {
        // 创建 QuickJS 上下文
        val quickJs = QuickJs.create()
        
        return try {
            // 1. 注入解析所需的上下文对象 (可选)
            // quickJs.set("context", ...) 
            
            // 2. 编译并执行脚本
            // 假设脚本中定义了一个全局函数 parse(html: String): String
            quickJs.evaluate(script)
            
            // 3. 调用解析函数
            // 注意：这里需要 JS 脚本必须提供一个名为 'main' 或 'parse' 的入口函数
            // 为了通用性，我们约定脚本必须返回一个函数，或者定义一个名为 'parse' 的全局函数
            
            // 获取全局对象中的 parse 方法
            // 方式一：直接执行调用语句
            // val result = quickJs.evaluate("parse('$html')") 
            
            // 方式二：更安全的方式是将 HTML 作为参数传递，避免字符串拼接带来的转义问题
            // 这里我们定义一个简单的接口映射
            val parser = quickJs.get(ParserInterface::class.java, "globalThis")
            parser.parse(html)
            
        } catch (e: Exception) {
            e.printStackTrace()
            throw RuntimeException("Script execution failed: ${e.message}")
        } finally {
            // 必须释放 QuickJS 资源，防止内存泄漏
            quickJs.close()
        }
    }
    
    /**
     * JS 脚本与 Kotlin 交互的接口定义
     * 脚本环境中的 globalThis 必须实现此接口（即定义对应的 parse 函数）
     */
    interface ParserInterface {
        /**
         * 解析 HTML 字符串
         * @param html 教务系统页面的 HTML 源码
         * @return 解析结果的 JSON 字符串
         */
        fun parse(html: String): String
    }
}
