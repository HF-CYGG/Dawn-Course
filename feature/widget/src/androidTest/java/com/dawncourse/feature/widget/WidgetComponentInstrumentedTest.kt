package com.dawncourse.feature.widget

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.appwidget.AppWidgetManager
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.dawncourse.feature.widget.worker.awaitWorkManagerOperation
import com.dawncourse.feature.widget.worker.WidgetSyncManager
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WidgetComponentInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val workManager by lazy { WorkManager.getInstance(context) }

    @After
    fun tearDown() = runBlocking {
        awaitWorkManagerOperation(workManager.cancelAllWork())
    }

    @Suppress("DEPRECATION")
    @Test
    fun forceUpdateReceiver_isInternalWhileGlanceReceiverRemainsExported() {
        val packageManager = context.packageManager
        val forceReceiver = packageManager.getReceiverInfo(
            ComponentName(context, DawnWidgetForceUpdateReceiver::class.java),
            PackageManager.GET_META_DATA,
        )
        val glanceReceiver = packageManager.getReceiverInfo(
            ComponentName(context, DawnWidgetReceiver::class.java),
            PackageManager.GET_META_DATA,
        )

        assertFalse(forceReceiver.exported)
        assertTrue(glanceReceiver.exported)
    }

    @Test
    fun workManagerOperationBridge_waitsForEnqueueAndCancellation() = runBlocking {
        val request = OneTimeWorkRequestBuilder<BridgeProbeWorker>()
            .setInitialDelay(1L, TimeUnit.DAYS)
            .build()

        awaitWorkManagerOperation(workManager.enqueue(request))
        assertTrue(workManager.getWorkInfoById(request.id).get()!!.state.isFinished.not())

        awaitWorkManagerOperation(workManager.cancelWorkById(request.id))
        assertTrue(workManager.getWorkInfoById(request.id).get()!!.state.isFinished)
    }

    @Test
    fun appWidgetUpdateReceiver_persistsUniqueWorkBeforeBroadcastCompletes() {
        context.sendBroadcast(
            Intent(context, DawnWidgetReceiver::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, intArrayOf(42))
            },
        )

        assertEventually {
            workManager.getWorkInfosForUniqueWork(
                WidgetSyncManager.IMMEDIATE_RESTORE_WORK_NAME,
            ).get().isNotEmpty()
        }
    }

    @Test
    fun forceAfterLastInstanceRemoved_clearsWorkResponsibilitiesWithoutRefreshing() = runBlocking {
        assertFalse(WidgetSyncManager.hasWidgetInstances(context))
        WidgetSyncManager.scheduleStartupRetry(context)

        context.sendBroadcast(
            Intent(context, DawnWidgetForceUpdateReceiver::class.java).apply {
                action = DawnWidgetForceUpdateReceiver.ACTION_FORCE_UPDATE
            },
        )

        assertEventually {
            val startupRetryFinished = workManager.getWorkInfosForUniqueWork(
                WidgetSyncManager.STARTUP_RETRY_WORK_NAME,
            ).get().all { it.state.isFinished }
            startupRetryFinished
        }
    }

    private fun assertEventually(assertion: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + TimeUnit.SECONDS.toMillis(10)
        while (SystemClock.elapsedRealtime() < deadline) {
            if (assertion()) return
            SystemClock.sleep(100L)
        }
        assertTrue("condition did not become true before timeout", assertion())
    }
}

class BridgeProbeWorker(
    context: Context,
    parameters: WorkerParameters,
) : Worker(context, parameters) {
    override fun doWork(): Result = Result.success()
}
