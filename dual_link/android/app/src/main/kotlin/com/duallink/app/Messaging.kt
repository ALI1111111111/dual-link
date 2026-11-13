package com.duallink.app

import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.provider.Telephony
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Phone B as the default SMS app: copies Dual Link's messages into Android's own SMS database,
 * so the phone (backups, other apps that read SMS) sees them like normal texts.
 * Only the default SMS app may write there, so this does nothing until then; rows not copied
 * yet are picked up later (e.g. right after you make Dual Link the default).
 */
object SystemSms {
    private const val TAG = "DualLinkSms"
    private val worker = Executors.newSingleThreadExecutor()

    /**
     * Asks the role system. The older Telephony.Sms.getDefaultSmsPackage() returns null on some
     * phones without a SIM (seen on Galaxy Z Fold6, Android 17) even when Dual Link holds the role.
     */
    fun isDefault(ctx: Context): Boolean =
        runCatching { ctx.getSystemService(android.app.role.RoleManager::class.java).isRoleHeld(android.app.role.RoleManager.ROLE_SMS) }
            .getOrDefault(false) || Telephony.Sms.getDefaultSmsPackage(ctx) == ctx.packageName

    /** Copies every finished message (received / sent / failed) that isn't in the system database yet. */
    fun sync(ctx: Context) {
        val app = ctx.applicationContext
        worker.execute {
            if (!isDefault(app)) {
                Log.i(TAG, "sync skipped: default SMS app is ${Telephony.Sms.getDefaultSmsPackage(app)}")
                return@execute
            }
            val store = Store.get(app)
            Log.i(TAG, "sync start: ${store.unmirrored(1000).size} to copy")
            var n = 0
            var failed = 0
            while (true) {
                val rows = store.unmirrored(200)
                if (rows.isEmpty()) break
                for (r in rows) {
                    val type = when (r.status) {
                        "received" -> Telephony.Sms.MESSAGE_TYPE_INBOX
                        "failed" -> Telephony.Sms.MESSAGE_TYPE_FAILED
                        else -> Telephony.Sms.MESSAGE_TYPE_SENT
                    }
                    val uri = runCatching {
                        app.contentResolver.insert(Telephony.Sms.CONTENT_URI, ContentValues().apply {
                            put(Telephony.Sms.ADDRESS, r.address); put(Telephony.Sms.BODY, r.body)
                            put(Telephony.Sms.DATE, r.ts); put(Telephony.Sms.DATE_SENT, r.ts)
                            put(Telephony.Sms.TYPE, type); put(Telephony.Sms.READ, if (r.read) 1 else 0)
                            put(Telephony.Sms.SEEN, 1)
                        })
                    }.onFailure { if (failed++ == 0) Log.w(TAG, "insert refused: $it") }.getOrNull()
                    // "-" marks a row the system refused, so it isn't retried forever.
                    store.setSysUri(r.id, uri?.toString() ?: "-")
                    n++
                }
            }
            if (n > 0) Log.i(TAG, "UAT mirrored n=$n failed=$failed")
        }
    }

    fun markRead(ctx: Context, uris: List<String>) = each(ctx, uris) { cr, u ->
        cr.update(u, ContentValues().apply { put(Telephony.Sms.READ, 1); put(Telephony.Sms.SEEN, 1) }, null, null)
    }

    fun delete(ctx: Context, uris: List<String>) = each(ctx, uris) { cr, u -> cr.delete(u, null, null) }

    private fun each(ctx: Context, uris: List<String>, op: (android.content.ContentResolver, Uri) -> Unit) {
        val app = ctx.applicationContext
        worker.execute {
            if (!isDefault(app)) return@execute
            uris.filter { it.startsWith("content://") }.forEach { runCatching { op(app.contentResolver, Uri.parse(it)) } }
        }
    }
}

/** SMS notifications with Reply, Mark as read and (for OTPs) Copy code. */
object Notifier {
    const val ACTION_REPLY = "com.duallink.app.REPLY"
    const val ACTION_READ = "com.duallink.app.MARK_READ"
    const val ACTION_COPY = "com.duallink.app.COPY_CODE"
    const val KEY_REPLY = "reply"
    private val otp = Regex("(?<![\\d])(\\d{4,8})(?![\\d])")

    fun notifId(address: String) = address.hashCode()

    /** First 4–8 digit number in a message that looks like a verification code. */
    fun codeIn(body: String): String? {
        val lower = body.lowercase()
        val looksLikeOtp = listOf("code", "otp", "pin", "verification", "password", "کوڈ").any { it in lower }
        return if (looksLikeOtp) otp.find(body)?.groupValues?.get(1) else null
    }

    private fun action(ctx: Context, action: String, address: String, extra: String? = null) =
        PendingIntent.getBroadcast(ctx, (action + address).hashCode(),
            Intent(ctx, NotificationActionReceiver::class.java).setAction(action)
                .putExtra("address", address).putExtra("extra", extra),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE) // mutable: RemoteInput needs it

    fun sms(ctx: Context, address: String, body: String) {
        ensureChannels(ctx)
        val open = PendingIntent.getActivity(ctx, notifId(address),
            Intent(ctx, MainActivity::class.java).putExtra("address", address),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val reply = NotificationCompat.Action.Builder(R.drawable.ic_stat_link, "Reply", action(ctx, ACTION_REPLY, address))
            .addRemoteInput(RemoteInput.Builder(KEY_REPLY).setLabel("Reply").build())
            .setAllowGeneratedReplies(true)
            .build()
        val b = NotificationCompat.Builder(ctx, CH_SMS)
            .setSmallIcon(R.drawable.ic_stat_link)
            .setContentTitle(displayName(ctx, address))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE) // hide text on lock screen
            .setPublicVersion(NotificationCompat.Builder(ctx, CH_SMS)
                .setSmallIcon(R.drawable.ic_stat_link).setContentTitle("New message").build())
            .setAutoCancel(true)
            .setContentIntent(open)
            .addAction(reply)
            .addAction(R.drawable.ic_stat_link, "Mark as read", action(ctx, ACTION_READ, address))
        codeIn(body)?.let { b.addAction(R.drawable.ic_stat_link, "Copy $it", action(ctx, ACTION_COPY, address, it)) }
        runCatching { NotificationManagerCompat.from(ctx).notify(notifId(address), b.build()) }
    }

    fun cancel(ctx: Context, address: String) = NotificationManagerCompat.from(ctx).cancel(notifId(address))
}

/** Handles the notification buttons. */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        val address = i.getStringExtra("address") ?: return
        when (i.action) {
            Notifier.ACTION_REPLY -> {
                val text = RemoteInput.getResultsFromIntent(i)?.getCharSequence(Notifier.KEY_REPLY)?.toString()?.trim()
                if (!text.isNullOrEmpty()) {
                    ClientService.sendSms(ctx, address, text, Store.get(ctx).lastIncomingSim(address))
                    markRead(ctx, address)
                }
                Notifier.cancel(ctx, address)
            }
            Notifier.ACTION_READ -> { markRead(ctx, address); Notifier.cancel(ctx, address) }
            Notifier.ACTION_COPY -> {
                val code = i.getStringExtra("extra") ?: return
                ctx.getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("Code", code))
                Toast.makeText(ctx, "Code $code copied", Toast.LENGTH_SHORT).show()
            }
        }
        Bus.emit("sms", mapOf("address" to address))
    }

    private fun markRead(ctx: Context, address: String) =
        SystemSms.markRead(ctx, Store.get(ctx).markRead(address))
}

/**
 * Required for the default SMS app role. On Phone B (no SIM) this normally never fires; if a SIM is
 * ever inserted, its texts are saved and shown like relayed ones. On Phone A, if someone makes
 * Dual Link the default there, the text is written to the phone's inbox so nothing is lost
 * (forwarding to Phone B still happens through SmsReceiver).
 */
class SmsDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        parts.groupBy { it.displayOriginatingAddress ?: "unknown" }.forEach { (from, list) ->
            val body = list.joinToString("") { it.displayMessageBody ?: "" }
            val ts = list.first().timestampMillis
            if (Prefs(ctx).mode == "gateway") {
                runCatching {
                    ctx.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, ContentValues().apply {
                        put(Telephony.Sms.ADDRESS, from); put(Telephony.Sms.BODY, body)
                        put(Telephony.Sms.DATE, System.currentTimeMillis()); put(Telephony.Sms.DATE_SENT, ts)
                    })
                }
            } else {
                if (Store.get(ctx).addMessage(UUID.randomUUID().toString(), from, body, ts, 0, "received", -2)) {
                    SystemSms.sync(ctx)
                    Notifier.sms(ctx, phoneKey(from), body)
                    Bus.emit("sms", mapOf("address" to phoneKey(from)))
                }
            }
        }
    }
}

/** Required for the default SMS app role. Phone B has no SIM, so MMS are not supported. */
class MmsDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {}
}

/** "Reply with a message" (e.g. from the call screen): sends through Phone A. Required for the role. */
class RespondViaMessageService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val to = intent?.data?.schemeSpecificPart?.substringBefore('?')?.split(';', ',')?.firstOrNull()
        val text = intent?.getStringExtra(Intent.EXTRA_TEXT)
        if (!to.isNullOrBlank() && !text.isNullOrBlank()) ClientService.sendSms(this, to, text, Store.get(this).lastIncomingSim(to))
        stopSelf(startId)
        return START_NOT_STICKY
    }
}
