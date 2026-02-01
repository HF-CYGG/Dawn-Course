package com.dawncourse.feature.update

import com.google.gson.annotations.SerializedName

/**
 * 更新信息实体类
 */
data class UpdateInfo(
    @SerializedName("versionCode") val versionCode: Int,
    @SerializedName("versionName") val versionName: String,
    @SerializedName("updateContent") val updateContent: String,
    @SerializedName("downloadUrl") val downloadUrl: String,
    @SerializedName("date") val date: String,
    @SerializedName("forceUpdate") val forceUpdate: Boolean = false
)
