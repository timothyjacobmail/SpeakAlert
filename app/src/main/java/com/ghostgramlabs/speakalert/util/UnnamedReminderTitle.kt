package com.ghostgramlabs.speakalert.util

enum class UnnamedReminderTitle(val value: String) {
    CREATION_TIME("creation_time"), REMINDER_TYPE("reminder_type"), NONE("none");

    companion object {
        fun fromValue(value: String?) = entries.firstOrNull { it.value == value } ?: CREATION_TIME
    }
}

/** Keep the existing label/text priority; this setting only replaces the generated fallback. */
internal fun reminderDisplayTitle(title: String?, text: String?, fallback: String): String {
    val legacy = title?.matches(Regex("Reminder at \\d{1,2}:\\d{2} [AP]M")) == true ||
        title.equals("Voice reminder", ignoreCase = true) || title.isDefaultAppDisplayName()
    if (!title.isNullOrBlank() && !legacy) return title
    if (!text.isNullOrBlank()) {
        val words = com.ghostgramlabs.speakalert.transit.BusAlert.display(text)!!.trim().split(Regex("\\s+"))
        return if (words.size > 10) words.take(10).joinToString(" ") + "..." else com.ghostgramlabs.speakalert.transit.BusAlert.display(text)!!
    }
    return fallback
}
