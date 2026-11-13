package com.duallink.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.os.PowerManager
import android.provider.ContactsContract
import android.view.WindowManager
import android.provider.Settings
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel
import org.json.JSONObject

class MainActivity : FlutterActivity() {
    private var methods: MethodChannel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Launched from Contacts / a link / a notification: Flutter asks for this once it starts.
        pendingCompose = composeFrom(intent)
            ?: intent?.getStringExtra("address")?.let { mapOf("action" to "sms", "address" to it, "body" to null) }
        // Release builds: no screenshots / screen recording of SMS or the pairing QR code.
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    override fun configureFlutterEngine(engine: FlutterEngine) {
        super.configureFlutterEngine(engine)
        val messenger = engine.dartExecutor.binaryMessenger

        EventChannel(messenger, "duallink/events").setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(args: Any?, sink: EventChannel.EventSink) { Bus.sink = sink }
            override fun onCancel(args: Any?) { Bus.sink = null }
        })

        methods = MethodChannel(messenger, "duallink/native").apply {
            setMethodCallHandler { call, result ->
                val prefs = Prefs(this@MainActivity)
                val store = Store.get(this@MainActivity)
                try {
                    when (call.method) {
                        "getState" -> result.success(state(prefs))

                        "startGateway" -> {
                            if (prefs.key == null) prefs.key = Crypto.newKey()
                            prefs.mode = "gateway"; prefs.enabled = true
                            GatewayService.start(this@MainActivity)
                            result.success(state(prefs))
                        }

                        "pairClient" -> {
                            val j = JSONObject(call.argument<String>("qr")!!)
                            prefs.host = j.getString("h")
                            prefs.port = j.optInt("p", DEFAULT_PORT)
                            prefs.key = j.getString("k")
                            prefs.mode = "client"; prefs.enabled = true
                            ClientService.stop(this@MainActivity)
                            ClientService.start(this@MainActivity)
                            result.success(state(prefs))
                        }

                        "rotateKey" -> {
                            // New QR code; the old paired phone can no longer connect.
                            prefs.key = Crypto.newKey()
                            GatewayService.instance?.disconnectClient()
                            GatewayService.instance?.reRegisterNsd()
                            result.success(state(prefs))
                        }

                        "stop" -> {
                            prefs.enabled = false
                            GatewayService.stop(this@MainActivity)
                            ClientService.stop(this@MainActivity)
                            result.success(state(prefs))
                        }

                        // ---- Phone A voice setup (Wireless debugging, no PC) ----
                        "adbState" -> result.success(AdbSelf.state(this@MainActivity))
                        "adbOpenDevSettings" -> {
                            // Opens Settings scrolled to (and highlighting) the exact row the user needs.
                            val dev = AdbSelf.devOptionsOn(this@MainActivity)
                            val row = if (dev) "toggle_adb_wireless" else "build_number"
                            val target = if (dev) Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS else Settings.ACTION_DEVICE_INFO_SETTINGS
                            runCatching {
                                startActivity(Intent(target)
                                    .putExtra(":settings:fragment_args_key", row)
                                    .putExtra(":settings:show_fragment_args", android.os.Bundle().apply { putString(":settings:fragment_args_key", row) }))
                            }
                            result.success(null)
                        }
                        "adbAskCode" -> { AdbSelf.startPairing(this@MainActivity); result.success(null) }
                        "adbPair" -> { AdbSelf.pair(this@MainActivity, call.argument<String>("code") ?: ""); result.success(null) }
                        "setVoiceOn" -> {
                            val on = call.argument<Boolean>("on") == true
                            GatewayService.instance?.voice?.setEnabled(on) ?: run { prefs.voiceOn = on }
                            result.success(AdbSelf.state(this@MainActivity))
                        }
                        "adbStartVoice" -> {
                            Thread { AdbSelf.ensureHelper(this@MainActivity, force = true) }.start()
                            result.success(null)
                        }

                        "resume" -> {
                            prefs.enabled = true
                            if (prefs.mode == "gateway") GatewayService.start(this@MainActivity)
                            if (prefs.mode == "client") ClientService.start(this@MainActivity)
                            result.success(state(prefs))
                        }

                        "reset" -> {
                            GatewayService.stop(this@MainActivity)
                            ClientService.stop(this@MainActivity)
                            prefs.clear()
                            Store.wipe(this@MainActivity)
                            result.success(state(prefs))
                        }

                        "sendSms" -> result.success(
                            ClientService.sendSms(this@MainActivity, call.argument<String>("to")!!,
                                call.argument<String>("body")!!, call.argument<Int>("sim") ?: -1))

                        "contacts" -> {
                            if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.READ_CONTACTS)
                                != PackageManager.PERMISSION_GRANTED) return@setMethodCallHandler result.success(emptyList<Any>())
                            Thread {
                                val list = readContacts()
                                runOnUiThread { result.success(list) }
                            }.start()
                        }

                        "threads" -> result.success(store.threads())
                        "messages" -> result.success(store.messages(call.argument<String>("address")!!))
                        "markRead" -> {
                            val a = call.argument<String>("address")!!
                            SystemSms.markRead(this@MainActivity, store.markRead(a))
                            Notifier.cancel(this@MainActivity, phoneKey(a))
                            result.success(null)
                        }

                        "deleteThread" -> {
                            SystemSms.delete(this@MainActivity, store.deleteThread(call.argument<String>("address")!!))
                            result.success(null)
                        }

                        "calls" -> result.success(store.calls())

                        "voiceStart" -> {
                            ClientService.instance?.promoteForMic()
                            result.success(ClientVoice.start(this@MainActivity))
                        }
                        "voiceStop" -> { ClientVoice.stop(this@MainActivity); result.success(null) }
                        "voiceSpeaker" -> { ClientVoice.setSpeaker(this@MainActivity, call.argument<Boolean>("on") == true); result.success(null) }
                        "voiceMute" -> { ClientVoice.muted = call.argument<Boolean>("on") == true; result.success(null) }

                        "callCmd" -> {
                            val j = JSONObject().put("cmd", call.argument<String>("cmd"))
                            call.argument<String>("number")?.let { j.put("number", it) }
                            call.argument<Int>("sim")?.let { j.put("sim", it) }
                            result.success(ClientService.callCommand(j))
                        }

                        /** Android 14+: full-screen incoming-call alerts need the user's OK once. */
                        "canFullScreen" -> result.success(
                            Build.VERSION.SDK_INT < 34 || getSystemService(android.app.NotificationManager::class.java).canUseFullScreenIntent())

                        "openFullScreenSettings" -> {
                            if (Build.VERSION.SDK_INT >= 34) startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                                Uri.parse("package:$packageName")))
                            result.success(null)
                        }

                        "isDefaultSms" -> result.success(SystemSms.isDefault(this@MainActivity))

                        "requestDefaultSms" -> {
                            val rm = getSystemService(RoleManager::class.java)
                            if (rm.isRoleAvailable(RoleManager.ROLE_SMS) && !rm.isRoleHeld(RoleManager.ROLE_SMS)) {
                                @Suppress("DEPRECATION")
                                startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_SMS), 78)
                            }
                            result.success(null)
                        }

                        /** A compose request (number + text) that launched the app, if any. */
                        "takeLaunchCompose" -> { result.success(pendingCompose); pendingCompose = null }

                        "requestPermissions" -> {
                            val wanted = call.argument<List<String>>("names")!!.flatMap { permissionsFor(it) }
                            val missing = wanted.filter {
                                ContextCompat.checkSelfPermission(this@MainActivity, it) != PackageManager.PERMISSION_GRANTED
                            }
                            if (missing.isEmpty()) result.success(true)
                            else {
                                permResult = result
                                ActivityCompat.requestPermissions(this@MainActivity, missing.toTypedArray(), 77)
                            }
                        }

                        "openPrivacy" -> {
                            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_URL))) }
                            result.success(null)
                        }
                        "openAppSettings" -> {
                            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                            result.success(null)
                        }

                        "isIgnoringBattery" -> result.success(
                            (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName))

                        "requestIgnoreBattery" -> {
                            @SuppressLint("BatteryLife")
                            val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                            startActivity(i)
                            result.success(null)
                        }

                        else -> result.notImplemented()
                    }
                } catch (e: Exception) {
                    result.error("native", e.message, null)
                }
            }
        }
    }

    private fun state(p: Prefs): Map<String, Any?> {
        val ip = localIp()
        val qr = if (p.mode == "gateway" && p.key != null && ip != null)
            JSONObject().put("h", ip).put("p", p.port).put("k", p.key).toString() else null
        return mapOf(
            "mode" to p.mode,
            "enabled" to p.enabled,
            "ip" to ip,
            "port" to p.port,
            "host" to p.host,
            "qr" to qr,
            "gatewayRunning" to (GatewayService.instance != null),
            "voiceHelper" to (GatewayService.instance?.voice?.helperStatus ?: "not_started"),
            "clientRunning" to (ClientService.instance != null),
            "clientConnectedToGateway" to GatewayService.clientConnected,
            "pending" to Store.get(this).pendingCount(),
            "connected" to ClientService.connected,
            "battery" to ClientService.gatewayBattery,
            "sims" to ClientService.gatewaySims,
            "call" to ClientCalls.current,
            "voice" to ClientVoice.state(),
        )
    }

    /** Phone B's address book: one entry per (name, number), numbers normalized like conversations. */
    private fun readContacts(): List<Map<String, String>> {
        val out = LinkedHashMap<String, Map<String, String>>()
        contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            null, null, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " COLLATE NOCASE"
        )?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(0) ?: continue
                val number = phoneKey(c.getString(1) ?: continue)
                out.putIfAbsent("$name|$number", mapOf("name" to name, "number" to number))
            }
        }
        return out.values.toList()
    }

    private var permResult: MethodChannel.Result? = null

    private fun permissionsFor(name: String): List<String> = when (name) {
        "sms" -> listOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.SEND_SMS,
            Manifest.permission.READ_SMS, Manifest.permission.READ_PHONE_STATE)
        "contacts" -> listOf(Manifest.permission.READ_CONTACTS)
        "calls" -> listOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_CALL_LOG,
            Manifest.permission.ANSWER_PHONE_CALLS, Manifest.permission.CALL_PHONE)
        "notifications" -> if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()
        "camera" -> listOf(Manifest.permission.CAMERA)
        "mic" -> listOf(Manifest.permission.RECORD_AUDIO)
        else -> emptyList()
    }

    /** Resolves with true only if every requested permission was granted. */
    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, grants: IntArray) {
        super.onRequestPermissionsResult(code, perms, grants)
        if (code != 77) return
        permResult?.success(grants.isNotEmpty() && grants.all { it == PackageManager.PERMISSION_GRANTED })
        permResult = null
    }

    private var pendingCompose: Map<String, String?>? = null

    /** sms:/smsto: from Contacts, links or other apps -> {address, body}. */
    private fun composeFrom(i: Intent?): Map<String, String?>? {
        // Answer only if the intent came from our own notification (secret token) and a call is ringing.
        if (i?.getBooleanExtra("answerHere", false) == true) {
            val ok = i.getStringExtra("token") == ClientCalls.launchToken && ClientCalls.current?.get("state") == "ringing"
            return mapOf("action" to if (ok) "answerHere" else "calls", "address" to i.getStringExtra("address"))
        }
        if (i?.getBooleanExtra("callScreen", false) == true) return mapOf("action" to "calls", "address" to i.getStringExtra("address"))
        val data = i?.data ?: return null
        if (data.scheme == "tel") {
            val n = Uri.decode(data.schemeSpecificPart).trim()
            return if (n.isEmpty()) null else mapOf("action" to "call", "address" to phoneKey(n))
        }
        if (data.scheme !in setOf("sms", "smsto", "mms", "mmsto")) return null
        val to = Uri.decode(data.schemeSpecificPart.substringBefore('?')).split(';', ',').firstOrNull()?.trim()
        if (to.isNullOrEmpty()) return null
        val body = i.getStringExtra("sms_body") ?: i.getStringExtra(Intent.EXTRA_TEXT)
            ?: data.getQueryParameterSafe("body")
        return mapOf("action" to "sms", "address" to phoneKey(to), "body" to body)
    }

    private fun Uri.getQueryParameterSafe(name: String): String? =
        runCatching { Uri.parse("x://y?" + (schemeSpecificPart.substringAfter('?', ""))).getQueryParameter(name) }.getOrNull()

    override fun onResume() {
        super.onResume()
        SystemSms.sync(this) // e.g. just became the default SMS app: copy existing messages in
        Bus.emit("client", emptyMap())
    }

    /** Tapping an SMS notification, a contact's "Message" button or an sms: link while open. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        composeFrom(intent)?.let { methods?.invokeMethod("openCompose", it); return }
        intent.getStringExtra("address")?.let { methods?.invokeMethod("openThread", it) }
    }
}
