package com.ghostgramlabs.speakalert.alarm

import android.content.Context
import com.ghostgramlabs.speakalert.R
import com.ghostgramlabs.speakalert.data.model.ReminderEntity
import com.ghostgramlabs.speakalert.util.APP_DISPLAY_NAME
import com.ghostgramlabs.speakalert.util.AppLocale

/**
 * User-visible strings used when building alert payloads. Defaults are the English resource
 * values so the payload builders stay pure functions (unit-testable without Android); production
 * callers should pass [AlertStrings.from] so alerts follow the in-app language.
 */
internal data class AlertStrings(
    val followUpTitle: String = "Follow-Up Check",
    val followUpQuestion: String = "Did you complete %1\$s?",
    val followUpThisReminder: String = "this reminder",
    val audioUnavailable: String = "Selected audio file is unavailable. Tap to choose another file.",
    val tapToView: String = "Tap to view"
) {
    companion object {
        /** Falls back to the English defaults if resources can't be resolved — an alarm must
         *  never fail to fire over localization. */
        fun from(context: Context): AlertStrings = runCatching {
            val res = AppLocale.localizedContext(context)
            AlertStrings(
                followUpTitle = res.getString(R.string.alert_followup_fallback_title),
                followUpQuestion = res.getString(R.string.alert_followup_question),
                followUpThisReminder = res.getString(R.string.alert_followup_this_reminder),
                audioUnavailable = res.getString(R.string.alert_audio_unavailable_full),
                tapToView = res.getString(R.string.alert_tap_to_view)
            )
        }.getOrDefault(AlertStrings())
    }
}

internal data class ReminderAlertPayload(
    val title: String,
    val message: String,
    val playbackAudioPath: String?,
    val playbackText: String?,
    val autoplayOnTap: Boolean,
    val isFollowUpAlert: Boolean
)

internal fun buildReminderAlertPayload(
    reminder: ReminderEntity,
    isFollowUpTrigger: Boolean,
    hasPlayableAudio: Boolean,
    hasAudioConfigured: Boolean,
    strings: AlertStrings = AlertStrings()
): ReminderAlertPayload {
    val followUpMessage = buildFollowUpMessage(reminder, strings)
    val message = when {
        isFollowUpTrigger -> followUpMessage
        !reminder.reminderText.isNullOrBlank() -> com.ghostgramlabs.speakalert.transit.BusAlert.display(reminder.reminderText)!!
        hasAudioConfigured && !hasPlayableAudio -> strings.audioUnavailable
        else -> strings.tapToView
    }

    return ReminderAlertPayload(
        title = if (isFollowUpTrigger) {
            reminder.title?.takeIf { it.isNotBlank() } ?: strings.followUpTitle
        } else {
            reminder.title?.takeIf { it.isNotBlank() } ?: APP_DISPLAY_NAME
        },
        message = message,
        playbackAudioPath = reminder.audioPath?.takeIf { !isFollowUpTrigger && hasPlayableAudio },
        playbackText = when {
            isFollowUpTrigger -> followUpMessage
            !reminder.reminderText.isNullOrBlank() -> reminder.reminderText
            else -> null
        },
        autoplayOnTap = !isFollowUpTrigger,
        isFollowUpAlert = isFollowUpTrigger
    )
}

internal fun buildFollowUpMessage(
    reminder: ReminderEntity,
    strings: AlertStrings = AlertStrings()
): String {
    val subject = reminder.title
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: reminder.reminderText
            ?.takeIf { it.isNotBlank() }
            ?.let { text ->
                val trimmed = text.trim()
                val words = trimmed.split(Regex("\\s+"))
                if (words.size > 8) words.take(8).joinToString(" ") else trimmed
            }
        ?: strings.followUpThisReminder
    return strings.followUpQuestion.format(subject)
}

internal fun shouldAutoPlayReminder(
    autoPlayEnabled: Boolean,
    inCall: Boolean,
    unlockedOnly: Boolean,
    isLocked: Boolean,
    playbackAudioPath: String?,
    playbackText: String?,
    isFollowUpTrigger: Boolean,
    speakTextIfNoVoice: Boolean,
    bootBlocked: Boolean,
    toneOnlyMode: Boolean,
    blockedByDnd: Boolean = false
): Boolean {
    if (!autoPlayEnabled || inCall || bootBlocked || toneOnlyMode || blockedByDnd) return false
    if (unlockedOnly && isLocked) return false
    if (!playbackAudioPath.isNullOrBlank()) return true
    if (playbackText.isNullOrBlank()) return false

    // Follow-up checks are an explicit reliability feature and should still ask
    // their confirmation question even when normal automatic spoken fallback is off.
    return isFollowUpTrigger || speakTextIfNoVoice
}
