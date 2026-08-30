package com.airclip.runtime

import android.content.Context
import com.airclip.R
import com.airclip.core.clipboard.ClipboardReadFailure

/** One place to phrase the outcome of a send, so every entry point says the same thing. */
object SendFeedback {

    /** `null` means "say nothing": a suppressed echo is normal operation, not a failure. */
    fun message(context: Context, outcome: SendOutcome): String? = when (outcome) {
        is SendOutcome.Sent -> if (outcome.peers > 0) {
            context.getString(R.string.toast_sent, outcome.peers)
        } else {
            context.getString(R.string.toast_no_peer)
        }

        SendOutcome.Suppressed -> null
        SendOutcome.ServiceOff -> context.getString(R.string.toast_service_off)
        SendOutcome.Paused -> context.getString(R.string.home_state_paused)
        is SendOutcome.Failed -> context.getString(
            when (outcome.reason) {
                ClipboardReadFailure.EMPTY, ClipboardReadFailure.FILTERED_SENSITIVE ->
                    R.string.toast_clipboard_empty

                ClipboardReadFailure.DENIED_BACKGROUND -> R.string.toast_read_denied
                ClipboardReadFailure.TOO_LARGE -> R.string.toast_too_large
                ClipboardReadFailure.UNSUPPORTED_MIME -> R.string.toast_unsupported
                ClipboardReadFailure.ERROR -> R.string.toast_read_error
            },
        )
    }
}
