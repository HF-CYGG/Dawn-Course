package com.dawncourse.core.domain.usecase

import com.dawncourse.core.domain.model.Course
import com.dawncourse.core.domain.model.SectionTime
import com.dawncourse.core.domain.model.Semester
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import javax.inject.Inject

/**
 * 将课程表转换为标准 iCalendar (ICS) 格式的用例
 *
 * 该用例负责将业务模型的课程数据、学期起始日期、节次时间转换为标准的 RFC 5545 ICS 文件内容。
 */
class GenerateIcsUseCase @Inject constructor() {

    companion object {
        private const val DATE_FORMAT_UTC = "yyyyMMdd'T'HHmmss'Z'"
        private const val DATE_FORMAT_LOCAL = "yyyyMMdd'T'HHmmss"
        private const val TIMEZONE = "Asia/Shanghai"
    }

    /**
     * 执行转换操作
     *
     * @param courses 需要导出的课程列表
     * @param semester 当前学期信息，用于获取开学日期
     * @param sectionTimes 每节课的具体起止时间设置
     * @return 生成的 ICS 格式文本内容
     */
    operator fun invoke(
        courses: List<Course>,
        semester: Semester,
        sectionTimes: List<SectionTime>
    ): String {
        val builder = StringBuilder()
        val utcFormat = SimpleDateFormat(DATE_FORMAT_UTC, Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val localFormat = SimpleDateFormat(DATE_FORMAT_LOCAL, Locale.US).apply {
            timeZone = TimeZone.getTimeZone(TIMEZONE)
        }

        val nowUtc = utcFormat.format(Date())

        // ICS Header
        builder.appendLine("BEGIN:VCALENDAR")
        builder.appendLine("VERSION:2.0")
        builder.appendLine("PRODID:-//DawnCourse//Timetable//CN")
        builder.appendLine("CALSCALE:GREGORIAN")
        builder.appendLine("METHOD:PUBLISH")
        builder.appendLine("X-WR-CALNAME:DawnCourse")
        builder.appendLine("X-WR-TIMEZONE:$TIMEZONE")
        builder.appendLine("X-APPLE-CALENDAR-COLOR:#4285F4") // 可选，设置日历默认颜色

        // Timezone definition
        builder.appendLine("BEGIN:VTIMEZONE")
        builder.appendLine("TZID:$TIMEZONE")
        builder.appendLine("BEGIN:STANDARD")
        builder.appendLine("TZOFFSETFROM:+0800")
        builder.appendLine("TZOFFSETTO:+0800")
        builder.appendLine("TZNAME:CST")
        builder.appendLine("DTSTART:19700101T000000")
        builder.appendLine("END:STANDARD")
        builder.appendLine("END:VTIMEZONE")

        // 遍历所有课程生成 Event
        courses.forEach { course ->
            // 如果课程的节次超出了时间表范围，则跳过
            if (course.startSection <= 0 || course.startSection > sectionTimes.size) return@forEach
            val endSectionIndex = course.startSection + course.duration - 1
            if (endSectionIndex > sectionTimes.size) return@forEach

            val startTimeStr = sectionTimes[course.startSection - 1].startTime // "HH:mm"
            val endTimeStr = sectionTimes[endSectionIndex - 1].endTime // "HH:mm"

            val startParts = startTimeStr.split(":")
            val endParts = endTimeStr.split(":")
            if (startParts.size != 2 || endParts.size != 2) return@forEach

            val startHour = startParts[0].toIntOrNull() ?: return@forEach
            val startMinute = startParts[1].toIntOrNull() ?: return@forEach
            val endHour = endParts[0].toIntOrNull() ?: return@forEach
            val endMinute = endParts[1].toIntOrNull() ?: return@forEach

            // 计算该课程第一周上课的具体日期
            // 假设 semester.startDate 是第一周周一的 00:00 的时间戳
            val calendar = Calendar.getInstance(TimeZone.getTimeZone(TIMEZONE))
            calendar.timeInMillis = semester.startDate

            // 将日期调整到该课程所在的星期 (dayOfWeek: 1=Mon, ..., 7=Sun)
            // Calendar.DAY_OF_WEEK: 1=Sun, 2=Mon, ..., 7=Sat
            val targetDayOfWeek = if (course.dayOfWeek == 7) Calendar.SUNDAY else course.dayOfWeek + 1
            val currentDayOfWeek = calendar.get(Calendar.DAY_OF_WEEK)
            var daysToAdd = targetDayOfWeek - currentDayOfWeek
            if (daysToAdd < 0) {
                daysToAdd += 7
            }
            
            // 调整到具体的周次（如果从第 N 周开始，需要加上 (N-1) * 7 天）
            daysToAdd += (course.startWeek - 1) * 7
            calendar.add(Calendar.DAY_OF_YEAR, daysToAdd)

            // 设置具体的开始时间和结束时间
            val startCal = calendar.clone() as Calendar
            startCal.set(Calendar.HOUR_OF_DAY, startHour)
            startCal.set(Calendar.MINUTE, startMinute)
            startCal.set(Calendar.SECOND, 0)

            val endCal = calendar.clone() as Calendar
            endCal.set(Calendar.HOUR_OF_DAY, endHour)
            endCal.set(Calendar.MINUTE, endMinute)
            endCal.set(Calendar.SECOND, 0)

            // 生成 UID
            val uid = UUID.randomUUID().toString() + "@dawncourse.com"

            // 计算 RRULE (重复规则)
            val weekCount = course.endWeek - course.startWeek + 1
            val interval = when (course.weekType) {
                Course.WEEK_TYPE_ALL -> 1
                Course.WEEK_TYPE_ODD, Course.WEEK_TYPE_EVEN -> 2
                else -> 1
            }
            val count = if (interval == 1) weekCount else (weekCount + 1) / 2

            builder.appendLine("BEGIN:VEVENT")
            builder.appendLine("UID:$uid")
            builder.appendLine("DTSTAMP:$nowUtc")
            builder.appendLine("DTSTART;TZID=$TIMEZONE:${localFormat.format(startCal.time)}")
            builder.appendLine("DTEND;TZID=$TIMEZONE:${localFormat.format(endCal.time)}")
            builder.appendLine("SUMMARY:${escapeIcsString(course.name)}")
            
            val location = if (course.location.isNotBlank()) course.location else "未知地点"
            builder.appendLine("LOCATION:${escapeIcsString(location)}")
            
            val description = buildString {
                append("教师：").append(if (course.teacher.isNotBlank()) course.teacher else "未知")
                if (course.note.isNotBlank()) {
                    append("\\n备注：").append(course.note)
                }
                append("\\n节次：第 ${course.startSection} - ${course.startSection + course.duration - 1} 节")
                append("\\n周次：第 ${course.startWeek} - ${course.endWeek} 周")
                when (course.weekType) {
                    Course.WEEK_TYPE_ODD -> append(" (单周)")
                    Course.WEEK_TYPE_EVEN -> append(" (双周)")
                }
            }
            builder.appendLine("DESCRIPTION:${escapeIcsString(description)}")
            
            if (count > 1) {
                builder.appendLine("RRULE:FREQ=WEEKLY;INTERVAL=$interval;COUNT=$count")
            }
            builder.appendLine("END:VEVENT")
        }

        builder.appendLine("END:VCALENDAR")
        return builder.toString()
    }

    private fun escapeIcsString(text: String): String {
        return text.replace("\\", "\\\\")
            .replace(";", "\\;")
            .replace(",", "\\,")
            .replace("\n", "\\n")
    }
}
