package com.dawncourse.core.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.dawncourse.core.data.local.dao.CourseDao
import com.dawncourse.core.data.local.dao.SemesterDao
import com.dawncourse.core.data.local.entity.CourseEntity
import com.dawncourse.core.data.local.entity.SemesterEntity

/**
 * 应用程序主数据库
 *
 * 定义了 Room 数据库的配置，包含实体列表和版本号。
 * 使用 @Database 注解声明数据库元数据。
 *
 * @property entities 数据库包含的实体表列表
 * @property version 数据库版本号，当表结构变更时需升级版本并提供迁移策略
 * @property exportSchema 是否导出 schema 文件（用于版本控制），此处设为 false 简化配置
 */
@Database(entities = [CourseEntity::class, SemesterEntity::class], version = 5, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    /**
     * 获取 CourseDao 实例
     *
     * Room 会自动实现此抽象方法。
     */
    abstract fun courseDao(): CourseDao
    abstract fun semesterDao(): SemesterDao
}
