/**
 * 文件说明：更新 APK 专用 FileProvider。
 *
 * 使用项目内子类规避部分 Android/OEM 版本直接实例化 AndroidX FileProvider 的兼容问题。
 */
package com.dawncourse.feature.update

import androidx.core.content.FileProvider

class UpdateFileProvider : FileProvider()
