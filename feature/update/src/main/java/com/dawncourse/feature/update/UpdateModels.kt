package com.dawncourse.feature.update

import com.google.gson.annotations.SerializedName

/**
 * 更新信息实体类
 */
data class UpdateInfo(
    @SerializedName("versionCode") val versionCode: Int,
    @SerializedName("versionName") val versionName: String,
    @SerializedName("isForce") val isForce: Boolean = false,
    @SerializedName("title") val title: String,
    @SerializedName("content") val content: String,
    @SerializedName("downloadUrl") val downloadUrl: String,
    @SerializedName("releaseDate") val releaseDate: String,
    @SerializedName("sha256") val sha256: String? = null
)
