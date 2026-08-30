package com.airclip.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.getSystemService
import com.airclip.R
import com.airclip.core.clipboard.ClipKind
import com.airclip.core.net.ReceivedContent
import com.airclip.core.sync.PublishSource
import com.airclip.ui.ClipboardRelayActivity
import com.airclip.ui.MainActivity

/**
 * The two notifications AirClip posts: the ongoing one that keeps the service alive, and the
 * per-item one whose 一键复制 action is the whole point of the receive path — on Android 10+ a
 * background app may *write* the clipboard, so one tap can finish the job even when auto-apply is
 * off or the platform refuses a silent write.
 */
object SyncNotifications {

    const val CHANNEL_SYNC = "airclip.sync"
    const val CHANNEL_RECEIVED = "airclip.received"
    const val ID_SYNC = 1001
    const val ID_RECEIVED = 1002

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService<NotificationManager>() ?: return

        val sync = NotificationChannel(
            CHANNEL_SYNC,
            context.getString(R.string.channel_sync_name),
            // Low: a permanent status line must never make a sound or push anything aside.
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.channel_sync_description)
            setShowBadge(false)
        }

        val received = NotificationChannel(
            CHANNEL_RECEIVED,
            context.getString(R.string.channel_received_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.channel_received_description)
            setShowBadge(true)
        }

        manager.createNotificationChannels(listOf(sync, received))
    }

    /**
     * [copyHash] non-null adds the 一键复制 action for the last thing that arrived. Android shows the
     * first three actions, so they are ordered by how often they are actually used.
     */
    fun ongoing(context: Context, text: String, paused: Boolean, copyHash: String?): Notification {
        val builder = NotificationCompat.Builder(context, CHANNEL_SYNC)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(
                context.getString(if (paused) R.string.notif_paused_title else R.string.notif_running_title),
            )
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp(context))
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)

        builder.addAction(
            NotificationCompat.Action.Builder(
                R.drawable.ic_notification,
                context.getString(R.string.notif_action_send),
                relay(context),
            ).build(),
        )
        if (copyHash != null) {
            builder.addAction(
                action(context, R.string.notif_action_copy, ClipActionReceiver.ACTION_COPY, copyHash),
            )
        }
        builder.addAction(
            if (paused) {
                action(context, R.string.notif_action_resume, ClipActionReceiver.ACTION_RESUME)
            } else {
                action(context, R.string.notif_action_pause, ClipActionReceiver.ACTION_PAUSE)
            },
        )
        builder.addAction(action(context, R.string.notif_action_stop, ClipActionReceiver.ACTION_STOP))

        return builder.build()
    }

    fun received(context: Context, received: ReceivedContent, applied: Boolean): Notification {
        val content = received.content
        val title = when (content.kind) {
            ClipKind.TEXT -> context.getString(
                R.string.notif_received_text,
                content.text.orEmpty().replace('\n', ' ').trim().take(60),
            )

            ClipKind.IMAGE -> context.getString(
                R.string.notif_received_image,
                content.image?.width ?: 0,
                content.image?.height ?: 0,
            )
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_RECEIVED)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(
                if (applied) context.getString(R.string.notif_copied) else received.fromDeviceName,
            )
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)

        if (!applied) {
            builder.addAction(
                action(context, R.string.notif_action_copy, ClipActionReceiver.ACTION_COPY, content.hash),
            )
        }
        return builder.build()
    }

    /** No-op when the user never granted `POST_NOTIFICATIONS`; the sync itself does not depend on it. */
    fun show(context: Context, id: Int, notification: Notification) {
        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
    }

    fun cancel(context: Context, id: Int) {
        runCatching { NotificationManagerCompat.from(context).cancel(id) }
    }

    private fun action(context: Context, label: Int, action: String, hash: String? = null) =
        NotificationCompat.Action.Builder(
            R.drawable.ic_notification,
            context.getString(label),
            broadcast(context, action, hash),
        ).build()

    private fun broadcast(context: Context, action: String, hash: String?): PendingIntent {
        val intent = Intent(context, ClipActionReceiver::class.java).setAction(action).apply {
            if (hash != null) putExtra(ClipActionReceiver.EXTRA_HASH, hash)
        }
        return PendingIntent.getBroadcast(
            context,
            action.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * A clipboard *read* needs a focused window, so 发送剪贴板 starts the relay activity; only the
     * write-side actions can be plain broadcasts.
     */
    private fun relay(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        RELAY_REQUEST,
        ClipboardRelayActivity.sendIntent(context, PublishSource.MANUAL),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private const val RELAY_REQUEST = 7
}
