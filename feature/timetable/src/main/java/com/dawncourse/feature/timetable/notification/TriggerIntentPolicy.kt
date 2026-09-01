package com.dawncourse.feature.timetable.notification

import com.dawncourse.core.domain.model.TriggerKey
import com.dawncourse.core.domain.model.TriggerKind
import com.dawncourse.core.domain.model.TriggerUriCodec

/** Receiver 输入的 action/URI 一致性校验。 */
object TriggerIntentPolicy {

    /** 返回每种触发器固定的广播 action。 */
    fun expectedAction(kind: TriggerKind): String = when (kind) {
        TriggerKind.REMINDER -> ReminderReceiver.ACTION_REMINDER
        TriggerKind.MUTE -> SilenceReceiver.ACTION_MUTE
        TriggerKind.UNMUTE -> SilenceReceiver.ACTION_UNMUTE
    }

    /** 只有 URI 可解码且 action 与 kind 一致时才返回 Key。 */
    fun parse(action: String?, dataUri: String?): TriggerKey? {
        val key = TriggerUriCodec.decode(dataUri) ?: return null
        return key.takeIf { value -> expectedAction(value.kind) == action }
    }
}
