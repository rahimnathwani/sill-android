package sill.android

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import sill.protocol.Input
import sill.protocol.Modifiers
import sill.protocol.PointerAction
import sill.protocol.ScrollPhase
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * The stream screen: the Mac's picture, letterboxed, with direct touch on it.
 *
 * - Tap: click there. Two fingers tapped together: right-click.
 * - One finger dragged: scroll (a trackpad-like gesture with its coast).
 * - Press and hold, then move: drag (select text, move a window). Hold and lift: right-click.
 * - Two fingers pinched or moved: zoom and pan the picture on this phone (nothing sent).
 * - The bar: Windows, Keyboard (with a key row for Esc, Tab, ⌘ ⌃ ⌥ ⇧ and arrows), Fit, Disconnect.
 */
/** What a touch on the picture is doing. */
private enum class Mode { NONE, PENDING, SCROLL, HELD, DRAG, TWO }

@SuppressLint("ViewConstructor")
class StreamView(
    context: Context,
    private val send: (Map<String, Any?>) -> Unit,
    private val onWindows: () -> Unit,
    private val onDisconnect: () -> Unit,
) : FrameLayout(context) {

    val surface = SurfaceView(context)
    private val touch = TouchLayer(context)
    private val keys = KeyCatcher(context)
    private val bar = LinearLayout(context)
    private val keyRow = LinearLayout(context)
    private val keyRowScroll = HorizontalScrollView(context)
    private val status = TextView(context)
    private val fitButton: Button
    private val main = Handler(Looper.getMainLooper())

    private var videoW = 16
    private var videoH = 10
    /** Local zoom: the picture's scale over the fitted size and its centre's offset, in px. */
    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f
    private var keyboardShown = false
    private var imeBottom = 0

    /** Latched modifiers from the key row, applied to the next key, text or click. */
    private val latched = LinkedHashSet<Long>()
    private val latchButtons = HashMap<Long, Button>()

    var ctrlIsCommand = true

    init {
        setBackgroundColor(Color.BLACK)
        clipChildren = true
        addView(surface, LayoutParams(1, 1))
        addView(touch, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(keys, LayoutParams(1, 1))

        status.setTextColor(Color.WHITE)
        status.textSize = 14f
        status.gravity = Gravity.CENTER
        status.text = "Waiting for the picture…"
        addView(status, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        bar.orientation = LinearLayout.HORIZONTAL
        bar.background = pill(0xB0202020.toInt())
        bar.setPadding(dp(4), dp(2), dp(4), dp(2))
        barButton("Windows") { onWindows() }
        barButton("Keyboard") { toggleKeyboard() }
        fitButton = barButton("Fit") { resetZoom() }
        fitButton.visibility = GONE
        barButton("✕") { onDisconnect() }
        addView(bar, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL))

        keyRow.orientation = LinearLayout.HORIZONTAL
        keyRow.setPadding(dp(4), dp(4), dp(4), dp(4))
        keyRowScroll.addView(keyRow)
        keyRowScroll.isHorizontalScrollBarEnabled = false
        keyRowScroll.setBackgroundColor(0xE0181818.toInt())
        keyRowScroll.visibility = GONE
        keyButton("esc") { tapKey(0x29) }
        keyButton("tab") { tapKey(0x2B) }
        latchButton("⌘", Modifiers.COMMAND)
        latchButton("⌃", Modifiers.CONTROL)
        latchButton("⌥", Modifiers.OPTION)
        latchButton("⇧", Modifiers.SHIFT)
        keyButton("←") { tapKey(0x50) }
        keyButton("↑") { tapKey(0x52) }
        keyButton("↓") { tapKey(0x51) }
        keyButton("→") { tapKey(0x4F) }
        keyButton("⌫") { tapKey(0x2A) }
        keyButton("⏎") { tapKey(0x28) }
        keyButton("Spotlight") { chord(0x2C, Modifiers.COMMAND) }
        addView(keyRowScroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        setOnApplyWindowInsetsListener { _, insets ->
            val ime = insets.getInsets(android.view.WindowInsets.Type.ime()).bottom
            val sys = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
            imeBottom = ime
            val wasShown = keyboardShown
            keyboardShown = ime > 0
            if (wasShown && !keyboardShown) keyRowScroll.visibility = GONE
            if (keyboardShown) keyRowScroll.visibility = VISIBLE
            (bar.layoutParams as LayoutParams).topMargin = sys.top + dp(4)
            (keyRowScroll.layoutParams as LayoutParams).bottomMargin = ime
            bar.requestLayout(); keyRowScroll.requestLayout()
            requestLayout()
            insets
        }
    }

    // --- Layout: the picture fits the room above the keyboard, then local zoom ---

    fun setVideoSize(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        status.visibility = GONE
        if (w == videoW && h == videoH) return
        videoW = w; videoH = h
        surface.holder.setFixedSize(w, h)
        resetZoom()
    }

    fun showStatus(text: String?) {
        status.text = text ?: ""
        status.visibility = if (text == null) GONE else VISIBLE
    }

    /** The room the picture fits in: the whole view, less the keyboard and its key row. */
    private fun room(): RectF {
        val rowH = if (keyRowScroll.visibility == VISIBLE) keyRowScroll.measuredHeight else 0
        val bottom = height - imeBottom - rowH
        return RectF(0f, 0f, width.toFloat(), max(bottom, height / 4).toFloat())
    }

    /** The fitted picture before zoom. */
    private fun fitted(): RectF {
        val r = room()
        val s = min(r.width() / videoW, r.height() / videoH)
        val w = videoW * s; val h = videoH * s
        return RectF(r.centerX() - w / 2, r.centerY() - h / 2, r.centerX() + w / 2, r.centerY() + h / 2)
    }

    /** Where the picture is drawn, zoom included. */
    fun videoRect(): RectF {
        val f = fitted()
        val w = f.width() * zoom; val h = f.height() * zoom
        val cx = f.centerX() + panX; val cy = f.centerY() + panY
        return RectF(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        clampPan()
        val v = videoRect()
        val rect = Rect(v.left.toInt(), v.top.toInt(), v.right.toInt(), v.bottom.toInt())
        if (surface.left != rect.left || surface.top != rect.top || surface.width != rect.width() || surface.height != rect.height()) {
            surface.measure(MeasureSpec.makeMeasureSpec(rect.width(), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(rect.height(), MeasureSpec.EXACTLY))
            surface.layout(rect.left, rect.top, rect.right, rect.bottom)
        }
    }

    private fun clampPan() {
        val f = fitted()
        val r = room()
        val w = f.width() * zoom; val h = f.height() * zoom
        // Keep the picture covering the room where it can, centred where it is smaller.
        val maxX = max(0f, (w - r.width()) / 2); val maxY = max(0f, (h - r.height()) / 2)
        val baseX = r.centerX() - f.centerX(); val baseY = r.centerY() - f.centerY()
        panX = (panX - baseX).coerceIn(-maxX, maxX) + baseX
        panY = (panY - baseY).coerceIn(-maxY, maxY) + baseY
        if (zoom <= 1.001f) { panX = 0f; panY = 0f }
    }

    private fun resetZoom() {
        zoom = 1f; panX = 0f; panY = 0f
        fitButton.visibility = GONE
        requestLayout()
    }

    /** A point in this view as a fraction of the picture; null outside it unless [clamped]. */
    private fun normalized(x: Float, y: Float, clamped: Boolean): Pair<Double, Double>? {
        val v = videoRect()
        if (v.width() <= 0 || v.height() <= 0) return null
        var nx = ((x - v.left) / v.width()).toDouble()
        var ny = ((y - v.top) / v.height()).toDouble()
        if (nx !in 0.0..1.0 || ny !in 0.0..1.0) {
            if (!clamped) return null
            nx = nx.coerceIn(0.0, 1.0); ny = ny.coerceIn(0.0, 1.0)
        }
        return nx to ny
    }

    // --- Sending ---

    private fun pointer(action: PointerAction, p: Pair<Double, Double>) = send(Input.pointer(action, p.first, p.second))

    /** The latched modifiers' own keys down, around [body], then up: how a keyboard sends a chord. */
    private fun withLatched(body: () -> Unit) {
        val mods = latched.toList()
        for (m in mods) send(Input.key(modifierUsage(m), true, 0))
        body()
        for (m in mods.reversed()) send(Input.key(modifierUsage(m), false, 0))
        clearLatches()
    }

    private fun modifierUsage(m: Long) = when (m) {
        Modifiers.COMMAND -> 0xE3; Modifiers.CONTROL -> 0xE0; Modifiers.OPTION -> 0xE2; else -> 0xE1
    }

    private fun latchedFlags() = latched.fold(0L) { a, m -> a or m }

    private fun tapKey(usage: Int) = chord(usage, 0)

    private fun chord(usage: Int, extra: Long) {
        val mods = latchedFlags() or extra
        val held = listOf(Modifiers.COMMAND, Modifiers.CONTROL, Modifiers.OPTION, Modifiers.SHIFT).filter { mods and it != 0L }
        for (m in held) send(Input.key(modifierUsage(m), true, 0))
        send(Input.key(usage, true, mods))
        send(Input.key(usage, false, mods))
        for (m in held.reversed()) send(Input.key(modifierUsage(m), false, 0))
        clearLatches()
    }

    private fun clearLatches() {
        if (latched.isEmpty()) return
        latched.clear()
        latchButtons.values.forEach { it.isSelected = false; it.alpha = 1f; styleKey(it, false) }
    }

    /** Text from the software keyboard. With a modifier latched, a letter or digit is a shortcut. */
    fun typed(text: String) {
        if (text.isEmpty()) return
        if (latched.isNotEmpty() && text.length == 1) {
            val usage = usageFor(text[0])
            if (usage != null) { chord(usage, if (text[0].isUpperCase()) Modifiers.SHIFT else 0); return }
        }
        send(Input.text(text))
    }

    private fun usageFor(c: Char): Int? = when (c.lowercaseChar()) {
        in 'a'..'z' -> 0x04 + (c.lowercaseChar() - 'a')
        in '1'..'9' -> 0x1E + (c - '1')
        '0' -> 0x27
        ' ' -> 0x2C
        else -> null
    }

    // --- Keyboard ---

    fun toggleKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        if (keyboardShown) {
            imm.hideSoftInputFromWindow(windowToken, 0)
        } else {
            keys.requestFocus()
            imm.showSoftInput(keys, 0)
            keyRowScroll.visibility = VISIBLE
        }
    }

    fun focusKeys() { keys.requestFocus() }

    /** A hardware key; returns whether it was taken. */
    fun hardwareKey(event: KeyEvent): Boolean {
        val usage = HidKeys.usage(event.keyCode) ?: return false
        if (event.action == KeyEvent.ACTION_MULTIPLE) return false
        if (event.repeatCount > 0 && event.action == KeyEvent.ACTION_DOWN) {
            // A held key repeats on the Mac by itself.
            return true
        }
        val down = event.action == KeyEvent.ACTION_DOWN
        send(Input.key(HidKeys.swapped(usage, ctrlIsCommand), down, HidKeys.modifiers(event.metaState, ctrlIsCommand)))
        return true
    }

    private inner class KeyCatcher(context: Context) : View(context) {
        private var composing = ""

        init { isFocusable = true; isFocusableInTouchMode = true }

        override fun onCheckIsTextEditor() = true

        override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
            // A visible-password field: no suggestions, no autocorrect, keys as they are typed.
            outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_ACTION_NONE
            composing = ""
            return object : BaseInputConnection(this, false) {
                override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                    val t = text?.toString() ?: ""
                    replaceComposing(t)
                    composing = ""
                    return true
                }

                override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
                    val t = text?.toString() ?: ""
                    replaceComposing(t)
                    composing = t
                    return true
                }

                override fun finishComposingText(): Boolean { composing = ""; return true }

                override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                    repeat(beforeLength.coerceIn(0, 64)) { send(Input.text("\u0008")) }
                    return true
                }

                override fun sendKeyEvent(event: KeyEvent): Boolean {
                    if (event.action != KeyEvent.ACTION_DOWN) return true
                    when (event.keyCode) {
                        KeyEvent.KEYCODE_DEL -> if (latched.isNotEmpty()) tapKey(0x2A) else send(Input.text("\u0008"))
                        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> if (latched.isNotEmpty()) tapKey(0x28) else send(Input.text("\n"))
                        KeyEvent.KEYCODE_TAB -> send(Input.text("\t"))
                        else -> {
                            val c = event.unicodeChar
                            if (c != 0) typed(String(Character.toChars(c)))
                            else HidKeys.usage(event.keyCode)?.let { tapKey(it) }
                        }
                    }
                    return true
                }

                override fun performEditorAction(actionCode: Int): Boolean { send(Input.text("\n")); return true }
            }
        }

        /** Sends what turns the composing text the Mac already has into [t]. */
        private fun replaceComposing(t: String) {
            var common = 0
            while (common < composing.length && common < t.length && composing[common] == t[common]) common++
            repeat(composing.length - common) { send(Input.text("\u0008")) }
            typed(t.substring(common))
        }

        override fun onKeyDown(keyCode: Int, event: KeyEvent) = hardwareKey(event) || super.onKeyDown(keyCode, event)
        override fun onKeyUp(keyCode: Int, event: KeyEvent) = hardwareKey(event) || super.onKeyUp(keyCode, event)
    }

    // --- Touch ---

    private inner class TouchLayer(context: Context) : View(context) {
        private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
        private val longPress = ViewConfiguration.getLongPressTimeout().toLong()

        private var mode = Mode.NONE
        private var downX = 0f; private var downY = 0f
        private var lastX = 0f; private var lastY = 0f
        private var downTime = 0L
        private var twoStartSpan = 0f; private var twoStartZoom = 1f
        private var twoLastCx = 0f; private var twoLastCy = 0f
        private var twoMoved = false
        private var twoDownTime = 0L
        private val velocity = VelocityTracker()
        private val momentum = Momentum()

        private val holdRunnable = Runnable {
            if (mode == Mode.PENDING) {
                mode = Mode.HELD
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    momentum.stop()
                    focusKeys()
                    mode = Mode.PENDING
                    downX = e.x; downY = e.y; lastX = e.x; lastY = e.y
                    downTime = SystemClock.uptimeMillis()
                    velocity.clear(); velocity.add(e.eventTime, e.x, e.y)
                    main.postDelayed(holdRunnable, longPress)
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    main.removeCallbacks(holdRunnable)
                    when (mode) {
                        Mode.SCROLL -> endScroll(coast = false)
                        Mode.DRAG -> normalized(lastX, lastY, true)?.let { p -> withLatched { pointer(PointerAction.LEFT_UP, p) } }
                        else -> {}
                    }
                    if (e.pointerCount == 2) {
                        mode = Mode.TWO
                        twoStartSpan = span(e); twoStartZoom = zoom
                        twoLastCx = (e.getX(0) + e.getX(1)) / 2; twoLastCy = (e.getY(0) + e.getY(1)) / 2
                        twoMoved = false
                        twoDownTime = SystemClock.uptimeMillis()
                    } else mode = Mode.NONE
                }
                MotionEvent.ACTION_MOVE -> move(e)
                MotionEvent.ACTION_POINTER_UP -> {
                    if (mode == Mode.TWO) {
                        // Two fingers tapped together, nothing moved: a right-click between them.
                        val quick = SystemClock.uptimeMillis() - twoDownTime < 300
                        if (!twoMoved && quick) normalized(twoLastCx, twoLastCy, false)?.let { p -> rightClick(p) }
                    }
                    mode = Mode.NONE
                }
                MotionEvent.ACTION_UP -> {
                    main.removeCallbacks(holdRunnable)
                    velocity.add(e.eventTime, e.x, e.y)
                    when (mode) {
                        Mode.PENDING -> normalized(e.x, e.y, false)?.let { p -> click(p) }
                        Mode.HELD -> normalized(e.x, e.y, false)?.let { p -> rightClick(p) }
                        Mode.SCROLL -> endScroll(coast = true)
                        Mode.DRAG -> normalized(e.x, e.y, true)?.let { p ->
                            pointer(PointerAction.MOVE, p); pointer(PointerAction.LEFT_UP, p); upLatched()
                        }
                        else -> {}
                    }
                    mode = Mode.NONE
                }
                MotionEvent.ACTION_CANCEL -> {
                    main.removeCallbacks(holdRunnable)
                    if (mode == Mode.SCROLL) endScroll(coast = false)
                    if (mode == Mode.DRAG) normalized(lastX, lastY, true)?.let { p -> pointer(PointerAction.LEFT_UP, p); upLatched() }
                    mode = Mode.NONE
                }
            }
            return true
        }

        private fun move(e: MotionEvent) {
            when (mode) {
                Mode.PENDING -> {
                    velocity.add(e.eventTime, e.x, e.y)
                    if (hypot(e.x - downX, e.y - downY) > slop) {
                        main.removeCallbacks(holdRunnable)
                        mode = Mode.SCROLL
                        normalized(downX, downY, true)?.let { p -> send(Input.scrollGesture(ScrollPhase.BEGAN, p.first, p.second)) }
                        lastX = downX; lastY = downY
                        scrollTo(e.x, e.y)
                    }
                }
                Mode.SCROLL -> { velocity.add(e.eventTime, e.x, e.y); scrollTo(e.x, e.y) }
                Mode.HELD -> if (hypot(e.x - downX, e.y - downY) > slop) {
                    // Held, then moved: a drag from where the finger came down.
                    mode = Mode.DRAG
                    normalized(downX, downY, true)?.let { p ->
                        downLatched()
                        pointer(PointerAction.MOVE, p); pointer(PointerAction.LEFT_DOWN, p)
                    }
                    normalized(e.x, e.y, true)?.let { p -> pointer(PointerAction.MOVE, p) }
                    lastX = e.x; lastY = e.y
                }
                Mode.DRAG -> {
                    normalized(e.x, e.y, true)?.let { p -> pointer(PointerAction.MOVE, p) }
                    lastX = e.x; lastY = e.y
                }
                Mode.TWO -> if (e.pointerCount >= 2) {
                    val cx = (e.getX(0) + e.getX(1)) / 2; val cy = (e.getY(0) + e.getY(1)) / 2
                    val s = span(e)
                    if (abs(s - twoStartSpan) > slop || hypot(cx - twoLastCx, cy - twoLastCy) > 2) twoMoved = true
                    if (twoMoved) {
                        val before = videoRect()
                        val newZoom = (twoStartZoom * s / max(twoStartSpan, 1f)).coerceIn(1f, 5f)
                        // Keep the point under the fingers' centre where it was.
                        val fx = (twoLastCx - before.left) / before.width(); val fy = (twoLastCy - before.top) / before.height()
                        zoom = newZoom
                        val after = videoRect()
                        panX += (cx - (after.left + fx * after.width()))
                        panY += (cy - (after.top + fy * after.height()))
                        fitButton.visibility = if (zoom > 1.01f) VISIBLE else GONE
                        this@StreamView.requestLayout()
                    }
                    twoLastCx = cx; twoLastCy = cy
                }
                else -> {}
            }
        }

        private fun span(e: MotionEvent) = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))

        private fun click(p: Pair<Double, Double>) = withLatched {
            pointer(PointerAction.MOVE, p); pointer(PointerAction.LEFT_DOWN, p); pointer(PointerAction.LEFT_UP, p)
        }

        private fun rightClick(p: Pair<Double, Double>) {
            performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            withLatched { pointer(PointerAction.MOVE, p); pointer(PointerAction.RIGHT_DOWN, p); pointer(PointerAction.RIGHT_UP, p) }
        }

        private var dragMods: List<Long> = emptyList()
        private fun downLatched() {
            dragMods = latched.toList()
            for (m in dragMods) send(Input.key(modifierUsage(m), true, 0))
        }
        private fun upLatched() {
            for (m in dragMods.reversed()) send(Input.key(modifierUsage(m), false, 0))
            dragMods = emptyList()
            clearLatches()
        }

        private fun scrollTo(x: Float, y: Float) {
            val v = videoRect()
            if (v.width() <= 0) return
            val dx = (x - lastX) / v.width(); val dy = (y - lastY) / v.height()
            lastX = x; lastY = y
            if (dx == 0f && dy == 0f) return
            normalized(x, y, true)?.let { p -> send(Input.scroll(p.first, p.second, dx.toDouble(), dy.toDouble())) }
        }

        private fun endScroll(coast: Boolean) {
            val p = normalized(lastX, lastY, true) ?: (0.5 to 0.5)
            send(Input.scrollGesture(ScrollPhase.ENDED, p.first, p.second))
            if (!coast) return
            val (vx, vy) = velocity.velocity()
            momentum.start(vx, vy, p)
        }
    }

    /** Recent positions, for the lift's speed in px per second. */
    private class VelocityTracker {
        private val t = LongArray(8); private val xs = FloatArray(8); private val ys = FloatArray(8)
        private var n = 0
        fun clear() { n = 0 }
        fun add(time: Long, x: Float, y: Float) {
            if (n == 8) { System.arraycopy(t, 1, t, 0, 7); System.arraycopy(xs, 1, xs, 0, 7); System.arraycopy(ys, 1, ys, 0, 7); n = 7 }
            t[n] = time; xs[n] = x; ys[n] = y; n++
        }
        fun velocity(): Pair<Float, Float> {
            if (n < 2) return 0f to 0f
            var i = 0
            while (i < n - 2 && t[n - 1] - t[i] > 100) i++
            val dt = (t[n - 1] - t[i]) / 1000f
            if (dt <= 0f) return 0f to 0f
            return (xs[n - 1] - xs[i]) / dt to (ys[n - 1] - ys[i]) / dt
        }
    }

    /**
     * The coast after a flick (InputOverlay.swift's ScrollMomentum): the Mac adds momentum only in
     * its trackpad driver, so a synthetic gesture that ends just stops unless the device sends it.
     */
    private inner class Momentum {
        private var vx = 0.0; private var vy = 0.0
        private var at: Pair<Double, Double> = 0.5 to 0.5
        private var running = false
        private var last = 0L
        private val minimumSpeed = dp(150).toDouble()
        private val tick = object : Runnable {
            override fun run() {
                if (!running) return
                val now = SystemClock.uptimeMillis()
                val dt = min((now - last) / 1000.0, 0.1)
                last = now
                val decay = Math.pow(0.96, dt * 60)
                vx *= decay; vy *= decay
                val stepX = vx * dt; val stepY = vy * dt
                if (hypot(stepX, stepY) < dp(1) * 0.3) { stop(); return }
                step(stepX, stepY)
                main.postDelayed(this, 16)
            }
        }

        fun start(vx0: Float, vy0: Float, p: Pair<Double, Double>) {
            stop()
            if (hypot(vx0.toDouble(), vy0.toDouble()) < minimumSpeed) return
            vx = vx0.toDouble(); vy = vy0.toDouble(); at = p
            running = true
            send(Input.scrollGesture(ScrollPhase.MOMENTUM_BEGAN, p.first, p.second))
            step(vx / 60, vy / 60)
            last = SystemClock.uptimeMillis()
            main.postDelayed(tick, 16)
        }

        private fun step(dx: Double, dy: Double) {
            val v = videoRect()
            if (v.width() <= 0) return
            send(Input.scroll(at.first, at.second, dx / v.width(), dy / v.height()))
        }

        fun stop() {
            if (!running) return
            running = false
            main.removeCallbacks(tick)
            send(Input.scrollGesture(ScrollPhase.MOMENTUM_ENDED, at.first, at.second))
        }
    }

    // --- Bar and key row ---

    private fun barButton(label: String, action: () -> Unit): Button {
        val b = Button(context)
        b.text = label
        b.isAllCaps = false
        b.setTextColor(Color.WHITE)
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        b.background = null
        b.minWidth = dp(44); b.minimumWidth = dp(44)
        b.minHeight = dp(36); b.minimumHeight = dp(36)
        b.setPadding(dp(10), 0, dp(10), 0)
        b.setOnClickListener { action() }
        bar.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)))
        return b
    }

    private fun keyButton(label: String, action: () -> Unit): Button {
        val b = Button(context)
        b.text = label
        b.isAllCaps = false
        b.setTextColor(Color.WHITE)
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        styleKey(b, false)
        b.minWidth = dp(44); b.minimumWidth = dp(44)
        b.minHeight = dp(40); b.minimumHeight = dp(40)
        b.setPadding(dp(10), 0, dp(10), 0)
        b.isFocusable = false
        b.setOnClickListener { action() }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40))
        lp.marginEnd = dp(6)
        keyRow.addView(b, lp)
        return b
    }

    private fun latchButton(label: String, flag: Long) {
        val b = keyButton(label) {}
        b.setOnClickListener {
            if (latched.remove(flag)) styleKey(b, false) else { latched.add(flag); styleKey(b, true) }
        }
        latchButtons[flag] = b
    }

    private fun styleKey(b: Button, on: Boolean) {
        b.background = pill(if (on) 0xFF2B6CB0.toInt() else 0xFF3A3A3C.toInt(), dp(8).toFloat())
    }

    private fun pill(color: Int, radius: Float = dp(18).toFloat()) = GradientDrawable().apply {
        setColor(color); cornerRadius = radius
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** The panel's size in dp, which the Mac takes as points. */
    fun panelSizeDp(): Pair<Double, Double> {
        val d = resources.displayMetrics.density
        return (width / d).toDouble() to (height / d).toDouble()
    }

    fun addSurfaceCallback(cb: SurfaceHolder.Callback) = surface.holder.addCallback(cb)
}
