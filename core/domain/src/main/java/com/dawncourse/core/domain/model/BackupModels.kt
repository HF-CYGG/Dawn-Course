package com.dawncourse.core.domain.model

import java.util.UUID

/**
 * 备份相关常量
 */
object BackupConstants {
    /** 备份文件结构版本 */
    const val SCHEMA_VERSION = 1
}

/**
 * 备份元信息
 */
data class BackupMeta(
    /** 备份结构版本 */
    val schemaVersion: Int,
    /** 应用版本名 */
    val appVersionName: String,
    /** 应用版本号 */
    val appVersionCode: Int,
    /** 导出时间戳 */
    val exportedAt: Long,
    /** 设备标识 */
    val deviceId: String,
    /** 负载校验值 */
    val checksum: String? = null,
    /** 备份文件唯一标识 */
    val backupId: String = UUID.randomUUID().toString()
)

/**
 * 备份负载内容
 */
data class BackupPayload(
    /** 学期列表 */
    val semesters: List<Semester>,
    /** 课程列表 */
    val courses: List<Course>,
    /** 设置快照 */
    val settings: AppSettings
)

/**
 * 备份文件根结构
 */
data class BackupFile(
    /** 元信息 */
    val meta: BackupMeta,
    /** 负载内容 */
    val payload: BackupPayload
)

/**
 * 备份摘要
 */
data class BackupSummary(
    /** 学期数量 */
    val semesterCount: Int,
    /** 课程数量 */
    val courseCount: Int
)

/**
 * 备份结果
 */
sealed interface BackupResult {
    /** 备份成功 */
    data class Success(val summary: BackupSummary) : BackupResult
    /** 备份失败 */
    data class Failure(val message: String) : BackupResult
}

/**
 * 还原结果
 */
sealed interface RestoreResult {
    /** 还原成功 */
    data class Success(val summary: BackupSummary) : RestoreResult
    /** 还原失败 */
    data class Failure(val message: String) : RestoreResult
}
