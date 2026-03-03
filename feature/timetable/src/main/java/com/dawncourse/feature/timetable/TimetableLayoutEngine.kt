package com.dawncourse.feature.timetable

import com.dawncourse.core.domain.model.Course
import kotlin.math.max

/**
 * 课表网格中单个课程的布局信息
 */
data class TimetableLayoutItem(
    val course: Course,
    val isCurrentWeek: Boolean,
    val safeDayOfWeek: Int,
    val safeStartSection: Int,
    val safeEndSection: Int,
    val laneIndex: Int,
    val laneCount: Int
)

/**
 * 课表布局计算引擎
 * 
 * 负责根据课程列表和当前周次，计算每个课程在网格中的显示位置（Day, Start, End, Lane）。
 * 核心逻辑包括：
 * 1. 课程筛选与展示规则应用
 * 2. 冲突检测与智能过滤
 * 3. 布局分栏计算
 */
object TimetableLayoutEngine {

    /**
     * 计算布局项列表
     *
     * @param courses 所有课程列表
     * @param currentWeek 当前周次
     * @param maxNodes 每日最大节数
     * @param hideNonThisWeek 是否隐藏非本周课程
     * @return 布局项列表
     */
    fun calculateLayoutItems(
        courses: List<Course>,
        currentWeek: Int,
        maxNodes: Int,
        hideNonThisWeek: Boolean,
        showWeekend: Boolean = true
    ): List<TimetableLayoutItem> {
        // 1. 准备显示列表（仅负责“本周/非本周”决策）
        val rawDisplayList = prepareRawDisplayList(courses, currentWeek, hideNonThisWeek, showWeekend)

        // 2. 生成最终布局列表：补齐边界、防止异常数据导致堆叠，并对节次区间重叠的课程做横向分栏
        return generateLayoutItems(rawDisplayList, maxNodes)
    }

    private fun prepareRawDisplayList(
        courses: List<Course>,
        currentWeek: Int,
        hideNonThisWeek: Boolean,
        showWeekend: Boolean
    ): List<Pair<Course, Boolean>> {
        val courseGroups = courses
            .filter { showWeekend || it.dayOfWeek <= 5 } // 过滤周末课程
            .groupBy { it.dayOfWeek to it.startSection }
        val list = mutableListOf<Pair<Course, Boolean>>() // 课程, 是否为本周

        courseGroups.forEach { (_, group) ->
            // 冲突解决逻辑：
            // 1. 优先显示本周课程
            // 2. 如果没有本周课程，且未开启"隐藏非本周"，则显示非本周课程
            // 3. 如果有多个非本周课程，显示 ID 最大的（通常是最新添加的）
            val currentWeekCourse = group.find { course ->
                // 判断逻辑：当前周在课程的起始周和结束周之间，且符合单双周规则
                currentWeek in course.startWeek..course.endWeek && when (course.weekType) {
                    Course.WEEK_TYPE_ODD -> currentWeek % 2 != 0
                    Course.WEEK_TYPE_EVEN -> currentWeek % 2 == 0
                    else -> true
                }
            }

            if (currentWeekCourse != null) {
                list.add(currentWeekCourse to true)
            } else if (!hideNonThisWeek) {
                // 显示非本周课程（取 ID 最大的一个作为代表）
                group.maxByOrNull { it.id }?.let { list.add(it to false) }
            }
        }
        return list
    }

    private data class Tmp(
        val course: Course,
        val isCurrentWeek: Boolean,
        val safeDayOfWeek: Int,
        val safeStartSection: Int,
        val safeEndSection: Int
    )

    private fun generateLayoutItems(
        rawDisplayList: List<Pair<Course, Boolean>>,
        maxNodes: Int
    ): List<TimetableLayoutItem> {
        val normalized = rawDisplayList
            .map { (course, isCurrentWeek) ->
                val safeDay = course.dayOfWeek.coerceIn(1, 7)
                val safeStart = course.startSection.coerceIn(1, maxNodes)
                val safeDuration = course.duration.coerceAtLeast(1)
                val safeEnd = (safeStart + safeDuration - 1).coerceIn(1, maxNodes)
                Tmp(
                    course = course,
                    isCurrentWeek = isCurrentWeek,
                    safeDayOfWeek = safeDay,
                    safeStartSection = safeStart,
                    safeEndSection = safeEnd
                )
            }
            // 稳定排序：避免输入列表顺序变化导致测量/放置“看起来错位”
            .sortedWith(
                compareBy<Tmp>({ it.safeDayOfWeek }, { it.safeStartSection }, { it.safeEndSection }, { it.course.id })
            )

        val result = mutableListOf<TimetableLayoutItem>()

        // 按星期分组，分别处理每一列（避免跨天影响 lane 计算）
        val byDay = normalized.groupBy { it.safeDayOfWeek }.toSortedMap()
        byDay.forEach { (day, dayCourses) ->
            var i = 0
            while (i < dayCourses.size) {
                // 1) 构造“重叠簇”：同一星期内，只要节次区间存在重叠，就划入同一簇
                var clusterEnd = dayCourses[i].safeEndSection
                var j = i + 1
                while (j < dayCourses.size && dayCourses[j].safeStartSection <= clusterEnd) {
                    clusterEnd = max(clusterEnd, dayCourses[j].safeEndSection)
                    j++
                }

                val rawCluster = dayCourses.subList(i, j)
                
                // 智能冲突处理：如果簇中包含本周课程，则过滤掉所有非本周课程
                // 避免 subList 修改引发异常，创建新列表处理
                val activeCluster = if (rawCluster.any { it.isCurrentWeek }) {
                    rawCluster.filter { it.isCurrentWeek }
                } else {
                    rawCluster
                }

                // 2) 分离“背景课程”与“前景课程”
                // 背景课程定义：时长超过 5 节，且簇中存在短课程（时长 <= 5 节）
                // 目的：避免全天/半天的长周期事件（如实习、实训）挤压正常课程的显示宽度
                val hasShortCourse = activeCluster.any { (it.safeEndSection - it.safeStartSection + 1) <= 5 }
                
                val (foreground, background) = if (hasShortCourse) {
                    activeCluster.partition { (it.safeEndSection - it.safeStartSection + 1) <= 5 }
                } else {
                    // 如果都是长课程，或者都是短课程，则全部视为前景，参与常规分栏
                    activeCluster to emptyList()
                }

                // 3) 对前景课程做常规横向分栏
                val laneEnds = mutableListOf<Int>()
                val assigned = mutableListOf<Pair<Tmp, Int>>()

                foreground.forEach { item ->
                    // 注意：节次区间是闭区间 [start, end]，所以 lane 可复用条件为 laneEnd < start
                    val laneIndex = laneEnds.indexOfFirst { laneEnd -> laneEnd < item.safeStartSection }
                    val finalLaneIndex = if (laneIndex >= 0) {
                        laneEnds[laneIndex] = item.safeEndSection
                        laneIndex
                    } else {
                        laneEnds.add(item.safeEndSection)
                        laneEnds.size - 1
                    }
                    assigned.add(item to finalLaneIndex)
                }

                val laneCount = laneEnds.size.coerceAtLeast(1)
                
                // 4) 输出结果：先添加背景课程（层级在下，全宽），再添加前景课程
                // 背景课程强制 laneIndex=0, laneCount=1，使其铺满宽度且位于底层
                background.forEach { item ->
                    result.add(
                        TimetableLayoutItem(
                            course = item.course,
                            isCurrentWeek = item.isCurrentWeek,
                            safeDayOfWeek = day,
                            safeStartSection = item.safeStartSection,
                            safeEndSection = item.safeEndSection,
                            laneIndex = 0,
                            laneCount = 1
                        )
                    )
                }

                assigned.forEach { (item, laneIndex) ->
                    result.add(
                        TimetableLayoutItem(
                            course = item.course,
                            isCurrentWeek = item.isCurrentWeek,
                            safeDayOfWeek = day,
                            safeStartSection = item.safeStartSection,
                            safeEndSection = item.safeEndSection,
                            laneIndex = laneIndex,
                            laneCount = laneCount
                        )
                    )
                }

                i = j
            }
        }

        return result
    }
}
