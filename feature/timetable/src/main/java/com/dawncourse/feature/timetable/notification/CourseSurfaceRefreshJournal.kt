package com.dawncourse.feature.timetable.notification

import android.content.Context
import java.util.UUID
import kotlinx.coroutines.CancellationException

/** 课程状态边界 Alarm 被消费后，直到 Surface 刷新成功前都必须保留的持久责任。 */
internal interface CourseSurfaceRefreshJournal {
    fun pendingToken(): String?
    fun markPending(): String?
    fun clearPendingIfMatches(token: String): Boolean
}

/** 独立 SharedPreferences token；以代际 compare-and-clear 避免旧 Worker 确认新边界。 */
internal class AppCourseSurfaceRefreshJournal(context: Context) : CourseSurfaceRefreshJournal {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    override fun pendingToken(): String? = synchronized(MARKER_LOCK) {
        preferences.getString(KEY_REFRESH_TOKEN, null)?.takeIf(String::isNotBlank)
    }

    override fun markPending(): String? = synchronized(MARKER_LOCK) {
        val token = UUID.randomUUID().toString()
        token.takeIf { preferences.edit().putString(KEY_REFRESH_TOKEN, token).commit() }
    }

    override fun clearPendingIfMatches(token: String): Boolean = synchronized(MARKER_LOCK) {
        val current = preferences.getString(KEY_REFRESH_TOKEN, null)
        if (current != token) return@synchronized true
        preferences.edit().remove(KEY_REFRESH_TOKEN).commit()
    }

    private companion object {
        const val PREFERENCES_NAME = "dc_course_surface_refresh"
        const val KEY_REFRESH_TOKEN = "refresh_token"
        val MARKER_LOCK = Any()
    }
}

internal data class CourseSurfaceRefreshClaim(
    val token: String?,
    val readFailed: Boolean,
) {
    val isPending: Boolean get() = token != null || readFailed
}

internal fun captureCourseSurfaceRefreshClaim(
    journal: CourseSurfaceRefreshJournal,
): CourseSurfaceRefreshClaim = runCatching {
    CourseSurfaceRefreshClaim(token = journal.pendingToken(), readFailed = false)
}.getOrElse {
    CourseSurfaceRefreshClaim(token = null, readFailed = true)
}

/** marker 与 WorkManager 任一路径成功即可接管一次已消费的课程边界 Alarm。 */
internal suspend fun persistAndEnqueueCourseSurfaceRefresh(
    journal: CourseSurfaceRefreshJournal,
    enqueue: suspend () -> Boolean,
): Boolean {
    val markerPersisted = runCatching { journal.markPending() != null }.getOrDefault(false)
    val workEnqueued = try {
        enqueue()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        false
    }
    return markerPersisted || workEnqueued
}

/** 只有本轮 Surface 确认刷新成功且责任代际未变化时才允许清理。 */
internal fun acknowledgeCourseSurfaceRefresh(
    journal: CourseSurfaceRefreshJournal,
    claim: CourseSurfaceRefreshClaim,
    surfaceRefreshed: Boolean,
): Boolean {
    if (claim.readFailed) return false
    val token = claim.token ?: return true
    if (!surfaceRefreshed) return true
    return runCatching { journal.clearPendingIfMatches(token) }.getOrDefault(false)
}
