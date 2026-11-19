package com.ghostgramlabs.speakalert.transit

/** Android shares text by MIME type, so non-Maps text must be rejected inside the receiver. */
internal object MapsShareInput {
    fun read(action: String?, mimeType: String?, text: CharSequence?, subject: CharSequence? = null): String? {
        if (action != "android.intent.action.SEND" || !mimeType.equals("text/plain", true) ||
            text == null || text.length !in 1..10_000) return null
        val shared = text.toString().trim()
        if (runCatching { MapsStopLink.link(shared, allowDirections = true) }.isFailure) return null
        val title = subject?.take(180)?.toString()?.replace('\n', ' ')?.trim().orEmpty()
        return if (title.isNotBlank() && shared.startsWith("https://", true) && title.length + shared.length + 1 <= 10_000)
            "$title\n$shared" else shared
    }
}
