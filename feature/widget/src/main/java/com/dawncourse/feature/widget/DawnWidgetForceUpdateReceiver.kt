package com.dawncourse.feature.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.glance.appwidget.updateAll
import com.dawncourse.feature.widget.policy.ForceUpdateCompletionPolicy
import com.dawncourse.feature.widget.policy.WidgetForceUpdatePolicy
import com.dawncourse.feature.widget.worker.WidgetSyncManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** 仅供应用内部显式广播使用的 Widget 强制刷新入口。 */
class DawnWidgetForceUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FORCE_UPDATE) return
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        receiverScope.launch {
            try {
                ForceUpdateCompletionPolicy.execute(
                    updateAll = {
                        WidgetForceUpdatePolicy.execute(
                            hasWidgetInstances = WidgetSyncManager.hasWidgetInstances(appContext),
                            clearResponsibilities = {
                                WidgetSyncManager.cancelUpdateAndAwait(appContext)
                            },
                            refreshWidgetContent = {
                                WidgetSyncManager.refreshWidgetContent(appContext)
                            },
                        )
                    },
                    enqueueRetry = { failure ->
                        Log.w(TAG, "force widget update failed", failure)
                        if (WidgetSyncManager.hasWidgetInstances(appContext)) {
                            WidgetSyncManager.enqueueBoundaryDelivery(appContext)
                        } else {
                            WidgetSyncManager.cancelUpdateAndAwait(appContext)
                        }
                    },
                    finishPendingResult = pendingResult::finish,
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                Log.w(TAG, "force widget retry handoff failed", failure)
            }
        }
    }

    companion object {
        const val ACTION_FORCE_UPDATE = "com.dawncourse.widget.FORCE_UPDATE"
        private const val TAG = "DawnWidgetForceReceiver"
        private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
