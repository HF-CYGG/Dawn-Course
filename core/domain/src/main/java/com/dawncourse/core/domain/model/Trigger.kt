package com.dawncourse.core.domain.model

import java.net.URI
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.Locale

/** 课程系统触发器的业务种类。 */
enum class TriggerKind {
    /** 课前通知提醒。 */
    REMINDER,

    /** 课程开始时由应用请求静音。 */
    MUTE,

    /** 课程结束时安全恢复应用所有的静音会话。 */
    UNMUTE
}

/** AlarmManager 实际采用的触发精度。 */
enum class TriggerPrecision {
    /** 已使用精确闹钟。 */
    EXACT,

    /** 受权限或系统能力限制，已降级为非精确闹钟。 */
    INEXACT
}

/**
 * 一次课程触发器的稳定身份。
 *
 * [LEGACY_PROFILE_ID] 仅用于 v6 Profile 上线前的兼容过渡，不代表伪造的 Profile 记录。
 */
data class TriggerKey(
    /** Profile 业务 ID；[LEGACY_PROFILE_ID] 只用于旧注册表的识别与清理。 */
    val profileId: Long,
    /** 课程业务 ID。 */
    val courseId: Long,
    /** 该课程真实发生的本地日期。 */
    val occurrenceDate: LocalDate,
    /** 触发器种类。 */
    val kind: TriggerKind
) {
    companion object {
        /** v6 Profile 迁移前唯一允许的保留 Profile ID。 */
        const val LEGACY_PROFILE_ID: Long = 0L
    }
}

/** 领域层计算得到的期望触发器。 */
data class DesiredTrigger(
    /** 稳定触发器身份。 */
    val key: TriggerKey,
    /** 绝对触发时刻。 */
    val triggerAt: Instant
)

/** 本地注册表中记录的已下发触发器。 */
data class ScheduledTrigger(
    /** 稳定触发器身份。 */
    val key: TriggerKey,
    /** 已下发的绝对触发时刻。 */
    val triggerAt: Instant,
    /** 系统实际应用的精度。 */
    val precision: TriggerPrecision
)

/** Desired 与 Scheduled 之间的可执行差异。 */
data class TriggerDiff(
    /** 需要新建或覆盖的触发器。 */
    val add: List<DesiredTrigger>,
    /** 可原样保留的已注册触发器。 */
    val keep: List<ScheduledTrigger>,
    /** 需要移除或被同 Key 覆盖的旧记录。 */
    val remove: List<ScheduledTrigger>
)

/**
 * [TriggerKey] 的稳定 URI 协议。
 *
 * 固定格式为 `dawn://alarm/{profile}/{course}/{yyyy-MM-dd}/{kind}`，PendingIntent 身份不再依赖
 * requestCode 或 Long 截断。
 */
object TriggerUriCodec {
    private const val SCHEME = "dawn"
    private const val HOST = "alarm"

    /** 把触发器 Key 编码为唯一 URI。 */
    fun encode(key: TriggerKey): String = buildString {
        append("dawn://alarm/")
        append(key.profileId)
        append('/')
        append(key.courseId)
        append('/')
        append(key.occurrenceDate)
        append('/')
        append(key.kind.name.lowercase(Locale.ROOT))
    }

    /** 解码并严格校验触发器 URI，任一字段异常时返回 null。 */
    fun decode(value: String?): TriggerKey? {
        if (value.isNullOrBlank()) return null
        val uri = try {
            URI(value)
        } catch (_: Exception) {
            return null
        }
        if (uri.scheme != SCHEME || uri.host != HOST) return null
        if (uri.userInfo != null || uri.port != -1 || uri.query != null || uri.fragment != null) return null
        val rawPath = uri.rawPath ?: return null
        if (rawPath != uri.path) return null
        val segments = rawPath.removePrefix("/").split('/')
        if (segments.size != 4 || segments.any { segment -> segment.isBlank() }) return null
        val profileId = segments[0].toLongOrNull()?.takeIf { id -> id >= 0L } ?: return null
        val courseId = segments[1].toLongOrNull()?.takeIf { id -> id > 0L } ?: return null
        val date = try {
            LocalDate.parse(segments[2])
        } catch (_: DateTimeParseException) {
            return null
        }
        val kind = TriggerKind.entries.firstOrNull { candidate ->
            candidate.name.equals(segments[3], ignoreCase = true)
        } ?: return null
        return TriggerKey(profileId, courseId, date, kind)
    }
}

/** 触发器安全下发顺序与确定性排序。 */
object TriggerOrdering {
    /** 在其他动作前先确保恢复动作存在。 */
    private fun kindOrder(kind: TriggerKind): Int = when (kind) {
        TriggerKind.UNMUTE -> 0
        TriggerKind.REMINDER -> 1
        TriggerKind.MUTE -> 2
    }

    /** Key 集合的稳定排序器。 */
    val keyComparator: Comparator<TriggerKey> = compareBy<TriggerKey>(
        { key -> kindOrder(key.kind) },
        { key -> key.profileId },
        { key -> key.courseId },
        { key -> key.occurrenceDate }
    )

    /** Desired 列表的稳定排序器。 */
    val desiredComparator: Comparator<DesiredTrigger> = compareBy<DesiredTrigger>(
        { trigger -> kindOrder(trigger.key.kind) },
        { trigger -> trigger.triggerAt },
        { trigger -> trigger.key.profileId },
        { trigger -> trigger.key.courseId },
        { trigger -> trigger.key.occurrenceDate }
    )

    /** Scheduled 列表的稳定排序器。 */
    val scheduledComparator: Comparator<ScheduledTrigger> = compareBy<ScheduledTrigger>(
        { trigger -> kindOrder(trigger.key.kind) },
        { trigger -> trigger.triggerAt },
        { trigger -> trigger.key.profileId },
        { trigger -> trigger.key.courseId },
        { trigger -> trigger.key.occurrenceDate }
    )
}
