package com.dawncourse.feature.import_module.engine.ocr

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File

/**
 * 模型下载状态
 */
sealed class DownloadState {
    data object Idle : DownloadState()
    data class Downloading(val progress: Int) : DownloadState()
    data class Success(val modelFile: File) : DownloadState()
    data class Error(val exception: Throwable) : DownloadState()
}

/**
 * OCR 模型按需下载器
 *
 * 负责从国内镜像源下载轻量级 OCR 模型，
 * 支持进度监听和断点续传（后续完善）。
 */
class ModelDownloader {

    /**
     * 下载模型文件
     *
     * @param downloadUrl 模型文件的国内镜像下载地址
     * @param targetFile 本地存储目标文件
     * @return 包含下载进度的 Flow 数据流
     */
    fun downloadModel(downloadUrl: String, targetFile: File): Flow<DownloadState> = flow {
        try {
            emit(DownloadState.Downloading(0))
            
            // TODO: 替换为真实的 OkHttp 下载逻辑
            // 模拟网络请求和下载过程
            for (i in 1..10) {
                delay(200) // 模拟下载延迟
                emit(DownloadState.Downloading(i * 10))
            }
            
            // 模拟下载成功
            emit(DownloadState.Success(targetFile))
        } catch (e: Exception) {
            emit(DownloadState.Error(e))
        }
    }.flowOn(Dispatchers.IO)
}
