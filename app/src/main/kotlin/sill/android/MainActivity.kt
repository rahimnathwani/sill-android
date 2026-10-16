package sill.android

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.text.InputType
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.window.OnBackInvokedDispatcher
import sill.protocol.AppInfo
import sill.protocol.Goodbye
import sill.protocol.PairLink
import sill.protocol.PairResult
import sill.protocol.PairedMac
import sill.protocol.Pairing
import sill.protocol.PairingCode
import sill.protocol.ParameterSets
import sill.protocol.Payloads
import sill.protocol.RecognitionTag
import sill.protocol.Session
import sill.protocol.SessionListener
import sill.protocol.Source
import sill.protocol.Target
import sill.protocol.Tls
import sill.protocol.WindowList
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * One activity, two screens: the Macs on this network (and the ones paired before), and the
 * stream. Everything that touches the network runs on [work]; the UI on the main thread.
 */
class MainActivity : Activity() {
    private lateinit var store: Store
    private lateinit var discovery: Discovery
    private val main = Handler(Looper.getMainLooper())
    private val work = Executors.newCachedThreadPool()

    // Connect screen.
    private lateinit var connectRoot: ScrollView
    private lateinit var list: LinearLayout
    private lateinit var statusLine: TextView
    private var found: List<FoundMac> = emptyList()

    // Stream screen.
    private var streamView: StreamView? = null
    private val decoder = VideoDecoder { w, h -> main.post { streamView?.setVideoSize(w, h) } }

    // The session and what it has said.
    @Volatile private var session: Session? = null
    private var current: Connection? = null
    private var windowList: WindowList? = null
    private val thumbnails = ConcurrentHashMap<Long, ByteArray>()
    private val icons = ConcurrentHashMap<String, ByteArray>()
    @Volatile private var apps: List<AppInfo> = emptyList()
    private var busy = false

    /** What the current or last session went to, for reconnecting. */
    private data class Connection(val target: Target, val name: String, val mac: PairedMac?)

    private var reconnectAttempt = 0
    private var reconnectRunnable: Runnable? = null
    private var userEnded = false
    private var resumeOnStart = false
    private var stopped = false
    /** A Mac that refused this phone's key once: one more dial decides (it may have been a blip). */
    private var refusedOnce: String? = null
    /** A session to make again once its Mac registers anew (Sill relaunched: a fresh tag). */
    private var waitingFor: Connection? = null
    private var waitingTag: String? = null
    private var lookingSince = 0L
    private val debuggable get() = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        discovery = Discovery(this) { macs -> found = macs; renderList(); reconnectIfRegistered() }
        buildConnectScreen()
        setContentView(connectRoot)
        work.execute { store.identity } // make the key off the main thread
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { back() }
        }
        handleLink(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleLink(intent)
    }

    override fun onStart() {
        super.onStart()
        stopped = false
        lookingSince = System.currentTimeMillis()
        main.postDelayed({ renderList() }, 3500)
        discovery.start()
        if (resumeOnStart) {
            resumeOnStart = false
            current?.let { c -> setStatus("Reconnecting to ${c.name}…"); startSession(c) }
        }
    }

    override fun onStop() {
        super.onStop()
        stopped = true
        discovery.stop()
        // Android stops a backgrounded app's network soon after; end cleanly and come back on return.
        if (session != null || (busy && current != null)) {
            resumeOnStart = true
            endSession(byUser = false, quiet = true)
        }
        cancelReconnect()
    }

    override fun onDestroy() {
        super.onDestroy()
        session?.close()
        decoder.shutdown()
        work.shutdownNow()
    }

    // Android 11 and 12 only: from 13 the OnBackInvokedCallback registered in onCreate takes Back,
    // and this is never called.
    @Suppress("DEPRECATION")
    @android.annotation.SuppressLint("GestureBackNavigation")
    @Deprecated("Below Android 13")
    override fun onBackPressed() = back()

    private fun back() {
        if (streamView != null) confirmDisconnect() else finish()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // A hardware keyboard while streaming goes to the Mac (Back stays Android's).
        val sv = streamView
        if (sv != null && event.keyCode != KeyEvent.KEYCODE_BACK && event.device?.isVirtual == false &&
            event.source and android.view.InputDevice.SOURCE_KEYBOARD == android.view.InputDevice.SOURCE_KEYBOARD &&
            sv.hardwareKey(event)) return true
        return super.dispatchKeyEvent(event)
    }

    // ---------------------------------------------------------------- Connect screen

    private fun buildConnectScreen() {
        connectRoot = ScrollView(this)
        connectRoot.setBackgroundColor(0xFF111113.toInt())
        connectRoot.isFillViewport = true
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(20), dp(24), dp(20), dp(24))
        connectRoot.addView(col)
        connectRoot.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            col.setPadding(dp(20) + bars.left, dp(24) + bars.top, dp(20) + bars.right, dp(24) + bars.bottom)
            insets
        }

        val title = TextView(this)
        title.text = "Sill"
        title.setTextColor(Color.WHITE)
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 34f)
        title.typeface = Typeface.DEFAULT_BOLD
        col.addView(title)

        val sub = TextView(this)
        sub.text = "Your Mac's windows on this phone. Needs the free Sill app on the Mac (getsill.app), on the same Wi-Fi.\n" +
            "An unofficial client, not made by Sill's developer. Version ${BuildInfo.version(this)}."
        sub.setTextColor(0xFFAAAAAA.toInt())
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        col.addView(sub, lp(top = 4))

        statusLine = TextView(this)
        statusLine.setTextColor(0xFFFFD60A.toInt())
        statusLine.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        statusLine.visibility = View.GONE
        col.addView(statusLine, lp(top = 16))

        list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        col.addView(list, lp(top = 16))

        col.addView(textButton("Connect by address…") { askAddress() }, lp(top = 20))
        col.addView(textButton("Look again") { discovery.restart() }, lp(top = 4))
        col.addView(textButton("Paired Macs…") { managePaired() }, lp(top = 4))

        val help = TextView(this)
        help.text = "Tap your Mac to connect. The first time, the Mac shows a 12-digit code: type it here. " +
            "Away from home, connect by the Mac's Tailscale name or address (Remote Access on the Mac, port 7455)."
        help.setTextColor(0xFF888888.toInt())
        help.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        col.addView(help, lp(top = 24))
        renderList()
    }

    private fun renderList() {
        if (!::list.isInitialized) return
        list.removeAllViews()
        val paired = store.pairedMacs()
        val shownPaired = HashSet<String>()
        val listed = found.filter { it.door != Door.PLAIN || debuggable }
        if (listed.isEmpty()) {
            val t = TextView(this)
            val waited = System.currentTimeMillis() - lookingSince > 3000
            t.text = discovery.failed ?: if (!waited) "Looking for Macs running Sill…" else
                "Looking for Macs running Sill…\n\nNot listed? Check that Sill is open on the Mac (its icon in the menu bar), " +
                "that the phone is on the same Wi-Fi (not a guest network, which keeps devices apart), and that the Mac is awake. " +
                "Or use Connect by address."
            t.setTextColor(0xFFAAAAAA.toInt())
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            list.addView(t, lp(bottom = 8))
        }
        for (m in listed) {
            val mac = matchPaired(m, paired)
            mac?.let { shownPaired.add(it.macID) }
            val word = when {
                m.door == Door.PLAIN -> "Plain (test host)"
                mac != null -> "Paired"
                m.door == Door.OPEN -> "Open"
                else -> "Not paired"
            }
            list.addView(row(m.serviceName, word, "${m.host}:${m.port}") { tapFound(m) }, lp(bottom = 8))
        }
        // Paired Macs not on this network: dial the last address that worked (a VPN, say).
        for (mac in paired) {
            if (mac.macID in shownPaired || mac.lastHost == null) continue
            list.addView(row(mac.name, "Last address", "${mac.lastHost}:${mac.lastPort}") {
                startSession(Connection(Target(mac.lastHost!!, mac.lastPort ?: 7455, mac.fingerprint), mac.name, mac))
            }, lp(bottom = 8))
        }
    }

    /** A listed Mac's saved record: by its recognition tag (any Bonjour name), else none. */
    private fun matchPaired(m: FoundMac, paired: List<PairedMac>): PairedMac? {
        val tag = m.tag ?: return null
        return paired.firstOrNull { p -> p.recognitionKey?.let { RecognitionTag.matches(tag, it) } == true }
    }

    private fun row(title: String, word: String, detail: String, onTap: () -> Unit): View {
        val r = LinearLayout(this)
        r.orientation = LinearLayout.VERTICAL
        r.background = GradientDrawable().apply { setColor(0xFF1E1E21.toInt()); cornerRadius = dp(12).toFloat() }
        r.setPadding(dp(16), dp(14), dp(16), dp(14))
        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        val t = TextView(this)
        t.text = title
        t.setTextColor(Color.WHITE)
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        top.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val w = TextView(this)
        w.text = word
        w.setTextColor(0xFF8E8E93.toInt())
        w.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        top.addView(w)
        r.addView(top)
        val d = TextView(this)
        d.text = detail
        d.setTextColor(0xFF636366.toInt())
        d.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        r.addView(d)
        r.isClickable = true
        r.isFocusable = true
        r.setOnClickListener { if (!busy) onTap() }
        r.contentDescription = "$title, $word"
        return r
    }

    private fun textButton(label: String, onTap: () -> Unit): TextView {
        val b = TextView(this)
        b.text = label
        b.setTextColor(0xFF0A84FF.toInt())
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        b.setPadding(0, dp(10), 0, dp(10))
        b.isClickable = true
        b.setOnClickListener { if (!busy) onTap() }
        return b
    }

    private fun setStatus(text: String?) {
        if (!::statusLine.isInitialized) return
        statusLine.text = text ?: ""
        statusLine.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    // ---------------------------------------------------------------- Connecting and pairing

    private fun tapFound(m: FoundMac) {
        cancelReconnect()
        waitingFor = null
        val mac = matchPaired(m, store.pairedMacs())
        when {
            m.door == Door.PLAIN -> startSession(Connection(Target(m.host, m.port, null, plain = true), m.serviceName, null))
            mac != null -> startSession(Connection(Target(m.host, m.port, mac.fingerprint), mac.name, mac))
            m.door == Door.OPEN -> startSession(Connection(Target(m.host, m.port, null), m.serviceName, null))
            else -> pair(m.host, m.port, m.serviceName)
        }
    }

    /** Asks the Mac to show its code, then takes the code typed here. */
    private fun pair(host: String, port: Int, name: String) {
        busy = true
        setStatus("Asking $name for a code…")
        work.execute {
            try {
                val asked = Pairing.ask(host, port, store.identity, store.deviceName, store.model)
                main.post {
                    busy = false
                    when (asked.result.reason) {
                        PairResult.SHOWN -> { setStatus(null); askCode(host, port, name, asked.macFingerprint, null) }
                        PairResult.CLOSED -> {
                            // The remote door never opens a window by itself: the person opens it on the Mac.
                            setStatus(null)
                            askCode(host, port, name, asked.macFingerprint,
                                "On the Mac, choose Pair iPhone or iPad… in Sill's menu, then type the code it shows.")
                        }
                        else -> setStatus(Pairing.describe(asked.result, name))
                    }
                }
            } catch (e: javax.net.ssl.SSLException) {
                // The remote door takes pairing connections only while the Mac shows its window.
                Log.w(TAG, "ask: $e")
                main.post {
                    busy = false
                    setStatus(null)
                    askCode(host, port, name, null,
                        "On the Mac, choose Pair iPhone or iPad… in Sill's menu, then type the code it shows.")
                }
            } catch (e: Exception) {
                Log.w(TAG, "ask: $e")
                main.post { busy = false; setStatus(networkWords(e, name)) }
            }
        }
    }

    private fun askCode(host: String, port: Int, name: String, macFp: ByteArray?, note: String?) {
        val field = EditText(this)
        field.inputType = InputType.TYPE_CLASS_NUMBER
        field.imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI or android.view.inputmethod.EditorInfo.IME_ACTION_DONE
        field.filters = arrayOf(InputFilter.LengthFilter(14))
        field.hint = "12 digits"
        field.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        field.letterSpacing = 0.1f
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(24), dp(8), dp(24), 0)
        if (note != null) box.addView(TextView(this).apply { text = note; setPadding(0, 0, 0, dp(8)) })
        box.addView(field)
        val dialog = AlertDialog.Builder(this)
            .setTitle("Enter the code from $name")
            .setView(box)
            .setPositiveButton("Pair", null)
            .setNegativeButton("Cancel") { _, _ -> cancelAsk(host, port, macFp) }
            .create()
        // Back or a tap outside withdraws the ask too, so the Mac's window closes.
        dialog.setOnCancelListener { cancelAsk(host, port, macFp) }
        dialog.setOnShowListener {
            field.requestFocus()
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
            field.setOnEditorActionListener { _, _, _ -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); true }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val (code, problem) = PairingCode.check(field.text.toString())
                if (code == null) {
                    field.error = if (problem == PairingCode.Problem.CHECK) "That isn't the code: check the digits." else "The code has 12 digits."
                    return@setOnClickListener
                }
                dialog.dismiss()
                pairWithCode(host, port, name, macFp, code)
            }
        }
        dialog.show()
    }

    private fun cancelAsk(host: String, port: Int, macFp: ByteArray?) {
        if (macFp == null) return     // no ask was made (the Mac's own window)
        work.execute { Pairing.cancel(host, port, store.identity, store.deviceName, store.model, macFp) }
    }

    private fun pairWithCode(host: String, port: Int, name: String, macFp: ByteArray?, code: String) {
        busy = true
        setStatus("Pairing with $name…")
        work.execute {
            try {
                val (mac, result) = Pairing.pairWithCode(host, port, store.identity, store.deviceName, store.model, code, macFp)
                main.post {
                    busy = false
                    if (mac != null) {
                        mac.lastHost = host; mac.lastPort = port
                        store.save(mac)
                        setStatus(null)
                        startSession(Connection(Target(host, port, mac.fingerprint), mac.name, mac))
                    } else if ((result.reason == PairResult.CODE && (result.triesLeft ?: 0) > 0) || result.reason == PairResult.BUSY) {
                        askCode(host, port, name, macFp, Pairing.describe(result, name))
                    } else {
                        setStatus(Pairing.describe(result, name))
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "pair: $e")
                main.post { busy = false; setStatus(networkWords(e, name)) }
            }
        }
    }

    /** A `sill://pair` link (the Mac's QR code, scanned with the camera): confirm, then pair at its addresses. */
    private fun handleLink(intent: Intent?) {
        val data = intent?.data?.toString() ?: return
        val link = PairLink.parse(data) ?: run { setStatus("That pairing link isn't valid."); return }
        intent.data = null
        AlertDialog.Builder(this)
            .setTitle("Pair with ${link.name}?")
            .setMessage("Only pair with a Mac you own, from a code it is showing now.")
            .setPositiveButton("Pair") { _, _ -> pairWithLink(link) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun pairWithLink(link: PairLink) {
        busy = true
        setStatus("Pairing with ${link.name}…")
        work.execute {
            // The link's own addresses at its port, then any Mac listed here (its home door): the
            // pin makes a wrong one fail at once.
            val tries = link.addresses + found.filter { it.door != Door.PLAIN }.map { it.host to it.port }
            var last: Exception? = null
            for ((host, port) in tries) {
                try {
                    val (mac, result) = Pairing.pairWithLink(host, port, store.identity, store.deviceName, store.model, link)
                    main.post {
                        busy = false
                        if (mac != null) {
                            mac.lastHost = host; mac.lastPort = port
                            store.save(mac)
                            setStatus(null)
                            startSession(Connection(Target(host, port, mac.fingerprint), mac.name, mac))
                        } else setStatus(Pairing.describe(result, link.name))
                    }
                    return@execute
                } catch (e: Exception) { last = e }
            }
            main.post { busy = false; setStatus(last?.let { networkWords(it, link.name) } ?: "Couldn't reach ${link.name}.") }
        }
    }

    private fun askAddress() {
        val field = EditText(this)
        field.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        field.imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI or android.view.inputmethod.EditorInfo.IME_ACTION_GO
        field.isSingleLine = true
        field.hint = "my-mac.tailnet.ts.net or 100.x.y.z:7455"
        store.recentAddresses().firstOrNull()?.let { field.setText(it) }
        field.setSelectAllOnFocus(true)
        val box = LinearLayout(this)
        box.setPadding(dp(24), dp(8), dp(24), 0)
        box.addView(field, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val dialog = AlertDialog.Builder(this)
            .setTitle("Connect by address")
            .setMessage("The Mac's address, and its port if not 7455 (Remote Access's).")
            .setView(box)
            .setPositiveButton("Connect", null)
            .setNegativeButton("Cancel", null)
            .create()
        val submit = {
            val text = field.text.toString().trim()
            val parsed = sill.protocol.Address.parse(text, 7455)
            if (parsed == null) field.error = "That isn't an address."
            else {
                dialog.dismiss()
                store.rememberAddress(text)
                connectAddress(parsed.first, parsed.second)
            }
        }
        // The keyboard's own key submits too: sideways, the keyboard covers the dialog's buttons.
        field.setOnEditorActionListener { _, _, _ -> submit(); true }
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { submit() } }
        dialog.show()
    }

    /** A typed address: a paired Mac there is dialed pinned; else pair first. */
    private fun connectAddress(host: String, port: Int) {
        val mac = store.pairedMacs().firstOrNull { it.lastHost == host && it.lastPort == port }
        if (mac != null) startSession(Connection(Target(host, port, mac.fingerprint), mac.name, mac))
        else {
            // Which Mac answers is only known from its key: look it up among the paired ones.
            busy = true
            setStatus("Connecting to $host…")
            work.execute {
                // Each paired Mac's pin in turn: a wrong one is refused here before this phone
                // shows its key, so the Mac counts nothing against it.
                var known: PairedMac? = null
                var failure: Exception? = null
                for (m in store.pairedMacs()) {
                    try {
                        val c = Tls.connect(host, port, store.identity, Tls.SESSION_ALPN, m.fingerprint)
                        try { c.socket.close() } catch (_: Exception) {}
                        known = m
                        break
                    } catch (_: Tls.PinRefused) {
                    } catch (e: Exception) { failure = e; break }
                }
                main.post {
                    busy = false
                    when {
                        known != null -> {
                            known.lastHost = host; known.lastPort = port; store.save(known)
                            startSession(Connection(Target(host, port, known.fingerprint), known.name, known))
                        }
                        failure != null -> setStatus(networkWords(failure, host) +
                            if (port == 7455) " Away from home, the Mac needs Remote Access on." else "")
                        else -> pair(host, port, host)
                    }
                }
            }
        }
    }

    private fun managePaired() {
        val paired = store.pairedMacs()
        if (paired.isEmpty()) { setStatus("No Macs paired yet."); return }
        val names = paired.map { "${it.name}  (${it.macID.take(6)})" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Forget a Mac")
            .setItems(names) { _, i ->
                AlertDialog.Builder(this)
                    .setTitle("Forget ${paired[i].name}?")
                    .setMessage("This phone will need its code again to connect. Remove the phone on the Mac too: Sill › Settings › Devices.")
                    .setPositiveButton("Forget") { _, _ -> store.forget(paired[i].macID); renderList() }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            .setNegativeButton("Done", null)
            .show()
    }

    private fun networkWords(e: Exception, name: String): String = when (e) {
        is Tls.PinRefused -> "$name has a new key since this phone paired. Forget it (Paired Macs…), then tap it to pair again."
        is Tls.AlpnRefused -> "$name's Sill is too old or too new for this app."
        is java.net.SocketTimeoutException -> "$name didn't answer."
        is java.net.ConnectException -> "$name refused the connection. Is Sill open on it?"
        is java.net.UnknownHostException -> "Couldn't find $name."
        is javax.net.ssl.SSLHandshakeException -> "$name refused this phone. On the Mac, check Sill › Settings › Devices; you may need to pair again."
        else -> "Couldn't reach $name: ${e.message ?: e.javaClass.simpleName}"
    }

    // ---------------------------------------------------------------- The session

    private fun startSession(c: Connection) {
        cancelReconnect()
        if (waitingFor?.mac?.macID != c.mac?.macID || c.mac == null) waitingFor = null
        busy = true
        userEnded = false
        current = c
        setStatus(if (reconnectAttempt > 0) "Reconnecting to ${c.name}…" else "Connecting to ${c.name}…")
        lateinit var s: Session
        s = Session(c.target, store.identity, store.deviceName, BuildInfo.VERSION, BuildInfo.BUILD, listenerFor { s })
        work.execute {
            try {
                s.connect()
                main.post {
                    busy = false
                    if (current != c) { s.close(); return@post }
                    if (stopped) { s.close(); resumeOnStart = true; return@post }
                    session = s
                    reconnectAttempt = 0
                    c.mac?.let { m -> if (m.lastHost != c.target.host || m.lastPort != c.target.port) { m.lastHost = c.target.host; m.lastPort = c.target.port; store.save(m) } }
                    showStream()
                    // The first viewport once the stream screen has its size.
                    whenLaidOut(streamView!!) { if (session === s) s.start(viewport()) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "connect: $e")
                main.post {
                    busy = false
                    if (e is Tls.PinRefused && c.mac != null) {
                        setStatus(networkWords(e, c.name))
                        return@post
                    }
                    if (reconnectAttempt > 0) scheduleReconnect() else setStatus(networkWords(e, c.name))
                }
            }
        }
    }

    private fun whenLaidOut(v: View, body: () -> Unit) {
        if (v.width > 0 && v.height > 0 && !v.isLayoutRequested) { body(); return }
        v.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
            override fun onLayoutChange(view: View, l: Int, t: Int, r: Int, b: Int, ol: Int, ot: Int, or: Int, ob: Int) {
                if (view.width == 0 || view.height == 0) return
                view.removeOnLayoutChangeListener(this)
                body()
            }
        })
    }

    private fun viewport(): Map<String, Any?> {
        val (w, h) = streamView?.panelSizeDp() ?: (800.0 to 450.0)
        @Suppress("DEPRECATION")
        val rate = (if (Build.VERSION.SDK_INT >= 30) display?.refreshRate else windowManager.defaultDisplay.refreshRate) ?: 60f
        val fps = when { rate >= 110 -> 120; rate >= 85 -> 90; else -> 60 }
        return Payloads.viewport(w, h, fps)
    }

    /** A session's events; its end counts only while it is still the current session. */
    private fun listenerFor(self: () -> Session) = object : SessionListener {
        override fun onWindowList(list: WindowList) { main.post { windowList = list; refusedOnce = null } }
        override fun onParameterSets(sets: ParameterSets) = decoder.onParameterSets(sets)
        override fun onFrame(payload: ByteArray, keyframe: Boolean, hostTimestamp: Double) = decoder.onFrame(payload, keyframe)
        override fun onThumbnail(windowID: Long, jpeg: ByteArray) { thumbnails[windowID] = jpeg }
        override fun onAppIcon(bundleID: String, png: ByteArray) { icons[bundleID] = png }
        override fun onAppList(apps: List<AppInfo>) { this@MainActivity.apps = apps }
        override fun onEnded(goodbye: Goodbye?, error: Throwable?) {
            main.post { if (session === self()) sessionEnded(goodbye, error) }
        }
    }

    private fun sessionEnded(goodbye: Goodbye?, error: Throwable?) {
        val c = current
        session = null
        if (userEnded) return
        showConnect()
        val name = c?.name ?: "The Mac"
        if (error is Session.Refused && c?.mac != null) {
            // The Mac no longer takes this phone's key. Dial once more (never a storm: the Mac
            // shuts out an address after five refusals in a minute), then pair afresh.
            if (refusedOnce != c.mac.macID) {
                refusedOnce = c.mac.macID
                setStatus("Checking with $name…")
                reconnectAttempt = 2
                scheduleReconnect()
            } else {
                refusedOnce = null
                store.forget(c.mac.macID)
                renderList()
                setStatus("$name no longer knows this phone (it was removed, or Sill was set up again). Tap it to pair again.")
            }
            return
        }
        if (goodbye != null) {
            val (words, again) = goodbye.describe(name, "phone")
            if (goodbye.reason == Goodbye.REMOVED || goodbye.reason == Goodbye.PAIRING_REQUIRED) c?.mac?.let { store.forget(it.macID); renderList() }
            setStatus(words)
            if (again && c != null) { reconnectAttempt = 1; scheduleReconnect() }
            else if (goodbye.reason == Goodbye.QUIT && c != null) waitForRegistration(c)
            return
        }
        Log.w(TAG, "session ended: $error")
        if (c != null) {
            reconnectAttempt = 1
            setStatus("Lost the connection to $name. Reconnecting…")
            scheduleReconnect()
        }
    }

    /** Connects again when [c]'s Mac registers anew (Sill opened again: a new recognition tag). */
    private fun waitForRegistration(c: Connection) {
        if (c.mac == null) return
        waitingFor = c
        waitingTag = found.firstOrNull { matchPaired(it, listOf(c.mac)) != null }?.tag
    }

    private fun reconnectIfRegistered() {
        val c = waitingFor ?: return
        if (session != null || busy || stopped) return
        val row = found.firstOrNull { matchPaired(it, listOf(c.mac!!)) != null } ?: return
        if (row.tag == waitingTag) return
        waitingFor = null
        startSession(c.copy(target = Target(row.host, row.port, c.mac!!.fingerprint)))
    }

    /** Dial again after 1, 2, 4, 8, then every 15 s, ten times at most. */
    private fun scheduleReconnect() {
        val c = current ?: return
        if (reconnectAttempt > 10) {
            setStatus("Couldn't reach ${c.name}. Tap it to try again.")
            reconnectAttempt = 0
            waitForRegistration(c)
            return
        }
        val delay = if (reconnectAttempt <= 4) 1000L shl (reconnectAttempt - 1) else 15_000L
        setStatus("Reconnecting to ${c.name}…")
        val r = Runnable {
            reconnectAttempt++
            // A Mac whose address changed: the listed row with its tag wins over the old address.
            val listed = c.mac?.let { m -> found.firstOrNull { f -> matchPaired(f, listOf(m)) != null } }
            val target = listed?.let { Target(it.host, it.port, c.mac.fingerprint) } ?: c.target
            startSession(c.copy(target = target))
        }
        reconnectRunnable = r
        main.postDelayed(r, delay)
    }

    private fun cancelReconnect() {
        reconnectRunnable?.let { main.removeCallbacks(it) }
        reconnectRunnable = null
    }

    private fun endSession(byUser: Boolean, quiet: Boolean = false) {
        userEnded = true
        cancelReconnect()
        reconnectAttempt = 0
        session?.close()
        session = null
        showConnect()
        if (byUser) { current = null; setStatus(null) }
        if (quiet) setStatus(null)
    }

    private fun confirmDisconnect() {
        AlertDialog.Builder(this)
            .setTitle("Disconnect from ${current?.name ?: "the Mac"}?")
            .setPositiveButton("Disconnect") { _, _ -> endSession(byUser = true) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------------------------------------------------------------- Screens

    private fun showStream() {
        if (streamView != null) return
        val sv = StreamView(this, send = { e -> session?.input(e) }, onWindows = { pickWindow() }, onDisconnect = { confirmDisconnect() })
        val prefs = getSharedPreferences("sill", MODE_PRIVATE)
        sv.drawMode = prefs.getBoolean("drawMode", false)
        sv.onDrawModeChange = { on -> prefs.edit().putBoolean("drawMode", on).apply() }
        sv.addSurfaceCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) { decoder.setSurface(holder.surface) }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) { decoder.setSurface(null) }
        })
        var lastSize = 0 to 0
        sv.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            // A turn or a window resize: the Mac gets the new panel size.
            val size = v.width to v.height
            if (size != lastSize && v.width > 0) { lastSize = size; session?.viewport(viewport()) }
        }
        streamView = sv
        setContentView(sv)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.insetsController?.let {
            it.hide(WindowInsets.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        sv.focusKeys()
    }

    private fun showConnect() {
        val sv = streamView ?: return
        streamView = null
        decoder.shutdown()
        thumbnails.clear()
        windowList = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.insetsController?.show(WindowInsets.Type.systemBars())
        (sv.parent as? ViewGroup)?.removeView(sv)
        setContentView(connectRoot)
        renderList()
    }

    /** The Mac's windows, the Desktop, and its apps. */
    private fun pickWindow() {
        val s = session ?: return
        val wl = windowList
        val items = ArrayList<Triple<String, String?, () -> Unit>>()
        items.add(Triple("Desktop", null) { s.select(Source.Desktop) })
        wl?.windows?.forEach { w ->
            val label = if (w.title.isBlank()) w.appName else "${w.appName} — ${w.title}"
            items.add(Triple(label, w.bundleID) { s.select(Source.Window(w.id)) })
        }
        items.add(Triple("Open an app…", null) { pickApp() })

        val listView = LinearLayout(this)
        listView.orientation = LinearLayout.VERTICAL
        val scroll = ScrollView(this)
        scroll.addView(listView)
        val dialog = AlertDialog.Builder(this).setTitle(wl?.macName ?: "Windows").setView(scroll).setNegativeButton("Close", null).create()
        for ((i, item) in items.withIndex()) {
            val r = LinearLayout(this)
            r.orientation = LinearLayout.HORIZONTAL
            r.gravity = Gravity.CENTER_VERTICAL
            r.setPadding(dp(20), dp(10), dp(20), dp(10))
            val active = when {
                i == 0 -> wl?.active == Source.Desktop
                i <= (wl?.windows?.size ?: 0) -> wl?.active == Source.Window(wl!!.windows[i - 1].id)
                else -> false
            }
            val img = ImageView(this)
            val bytes = when {
                i in 1..(wl?.windows?.size ?: 0) -> thumbnails[wl!!.windows[i - 1].id]
                else -> null
            } ?: item.second?.let { icons[it] }
            bytes?.let { b -> BitmapFactory.decodeByteArray(b, 0, b.size)?.let { img.setImageBitmap(it) } }
            img.scaleType = ImageView.ScaleType.FIT_CENTER
            r.addView(img, LinearLayout.LayoutParams(dp(72), dp(48)))
            val t = TextView(this)
            t.text = item.first
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            if (active) t.typeface = Typeface.DEFAULT_BOLD
            t.setPadding(dp(12), 0, 0, 0)
            r.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            r.isClickable = true
            r.setOnClickListener { dialog.dismiss(); item.third() }
            listView.addView(r)
        }
        dialog.show()
    }

    private fun pickApp() {
        val s = session ?: return
        val list = apps.sortedBy { it.name.lowercase() }
        if (list.isEmpty()) { AlertDialog.Builder(this).setMessage("The Mac hasn't sent its apps yet.").setPositiveButton("OK", null).show(); return }
        AlertDialog.Builder(this)
            .setTitle("Open an app")
            .setItems(list.map { it.name }.toTypedArray()) { _, i -> s.launchApp(list[i].bundleID) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------------------------------------------------------------- Helpers

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun lp(top: Int = 0, bottom: Int = 0) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(top); bottomMargin = dp(bottom)
        }

    companion object { private const val TAG = "Sill" }
}

/** The version this app tells the Mac in its hello: the package's own. */
object BuildInfo {
    @Volatile var VERSION = "0.1.0"
        private set
    @Volatile var BUILD = "1"
        private set

    fun version(context: Context): String {
        try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            VERSION = info.versionName ?: VERSION
            BUILD = info.longVersionCode.toString()
        } catch (_: Exception) {}
        return VERSION
    }
}

@Suppress("unused")
private fun Context.unused() = Unit
