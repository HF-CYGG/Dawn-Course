package com.dawncourse.feature.import_module.engine

import com.dawncourse.feature.import_module.model.ParsedCourse
import com.dawncourse.feature.import_module.model.SectionRange
import com.dawncourse.feature.import_module.model.XiaoaiCourse
import com.dawncourse.feature.import_module.model.convertXiaoaiCoursesToParsedCourses
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.TextNode
import java.net.URI
import java.net.URLEncoder
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 强智教务系统 API 导入引擎
 *
 * 负责处理强智教务系统的特定导入逻辑，包括：
 * 1. API 认证与课程数据获取
 * 2. HTML 页面解析 (Jsoup)
 * 3. 直连 JSON 数据解析
 * 4. 各种数据格式清洗与标准化
 */
@Singleton
class QiangZhiApiEngine @Inject constructor() {

    /**
     * 强智课程原始数据模型
     * 用于暂存从单元格文本中解析出的单一课程信息
     */
    private data class QiangZhiCourseRaw(
        var name: String = "",
        var teacher: String = "",
        var week: String = "",
        var place: String = ""
    )

    /**
     * 强智 API 登录获取 Token
     */
    fun authUser(baseUrl: String, studentId: String, password: String): String {
        val url = buildUrl(
            baseUrl,
            mapOf(
                "method" to "authUser",
                "xh" to studentId,
                "pwd" to password
            )
        )
        val responseText = requestJson(url, null)
        val json = JSONObject(responseText)
        val token = json.optString("token")
        if (token.isBlank() || token == "-1") {
            val msg = json.optString("msg").ifBlank { "登录失败" }
            throw Exception(msg)
        }
        return token
    }

    /**
     * 强智 API 获取当前学年学期信息
     */
    fun getCurrentTime(baseUrl: String, token: String): JSONObject {
        val today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
        val url = buildUrl(
            baseUrl,
            mapOf(
                "method" to "getCurrentTime",
                "currDate" to today
            )
        )
        val responseText = requestJson(url, token)
        return JSONObject(responseText)
    }

    /**
     * 强智 API 获取指定周课程表
     */
    fun getCourseArray(
        baseUrl: String,
        token: String,
        studentId: String,
        xnxqh: String,
        week: Int
    ): JSONArray {
        val url = buildUrl(
            baseUrl,
            mapOf(
                "method" to "getKbcxAzc",
                "xh" to studentId,
                "xnxqid" to xnxqh,
                "zc" to week.toString()
            )
        )
        val responseText = requestJson(url, token)
        return JSONArray(responseText)
    }

    /**
     * 解析强智 API 返回的课程数组
     */
    fun parseApiCourses(array: JSONArray): List<XiaoaiCourse> {
        val courses = mutableListOf<XiaoaiCourse>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val name = item.optString("kcmc").trim()
            if (name.isBlank()) continue
            val teacher = item.optString("jsxm").trim()
            val location = item.optString("jsmc").trim()
            val weekText = item.optString("kkzc").trim()
            var weeks = parseWeekString(weekText)
            
            // 处理单双周：结合 kkzc 文本和 sjbz 字段
            val sjbz = item.optString("sjbz")
            if (weekText.contains("单") || sjbz == "1") {
                weeks = weeks.filter { it % 2 == 1 }
            } else if (weekText.contains("双") || sjbz == "2") {
                weeks = weeks.filter { it % 2 == 0 }
            }

            // 解析节次
            val sectionText = item.optString("jcs")
                .ifBlank { item.optString("jc") }
                .ifBlank { item.optString("jcsj") }
            var sections = parseSectionListFromText(sectionText)
            
            // 解析星期
            var day = parseDay(item)

            // 如果节次或星期未获取到，尝试解析 kcsj (格式 x0a0b: 星期x 第0a-0b节)
            val kcsj = item.optString("kcsj")
            if ((sections.isEmpty() || day == 0) && kcsj.length >= 5) {
                // 解析星期
                if (day == 0) {
                    day = kcsj.substring(0, 1).toIntOrNull() ?: 0
                }
                // 解析节次
                if (sections.isEmpty()) {
                    val startNode = kcsj.substring(1, 3).toIntOrNull() ?: 0
                    val endNode = kcsj.substring(3, 5).toIntOrNull() ?: 0
                    if (startNode > 0 && endNode >= startNode) {
                        sections = (startNode..endNode).toList()
                    }
                }
            }

            // 兜底：通过时间推断节次
            if (sections.isEmpty()) {
                val range = calculateSectionRangeByTime(
                    startTime = item.optString("kssj"),
                    endTime = item.optString("jssj")
                )
                if (range != null) {
                    val endSection = range.startSection + range.duration - 1
                    sections = (range.startSection..endSection).toList()
                }
            }

            if (day <= 0 || weeks.isEmpty() || sections.isEmpty()) continue
            courses.add(
                XiaoaiCourse(
                    name = name,
                    teacher = teacher,
                    position = location,
                    day = day,
                    weeks = weeks,
                    sections = sections
                )
            )
        }
        return courses
    }

    /**
     * 合并强智 API 课程中的周次信息，避免重复课程段
     */
    fun mergeCourses(courses: List<XiaoaiCourse>): List<XiaoaiCourse> {
        val merged = LinkedHashMap<String, XiaoaiCourse>()
        courses.forEach { course ->
            val key = buildString {
                append(course.name)
                append("|")
                append(course.teacher)
                append("|")
                append(course.position)
                append("|")
                append(course.day)
                append("|")
                append(course.sections.joinToString(","))
            }
            val existing = merged[key]
            if (existing == null) {
                merged[key] = course
            } else {
                val mergedWeeks = (existing.weeks + course.weeks).distinct().sorted()
                merged[key] = existing.copy(weeks = mergedWeeks)
            }
        }
        return merged.values.toList()
    }

    /**
     * 解析强智 API 返回的星期字段
     */
    private fun parseDay(item: JSONObject): Int {
        val day = item.optInt("xqj", item.optInt("day", 0))
        if (day in 1..7) return day
        val dayText = item.optString("xqjmc").ifBlank { item.optString("xq") }
        return parseDayFromText(dayText)
    }

    /**
     * 将文本解析为节次列表
     */
    fun parseSectionListFromText(text: String): List<Int> {
        if (text.isBlank()) return emptyList()
        val normalized = text.replace("第", "")
            .replace("节", "")
            .replace("—", "-")
            .replace("－", "-")
            .replace("～", "-")
            .replace("至", "-")
        val parts = normalized.split(",", "，", "、")
        val sections = mutableListOf<Int>()
        parts.forEach { part ->
            val trimmed = part.trim()
            if (trimmed.isBlank()) return@forEach
            if (trimmed.contains("-")) {
                val range = trimmed.split("-")
                    .mapNotNull { it.trim().toIntOrNull() }
                if (range.size >= 2) {
                    val start = range.first()
                    val end = range.last()
                    if (start > 0 && end >= start) {
                        for (s in start..end) sections.add(s)
                    }
                }
            } else if (trimmed.length >= 4 && trimmed.all { it.isDigit() }) {
                val chunked = trimmed.chunked(2)
                chunked.forEach { chunk ->
                    val value = chunk.toIntOrNull()
                    if (value != null && value > 0) sections.add(value)
                }
            } else {
                val value = trimmed.toIntOrNull()
                if (value != null && value > 0) sections.add(value)
            }
        }
        return sections.distinct().sorted()
    }

    /**
     * 通过时间段推断节次范围
     */
    private fun calculateSectionRangeByTime(
        startTime: String,
        endTime: String?
    ): SectionRange? {
        if (startTime.isBlank()) return null
        return try {
            val formatter = DateTimeFormatter.ofPattern("HH:mm")
            val start = LocalTime.parse(startTime, formatter)
            val end = if (!endTime.isNullOrBlank()) LocalTime.parse(endTime, formatter) else null
            val startSection = when {
                start >= LocalTime.of(19, 0) -> 9
                start >= LocalTime.of(14, 0) -> 5
                else -> 1
            }
            val durationMinutes = if (end != null && end.isAfter(start)) {
                java.time.Duration.between(start, end).toMinutes().toInt()
            } else {
                45
            }
            val sectionCount = kotlin.math.ceil(durationMinutes / 45.0).toInt().coerceAtLeast(1)
            val endSection = (startSection + sectionCount - 1).coerceAtLeast(startSection)
            SectionRange(startSection, endSection)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 将星期文本转换为数字
     */
    fun parseDayFromText(text: String): Int {
        if (text.isBlank()) return 0
        return when {
            text.contains("一") -> 1
            text.contains("二") -> 2
            text.contains("三") -> 3
            text.contains("四") -> 4
            text.contains("五") -> 5
            text.contains("六") -> 6
            text.contains("日") || text.contains("天") || text.contains("七") -> 7
            else -> 0
        }
    }

    /**
     * 构建强智 API 请求地址
     */
    private fun buildUrl(baseUrl: String, params: Map<String, String>): String {
        val query = params.entries.joinToString("&") { (key, value) ->
            val encoded = URLEncoder.encode(value, "UTF-8")
            "$key=$encoded"
        }
        return "$baseUrl?$query"
    }

    /**
     * 发送强智 API 请求
     */
    private fun requestJson(url: String, token: String?): String {
        val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            if (!token.isNullOrBlank()) {
                connection.setRequestProperty("token", token)
            }
            
            // 伪装 User-Agent 防止被简单的反爬虫拦截
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10; SM-G9600 Build/QP1A.190711.020; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/86.0.4240.198 Mobile Safari/537.36")

            val responseCode = connection.responseCode
            val stream = if (responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream ?: connection.inputStream
            }
            val responseText = stream.bufferedReader().use { it.readText() }
            
            // 检查响应内容是否为 HTML
            if (responseText.trimStart().startsWith("<html", ignoreCase = true) || 
                responseText.trimStart().startsWith("<!DOCTYPE html", ignoreCase = true)) {
                
                // 尝试提取网页标题或错误信息
                val title = runCatching { 
                    Jsoup.parse(responseText).title() 
                }.getOrNull() ?: ""
                
                val errorMsg = when {
                    title.contains("登录") -> "登录状态已过期，请重新登录"
                    title.contains("验证") || responseText.contains("人机验证") -> "触发了防火墙验证，请使用网页导入"
                    responseCode == 404 -> "该学校未开放移动端 API，请使用网页导入"
                    responseCode == 500 -> "教务系统服务器内部错误，请稍后重试或使用网页导入"
                    else -> "API 接口返回了网页而非数据，可能是接口已关闭或需要验证。建议使用网页导入。"
                }
                
                throw Exception(errorMsg)
            }
            
            if (responseCode !in 200..299) {
                throw Exception("HTTP Error $responseCode: $responseText")
            }
            return responseText
        } catch (e: Exception) {
            throw e
        } finally {
            connection.disconnect()
        }
    }

    /**
     * 判断是否为强智教务系统域名
     */
    fun isQiangZhiHost(url: String): Boolean {
        if (url.isBlank()) return false
        return try {
            val uri = URI(url)
            val host = (uri.host ?: "").lowercase()
            host.contains("qzdatasoft") ||
                url.contains("/jsxsd/xskb/", ignoreCase = true) ||
                url.contains("xskb_list.do", ignoreCase = true)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 使用 Jsoup 解析强智教务系统课表 HTML
     */
    fun parseHtmlWithJsoup(html: String): List<ParsedCourse> {
        if (html.isBlank()) return emptyList()
        return try {
            val doc = Jsoup.parse(html)
            val kbContents = doc.select(".kbcontent")
            if (kbContents.isEmpty()) return emptyList()

            val xiaoaiCourses = mutableListOf<XiaoaiCourse>()

            for (element in kbContents) {
                val id = element.id()
                if (id.isBlank()) continue
                // id 一般形如 td12，取第一个数字作为星期几
                val digits = id.filter { it.isDigit() }
                if (digits.isEmpty()) continue
                val day = digits.first().digitToIntOrNull() ?: continue
                if (day !in 1..7) continue

                // 按分隔线拆分同一单元格中的多门课程
                val blocks = element.html().split("---------------------")
                for (block in blocks) {
                    val blockHtml = block.trim()
                    if (blockHtml.isEmpty()) continue

                    val tempDoc = Jsoup.parseBodyFragment(blockHtml)
                    val body = tempDoc.body()

                    // 1. 尝试提取课程名 (第一个非空文本节点)
                    var name = ""
                    val allTextNodes = body.textNodes().filter { it.text().trim().isNotBlank() }
                    
                    // 优先使用 font 标签解析，如果存在
                    val fonts = body.select("font")
                    if (fonts.isNotEmpty()) {
                        // 策略 A: 有 title 属性 (标准强智)
                        val teacherNode = fonts.firstOrNull { it.attr("title") == "老师" }
                        val roomNode = fonts.firstOrNull { it.attr("title") == "教室" }
                        val weekNode = fonts.firstOrNull { it.attr("title") == "周次(节次)" }

                        if (teacherNode != null || roomNode != null || weekNode != null) {
                            name = body.ownText().trim()
                            if (name.isBlank() && allTextNodes.isNotEmpty()) {
                                name = allTextNodes.first().text().trim()
                            }
                            
                            val teacher = teacherNode?.text()?.trim().orEmpty()
                            val room = roomNode?.text()?.trim().orEmpty()
                            val weekRaw = weekNode?.text()?.trim().orEmpty()
                            
                            parseAndAddCourse(xiaoaiCourses, name, teacher, room, weekRaw, day)
                            continue
                        }
                        
                        // 策略 B: 无 title 属性，按 font 顺序 (参考 MI_AI_Course_Schedule-main)
                        // 通常顺序: 0:老师, 1:周次(节次), 2:教室
                        // 课程名通常在 font 之前
                        name = body.ownText().trim()
                        if (name.isBlank()) {
                            // 尝试获取第一个 font 之前的文本
                            val firstFont = fonts[0]
                            val prev = firstFont.previousSibling()
                            if (prev is TextNode) {
                                name = prev.text().trim()
                            }
                        }
                        if (name.isBlank() && allTextNodes.isNotEmpty()) {
                            name = allTextNodes.first().text().trim()
                        }

                        val teacher = fonts.getOrNull(0)?.text()?.trim().orEmpty()
                        val weekRaw = fonts.getOrNull(1)?.text()?.trim().orEmpty()
                        val room = fonts.getOrNull(2)?.text()?.trim().orEmpty()

                        parseAndAddCourse(xiaoaiCourses, name, teacher, room, weekRaw, day)
                        continue
                    }

                    // 策略 C: 纯文本解析 (无 font 标签)
                    // 假设顺序: 课程名 -> 老师 -> 周次 -> 教室
                    val lines = Jsoup.parse(blockHtml.replace("<br>", "\n")).text().split("\n").map { it.trim() }.filter { it.isNotBlank() }
                    if (lines.isNotEmpty()) {
                        name = lines[0]
                        var teacher = ""
                        var room = ""
                        var weekRaw = ""
                        
                        // 简单的启发式匹配
                        for (i in 1 until lines.size) {
                            val line = lines[i]
                            if (line.contains("周") || line.contains("节")) {
                                weekRaw = line
                            } else if (line.contains("室") || line.contains("楼") || line.contains("区")) {
                                room = line
                            } else {
                                if (teacher.isBlank()) teacher = line
                            }
                        }
                        // 如果启发式匹配失败，回退到固定位置
                        if (weekRaw.isBlank() && lines.size >= 3) {
                             teacher = lines.getOrElse(1) { "" }
                             weekRaw = lines.getOrElse(2) { "" }
                             room = lines.getOrElse(3) { "" }
                        }
                        
                        parseAndAddCourse(xiaoaiCourses, name, teacher, room, weekRaw, day)
                    }
                }
            }

            if (xiaoaiCourses.isEmpty()) {
                emptyList()
            } else {
                convertXiaoaiCoursesToParsedCourses(xiaoaiCourses)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseAndAddCourse(
        list: MutableList<XiaoaiCourse>,
        name: String,
        teacher: String,
        room: String,
        weekRaw: String,
        day: Int
    ) {
        if (name.isBlank() || name == "&nbsp;") return
        if (weekRaw.isBlank()) return

        // 解析节次 [03-04节]
        val sections = mutableListOf<Int>()
        val sectionRegex = "\\[(\\d+)-(\\d+)节]".toRegex()
        val sectionMatch = sectionRegex.find(weekRaw)
        if (sectionMatch != null) {
            val start = sectionMatch.groupValues[1].toIntOrNull() ?: 0
            val end = sectionMatch.groupValues[2].toIntOrNull() ?: 0
            if (start > 0 && end >= start) {
                for (s in start..end) {
                    sections.add(s)
                }
            }
        }

        // 解析周次 1-16 或 1-8,10-16
        val weeks = mutableListOf<Int>()
        val weekStr = weekRaw.substringBefore("(").trim()
        if (weekStr.isNotEmpty()) {
            val parts = weekStr.split(",", "，")
            for (part in parts) {
                val token = part.trim()
                if (token.isEmpty()) continue
                if (token.contains("-")) {
                    val rangeParts = token.split("-")
                    val startWeek = rangeParts.getOrNull(0)?.trim()?.toIntOrNull() ?: continue
                    val endWeek = rangeParts.getOrNull(1)?.trim()?.toIntOrNull() ?: continue
                    if (startWeek > 0 && endWeek >= startWeek) {
                        for (w in startWeek..endWeek) {
                            weeks.add(w)
                        }
                    }
                } else {
                    val week = token.toIntOrNull()
                    if (week != null && week > 0) weeks.add(week)
                }
            }
        }

        if (weeks.isNotEmpty() && sections.isNotEmpty()) {
            list.add(
                XiaoaiCourse(
                    name = name,
                    teacher = if (teacher.isNotBlank()) teacher else "未知教师",
                    position = if (room.isNotBlank()) room else "未知教室",
                    day = day,
                    weeks = weeks.distinct().sorted(),
                    sections = sections.distinct().sorted()
                )
            )
        }
    }

    /**
     * 标准化强智 API 基础地址
     */
    fun normalizeBaseUrl(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        if (trimmed.isBlank()) return ""
        val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            "https://$trimmed"
        }
        return try {
            val uri = URI(withScheme)
            val host = uri.host ?: return ""
            val scheme = uri.scheme ?: "https"
            val port = if (uri.port > 0) ":${uri.port}" else ""
            "$scheme://$host$port/app.do"
        } catch (_: Exception) {
            ""
        }
    }

    /**
     * 解析强智教务系统直连 JSON 数据
     */
    fun parseJson(json: String): List<ParsedCourse> {
        val root = JSONObject(json)
        val data = root.optJSONArray("data") ?: return emptyList()
        val xiaoaiCourses = mutableListOf<XiaoaiCourse>()

        for (i in 0 until data.length()) {
            val item = data.getJSONObject(i)
            // 强智系统的节次映射：row 1 -> 第1大节 (1-2节)
            val row = item.optInt("row") // 1..5
            // 强智系统的周次映射：col 1 -> 周一
            val col = item.optInt("col") // 1..7 (Week)
            val text = item.optString("text")
            
            if (text.isBlank()) continue

            // 解析文本内容 (格式通常为: 课程名 教师 周次 地点)
            val rawCourses = parseText(text)
            
            rawCourses.forEach { c ->
                // 计算起始节次：(大节 - 1) * 2 + 1
                // 例如：第1大节 -> 1节, 第2大节 -> 3节
                val startSection = (row - 1) * 2 + 1
                val sections = listOf(startSection, startSection + 1)
                
                // 解析周次字符串 (例如 "1-16周", "1-8,10-16周")
                val weekList = parseWeekString(c.week)
                
                if (weekList.isNotEmpty()) {
                    xiaoaiCourses.add(
                        XiaoaiCourse(
                            name = c.name,
                            teacher = c.teacher,
                            position = c.place,
                            day = col,
                            weeks = weekList,
                            sections = sections
                        )
                    )
                }
            }
        }
        return convertXiaoaiCoursesToParsedCourses(xiaoaiCourses)
    }

    /**
     * 解析强智教务系统单元格文本
     */
    private fun parseText(info: String): List<QiangZhiCourseRaw> {
        val list = mutableListOf<QiangZhiCourseRaw>()
        var index = 0
        val length = info.length
        
        while (index < length) {
            var segNum = 1 // 当前解析字段序号 (1:Name, 2:Teacher, 3:Week, 4:Place)
            val infoSeg = StringBuilder()
            val course = QiangZhiCourseRaw()
            
            // 循环解析一门课程的 4 个字段
            while (segNum <= 4 && index <= length) {
                // 读取当前字符，如果已到末尾则模拟为空格以触发结束逻辑
                val ch = if (index == length) ' ' else info[index]
                index++
                
                if (!ch.isWhitespace()) {
                    infoSeg.append(ch)
                } else {
                    // 遇到空白字符，且缓冲区有内容，说明一个字段解析完成
                    if (infoSeg.isNotEmpty()) {
                         val strSeg = infoSeg.toString()
                         when (segNum) {
                             1 -> course.name = strSeg
                             2 -> course.teacher = strSeg
                             3 -> course.week = strSeg
                             4 -> course.place = strSeg
                         }
                         segNum++
                         infoSeg.clear()
                    }
                }
            }
            // 只有解析出课程名才视为有效课程
            if (course.name.isNotEmpty()) {
                list.add(course)
            }
        }
        return list
    }
    
    /**
     * 解析周次字符串
     */
    fun parseWeekString(weekStr: String): List<Int> {
        val weeks = mutableListOf<Int>()
        var index = 0
        val len = weekStr.length
        while (index < len) {
            // 跳过非数字字符 (如 "周", "(", "," 等)
            while (index < len && !weekStr[index].isDigit()) {
                index++
            }
            if (index >= len) break

            // 提取第一个数字 (起始周或单周)
            var l = 0
            while (index < len && weekStr[index].isDigit()) {
                l = l * 10 + (weekStr[index] - '0')
                index++
            }
            
            // 检查是否为区间格式 (例如 "1-16")
            if (index < len && weekStr[index] == '-') {
                index++
                // 提取第二个数字 (结束周)
                var r = 0
                while (index < len && weekStr[index].isDigit()) {
                    r = r * 10 + (weekStr[index] - '0')
                    index++
                }
                if (r > 0) {
                    for (i in l..r) weeks.add(i)
                }
            } else {
                // 单周格式
                if (l > 0) weeks.add(l)
            }
        }
        return weeks
    }
}
