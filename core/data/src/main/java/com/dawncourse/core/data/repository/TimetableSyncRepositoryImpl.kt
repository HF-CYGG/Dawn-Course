package com.dawncourse.core.data.repository

import com.dawncourse.core.data.local.AppDatabase
import com.dawncourse.core.domain.model.Course
import com.dawncourse.core.domain.model.SyncCredentialType
import com.dawncourse.core.domain.model.SyncCredentials
import com.dawncourse.core.domain.model.SyncProviderType
import com.dawncourse.core.domain.model.TimetableSyncResult
import com.dawncourse.core.domain.repository.CourseRepository
import com.dawncourse.core.domain.repository.CredentialsRepository
import com.dawncourse.core.domain.repository.SemesterRepository
import com.dawncourse.core.domain.repository.TimetableSyncRepository
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min

@Singleton
class TimetableSyncRepositoryImpl @Inject constructor(
    private val credentialsRepository: CredentialsRepository,
    private val semesterRepository: SemesterRepository,
    private val courseRepository: CourseRepository,
    private val database: AppDatabase
) : TimetableSyncRepository {

    override suspend fun syncCurrentSemester(): TimetableSyncResult = withContext(Dispatchers.IO) {
        val currentSemester = semesterRepository.getCurrentSemester().firstOrNull()
            ?: return@withContext TimetableSyncResult.Failure("未设置当前学期")
        
        val credentials = credentialsRepository.getCredentials()
            ?: return@withContext TimetableSyncResult.Failure("未绑定同步账号")

        return@withContext when (credentials.provider) {
            SyncProviderType.WAKEUP -> syncWakeUp(credentials, currentSemester.id)
            else -> TimetableSyncResult.Failure("暂不支持该教务系统同步: ${credentials.provider.name}")
        }
    }

    private suspend fun syncWakeUp(
        credentials: SyncCredentials,
        semesterId: Long
    ): TimetableSyncResult {
        return try {
            val rawCourses = fetchWakeUpCourses(credentials.secret)
            if (rawCourses.isEmpty()) {
                return TimetableSyncResult.Success("未获取到任何课程数据")
            }

            // 转换为领域模型
            val newCourses = rawCourses.map { raw ->
                Course(
                    id = 0, // 自增 ID
                    semesterId = semesterId,
                    name = raw.name,
                    teacher = raw.teacher,
                    location = raw.room,
                    dayOfWeek = raw.day,
                    startSection = raw.startNode,
                    duration = raw.step,
                    startWeek = raw.startWeek,
                    endWeek = raw.endWeek,
                    weekType = Course.WEEK_TYPE_ALL, // 默认为全周
                    color = generateRandomColor(),
                    originId = 0, // 默认为 0
                    isModified = false,
                    note = ""
                )
            }

            // 执行数据库事务：清空当前学期旧数据 -> 插入新数据
            // 注意：这里简单粗暴地清空重写，会丢失用户手动修改的信息。
            // 但考虑到是“同步”，通常意味着以服务端为准。
            val courseDao = database.courseDao()
            // 获取当前学期所有课程并删除
            // 由于 Dao 没有直接 deleteBySemester，我们先查询再逐个删除，或者使用 RawQuery (更高效但这里为了安全先用 Dao)
            // 实际上为了性能，这里应该在 Dao 加一个 deleteBySemesterId，但为了不改动 Dao 接口导致重编译过多，先这样实现。
            val existingCourses = courseDao.getCoursesBySemester(semesterId).first()
            
            // 使用 Room 事务保证原子性
            database.withTransaction {
                existingCourses.forEach { entity ->
                    courseDao.deleteCourseById(entity.id)
                }
                // 批量插入需要转换成 Entity，但 CourseRepository 接口操作的是 Model。
                // 这里我们直接调用 Repository 的 insertCourses 方法，它会处理转换。
                // 但 Repository 方法是 suspend，不能直接在 Room 事务块中调用（除非 Room 支持 suspend 事务块，Room 2.1+ 支持）
                // 我们的 withTransaction 是 suspend 函数，所以可以直接调用 suspend Dao 方法。
                // 但 Repository 方法可能包装了 Dao 方法。
                // 为了简单，我们直接调用 Repository。
                // 但 withTransaction 需要 database 实例。
                // 我们可以直接用 Dao 插入。
                // 但我们需要把 Model 转 Entity。这在 Data 层是可以做的，但 Entity 是 internal 的吗？
                // CourseEntity 是 public 的。
                // 让我们直接用 Repository 的 insertCourses。
                courseRepository.insertCourses(newCourses)
            }

            TimetableSyncResult.Success("已更新 ${newCourses.size} 门课程")
        } catch (e: Exception) {
            e.printStackTrace()
            TimetableSyncResult.Failure(e.message ?: "同步失败")
        }
    }

    private fun fetchWakeUpCourses(key: String): List<RawCourse> {
        val urlString = "https://i.wakeup.fun/share_schedule/get?key=$key"
        val url = URL(urlString)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("User-Agent", "okhttp/3.14.9")
        conn.setRequestProperty("version", "243")
        conn.connectTimeout = 10000
        conn.readTimeout = 10000

        val responseCode = conn.responseCode
        if (responseCode == 401 || responseCode == 403) {
            throw IllegalStateException("AUTH") // 对应 bytecode 的 "AUTH"
        }
        if (responseCode != 200) {
            throw java.io.IOException("HTTP $responseCode")
        }

        val responseBody = conn.inputStream.use { stream ->
            BufferedReader(InputStreamReader(stream)).readText()
        }

        val json = JSONObject(responseBody)
        val code = json.optInt("code")
        if (code != 200) {
            val msg = json.optString("msg")
            throw java.io.IOException("API Error: $msg")
        }

        val dataStr = json.optString("data")
        if (dataStr.isBlank()) return emptyList()

        // 解析数据格式：Split by \n
        // Part 3: Course names map
        // Part 4: Schedule list
        val parts = dataStr.split("\n", limit = 6)
        if (parts.size < 5) {
            throw org.json.JSONException("invalid parts")
        }

        val namesJson = JSONArray(parts[3])
        val courseNameMap = mutableMapOf<Int, String>()
        for (i in 0 until namesJson.length()) {
            val item = namesJson.getJSONObject(i)
            val id = item.optInt("id")
            val name = item.optString("courseName")
            courseNameMap[id] = name
        }

        val scheduleJson = JSONArray(parts[4])
        val result = ArrayList<RawCourse>()

        for (i in 0 until scheduleJson.length()) {
            val item = scheduleJson.getJSONObject(i)
            val id = item.optInt("id")
            val name = courseNameMap[id] ?: "-"
            
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
                    room = room,
                    day = day,
                    startNode = startNode,
                    step = step,
                    startWeek = startWeek,
                    endWeek = endWeek
                )
            )
        }
        return result
    }

    private data class RawCourse(
        val name: String,
        val teacher: String,
        val room: String,
        val day: Int,
        val startNode: Int,
        val step: Int,
        val startWeek: Int,
        val endWeek: Int
    )

    private fun generateRandomColor(): String {
        // Material Colors 500
        val colors = listOf(
            "#F44336", "#E91E63", "#9C27B0", "#673AB7", "#3F51B5",
            "#2196F3", "#03A9F4", "#00BCD4", "#009688", "#4CAF50",
            "#8BC34A", "#CDDC39", "#FFEB3B", "#FFC107", "#FF9800",
            "#FF5722", "#795548", "#9E9E9E", "#607D8B"
        )
        return colors.random()
    }
}
