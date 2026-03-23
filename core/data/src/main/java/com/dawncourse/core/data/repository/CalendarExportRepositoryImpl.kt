package com.dawncourse.core.data.repository

import android.content.Context
import android.net.Uri
import com.dawncourse.core.domain.repository.CalendarExportRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 日历导出仓库实现
 *
 * 负责通过 SAF 机制将日历数据（如 ICS 内容）写入到用户选择的文件中。
 */
@Singleton
class CalendarExportRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : CalendarExportRepository {

    override suspend fun exportIcsToUri(uri: String, icsContent: String): Boolean {
        return withContext(Dispatchers.IO) {
            runCatching {
                val parsedUri = Uri.parse(uri)
                val outputStream = context.contentResolver.openOutputStream(parsedUri)
                if (outputStream == null) {
                    return@runCatching false
                }
                outputStream.use { it.write(icsContent.toByteArray()) }
                true
            }.getOrDefault(false)
        }
    }
}
