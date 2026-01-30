package com.dawncourse.feature.timetable.notification

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.dawncourse.core.domain.repository.CourseRepository
import com.dawncourse.core.domain.repository.SettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import javax.inject.Inject
import kotlinx.coroutines.flow.first

@AndroidEntryPoint
class PersistentNotificationService : Service() {

    @Inject lateinit var courseRepository: CourseRepository
    @Inject lateinit var settingsRepository: SettingsRepository

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NotificationHelper.NOTIFICATION_ID_BASE + 999, createNotification("正在加载课程信息..."))
        
        job?.cancel()
        job = serviceScope.launch {
            while (isActive) {
                updateNotification()
                delay(60 * 1000L) // Update every minute
            }
        }
        
        return START_STICKY
    }

    private suspend fun updateNotification() {
        // Here we could implement logic to find the next course
        // For now, we keep it simple to avoid complex logic duplication
        val notification = createNotification("课程表正在后台运行")
        val manager = androidx.core.app.NotificationManagerCompat.from(this)
        try {
            manager.notify(NotificationHelper.NOTIFICATION_ID_BASE + 999, notification)
        } catch (e: SecurityException) { }
    }

    private fun createNotification(content: String): Notification {
        NotificationHelper.createNotificationChannel(this)
        return NotificationCompat.Builder(this, NotificationHelper.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Dawn Course")
            .setContentText(content)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}
