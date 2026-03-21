package com.dawncourse.core.data.repository

import androidx.room.withTransaction
import com.dawncourse.core.data.local.AppDatabase
import com.dawncourse.core.domain.model.Course
import com.dawncourse.core.domain.model.SyncCredentialType
import com.dawncourse.core.domain.model.SyncErrorCode
import com.dawncourse.core.domain.model.SyncProviderType
import com.dawncourse.core.domain.model.TimetableSyncResult
import com.dawncourse.core.domain.repository.CourseRepository
import com.dawncourse.core.domain.repository.CredentialsRepository
import com.dawncourse.core.domain.repository.SemesterRepository
import com.dawncourse.core.domain.repository.TimetableSyncRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/**
 * 课表同步仓库实现（WakeUp 提供者）
 *
 * 仅实现 WAKEUP 类型的自动更新：使用口令（TOKEN）拉取课程数据并替换当前学期的课程。
 */
@Singleton
class TimetableSyncRepositoryImpl @Inject constructor(
    private val credentialsRepository: CredentialsRepository,
    private val semesterRepository: SemesterRepository,
    private val courseRepository: CourseRepository,
    private val database: AppDatabase
) : TimetableSyncRepository {

    override suspend fun syncCurrentSemester(): TimetableSyncResult {
        val creds = credentialsRepository.getCredentials()
            ?: return TimetableSyncResult.Failure(
                code = SyncErrorCode.NO_CREDENTIALS,
                message = "未绑定账号或口令"
            )

        return when (creds.provider) {
            SyncProviderType.WAKEUP -> {
                syncWakeUp(creds)
            }
            SyncProviderType.QIDI, SyncProviderType.ZF -> {
                TimetableSyncResult.Failure(
                    code = SyncErrorCode.AUTH_FAILED,
                    message = "当前绑定为教务账号，请在主界面使用“自动更新”进入同步页面"
                )
            }
            SyncProviderType.WEBDAV -> {
                TimetableSyncResult.Failure(
                    code = SyncErrorCode.AUTH_FAILED,
                    message = "当前绑定为 WebDAV 账号，请在设置页使用 WebDAV 同步"
                )
            }
        }
    }

    private suspend fun syncWakeUp(creds: com.dawncourse.core.domain.model.SyncCredentials): TimetableSyncResult {
        if (creds.type != SyncCredentialType.TOKEN) {
            return TimetableSyncResult.Failure(
                code = SyncErrorCode.AUTH_FAILED,
                message = "凭据类型错误（需 TOKEN/口令）"
            )
        }
        val token = creds.secret

        val currentSemester = semesterRepository.getCurrentSemester().first()
            ?: return TimetableSyncResult.Failure(
                code = SyncErrorCode.SERVER_ERROR,
                message = "未设置当前学期"
            )

        val parsedCourses = try {
            fetchWakeUpCourses(token)
        } catch (e: java.net.SocketTimeoutException) {
            return TimetableSyncResult.Failure(SyncErrorCode.NETWORK_ERROR, "网络超时")
        } catch (e: java.io.IOException) {
            return TimetableSyncResult.Failure(SyncErrorCode.NETWORK_ERROR, "网络异常：${e.message}")
        } catch (e: org.json.JSONException) {
            return TimetableSyncResult.Failure(SyncErrorCode.PARSE_ERROR, "数据解析失败")
        } catch (e: Exception) {
            return TimetableSyncResult.Failure(SyncErrorCode.UNKNOWN, "未知错误：${e.message}")
        }

        // 映射为领域模型
        val domainCourses = parsedCourses.map { rc ->
            Course(
                semesterId = currentSemester.id,
                name = rc.name,
                teacher = rc.teacher,
                location = rc.location,
                dayOfWeek = rc.dayOfWeek,
                startSection = rc.startSection,
                duration = rc.duration,
                startWeek = rc.startWeek,
                endWeek = rc.endWeek
            )
        }

        // 原子替换：删除当前学期课程 -> 插入新课程
        database.withTransaction {
            courseRepository.deleteCoursesBySemester(currentSemester.id)
            if (domainCourses.isNotEmpty()) {
                courseRepository.insertCourses(domainCourses)
            }
        }

        return TimetableSyncResult.Success(
            updatedCount = domainCourses.size,
            message = "已更新 ${domainCourses.size} 门课程"
        )
    }

    /**
     * 远端结果的简化模型（仅保留映射所需字段）
     */
    private data class RawCourse(
        val name: String,
        val teacher: String,
        val location: String,
        val dayOfWeek: Int,
        val startSection: Int,
        val duration: Int,
        val startWeek: Int,
        val endWeek: Int
    )

    private fun fetchWakeUpCourses(token: String): List<RawCourse> {
        val urlStr = "https://i.wakeup.fun/share_schedule/get?key=$token"
        val url = java.net.URL(urlStr)
        val conn = url.openConnection() as java.net.HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("User-Agent", "okhttp/3.14.9")
        conn.setRequestProperty("version", "243")
        conn.connectTimeout = 10000
        conn.readTimeout = 10000

        if (conn.responseCode != 200) {
            if (conn.responseCode == 401 || conn.responseCode == 403) {
                throw IllegalStateException("AUTH")
            }
            throw java.io.IOException("HTTP ${conn.responseCode}")
        }
        val responseText = conn.inputStream.bufferedReader().use { it.readText() }

        val rootJson = org.json.JSONObject(responseText)
        if (rootJson.optInt("code") != 200) {
            val msg = rootJson.optString("msg")
            throw java.io.IOException("API Error: $msg")
        }
        val dataStr = rootJson.optString("data")
        val parts = dataStr.split("\n")
        if (parts.size < 5) {
            throw org.json.JSONException("invalid parts")
        }

        // 课程名称映射 (parts[3])
        val nameArray = org.json.JSONArray(parts[3])
        val nameMap = mutableMapOf<Int, String>()
        for (i in 0 until nameArray.length()) {
            val item = nameArray.getJSONObject(i)
            val id = item.optInt("id")
            val name = item.optString("courseName")
            nameMap[id] = name
        }

        // 课程明细 (parts[4])
        val rawCourses = org.json.JSONArray(parts[4])
        val result = mutableListOf<RawCourse>()
        for (i in 0 until rawCourses.length()) {
            val item = rawCourses.getJSONObject(i)
            val id = item.optInt("id")
            val name = nameMap[id] ?: "-"
            val room = item.optString("room", "-")
            val teacher = item.optString("teacher", "-")
            val startWeek = item.optInt("startWeek")
            val endWeek = item.optInt("endWeek")
            val day = item.optInt("day")
            val startNode = item.optInt("startNode")
            val step = item.optInt("step")

            result.add(
                RawCourse(
                    name = name,
                    teacher = teacher,
                    location = room,
                    dayOfWeek = day,
                    startSection = startNode,
                    duration = step,
                    startWeek = startWeek,
                    endWeek = endWeek
                )
            )
        }
        return result
    }
}
