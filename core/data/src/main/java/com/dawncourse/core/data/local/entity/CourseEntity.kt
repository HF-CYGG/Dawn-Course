package com.dawncourse.core.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.dawncourse.core.domain.model.Course

/**
 * 课程数据库实体类 (Room Entity)
 *
 * 定义了课程在 SQLite 数据库中的表结构。
 * 使用 Room 注解 @Entity 标记，tableName 指定表名为 "courses"。
 * 此类仅用于 Data 层内部存储，对外交互时需转换为 Domain 层的 [Course] 模型。
 */
@Entity(tableName = "courses")
data class CourseEntity(
    /** 主键 ID，自增长 */
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** 学期 ID */
    val semesterId: Long = 1,
    /** 课程名称 */
    val name: String,
    /** 教师姓名 */
    val teacher: String,
    /** 上课地点 */
    val location: String,
    
    // 时间信息
    /** 星期几 (1-7) */
    val dayOfWeek: Int,
    /** 开始节次 */
    val startSection: Int,
    /** 持续节数 */
    val duration: Int,
    
    // 周次信息
    /** 起始周 */
    val startWeek: Int,
    /** 结束周 */
    val endWeek: Int,
    /** 周次类型 (0:全, 1:单, 2:双) */
    val weekType: Int,
    
    /** 颜色代码 */
    val color: String
)

/**
 * 扩展函数：将数据库实体 [CourseEntity] 转换为领域模型 [Course]
 * 用于 Repository 层将数据库数据暴露给 Domain 层。
 */
fun CourseEntity.toDomain() = Course(
    id = id,
    semesterId = semesterId,
    name = name,
    teacher = teacher,
    location = location,
    dayOfWeek = dayOfWeek,
    startSection = startSection,
    duration = duration,
    startWeek = startWeek,
    endWeek = endWeek,
    weekType = weekType,
    color = color
)

/**
 * 扩展函数：将领域模型 [Course] 转换为数据库实体 [CourseEntity]
 * 用于 Repository 层将 Domain 数据存入数据库。
 */
fun Course.toEntity() = CourseEntity(
    id = id,
    semesterId = semesterId,
    name = name,
    teacher = teacher,
    location = location,
    dayOfWeek = dayOfWeek,
    startSection = startSection,
    duration = duration,
    startWeek = startWeek,
    endWeek = endWeek,
    weekType = weekType,
    color = color
)
