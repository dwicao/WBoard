package com.wboard.ime.ime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.wboard.ime.ui.KeyboardTheme
import kotlin.math.abs

/**
 * Lightweight Canvas-drawn keyboard view with:
 * - Zero child View inflation overhead
 * - Spacebar horizontal swipe cursor movement
 * - Hold-to-repeat backspace (escalating to word deletion)
 * - Long-press inline option popup (numbers on top row, diacritics, punctuation on '.')
 * - Optional key press preview popup
 */
class WBoardKeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    interface Listener {
        fun onKeyInput(code: Int, text: String?)
        fun onDeleteWord()
        fun onMoveCursor(deltaChars: Int)
    }

    private data class KeyBox(
        val spec: KeySpec,
        val rect: RectF
    )

    var listener: Listener? = null

    private var theme: KeyboardTheme = KeyboardTheme.LIGHT
    private var mode: KeyboardMode = KeyboardMode.ALPHA
    private var shiftState: ShiftState = ShiftState.OFF
    private var showNumberRow: Boolean = false
    private var heightScale: Float = 1.0f
    private var keyPopupEnabled: Boolean = true
    private var hapticsEnabled: Boolean = true
    private var enterLabel: String = "↵"

    private val density = resources.displayMetrics.density
    private val keyBoxes = ArrayList<KeyBox>(48)

    private var activePointerId: Int = MotionEvent.INVALID_POINTER_ID
    private var pressedKey: KeyBox? = null
    private var spaceSwipeActive: Boolean = false
    private var spaceSwipeAnchorX: Float = 0f
    private val spaceStepPx = 14f * density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    // Long-press popup state
    private var longPressKey: KeyBox? = null
    private var longPressOptionRects: List<RectF> = emptyList()
    private var selectedLongPressIndex: Int = 0

    // Backspace repeat state
    private var backspaceHeldMillis: Long = 0L
    private val handler = Handler(Looper.getMainLooper())

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.RIGHT
        textSize = 9.5f * density
    }
    private val popupPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        setShadowLayer(6f * density, 0f, 2f * density, 0x33000000)
    }

    private val tempRect = RectF()

    private val longPressRunnable = Runnable {
        val key = pressedKey ?: return@Runnable
        if (key.spec.code == KeyCode.BACKSPACE) return@Runnable
        if (key.spec.longPressOptions.isNotEmpty()) {
            longPressKey = key
            selectedLongPressIndex = 0
            buildLongPressPopupRects(key)
            performHapticIfEnabled()
            invalidate()
        }
    }

    private val backspaceRepeatRunnable = object : Runnable {
        override fun run() {
            val key = pressedKey
            if (key == null || key.spec.code != KeyCode.BACKSPACE) return
            backspaceHeldMillis += REPEAT_INTERVAL_MS
            if (backspaceHeldMillis >= WORD_DELETE_THRESHOLD_MS) {
                listener?.onDeleteWord()
            } else {
                listener?.onKeyInput(KeyCode.BACKSPACE, null)
            }
            handler.postDelayed(this, REPEAT_INTERVAL_MS)
        }
    }

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    fun applyTheme(newTheme: KeyboardTheme) {
        theme = newTheme
        invalidate()
    }

    fun configure(
        mode: KeyboardMode,
        shiftState: ShiftState,
        showNumberRow: Boolean,
        heightScale: Float,
        keyPopupEnabled: Boolean,
        hapticsEnabled: Boolean,
        enterLabel: String
    ) {
        val heightChanged = this.showNumberRow != showNumberRow || this.heightScale != heightScale
        val modeChanged = this.mode != mode
        this.mode = mode
        this.shiftState = shiftState
        this.showNumberRow = showNumberRow
        this.heightScale = heightScale
        this.keyPopupEnabled = keyPopupEnabled
        this.hapticsEnabled = hapticsEnabled
        this.enterLabel = enterLabel

        if (heightChanged) {
            requestLayout()
        }
        if (modeChanged || heightChanged) {
            layoutKeys(width, height)
        }
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val totalH = computeKeyboardHeightPx()
        setMeasuredDimension(w, totalH)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layoutKeys(w, h)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        layoutKeys(right - left, bottom - top)
    }

    private fun computeKeyboardHeightPx(): Int {
        // Keep keyboard height 100% constant across ALPHA, SYMBOLS, MORE_SYMBOLS, and NUMERIC
        // so switching layouts never causes height jumps or inconsistent key sizes.
        val rowCount = if (showNumberRow) 5 else 4
        val rowH = if (showNumberRow) 46f * density * heightScale else 52f * density * heightScale
        val rowGap = 6f * density
        val padVertical = 14f * density
        return (rowCount * rowH + (rowCount - 1) * rowGap + padVertical).toInt()
    }

    private fun layoutKeys(totalW: Int, totalH: Int) {
        keyBoxes.clear()
        if (totalW <= 0 || totalH <= 0) return

        val rows = KeyboardLayouts.rowsFor(mode, showNumberRow)
        val padH = 4f * density
        val padTop = 6f * density
        val padBottom = 8f * density
        val rowGap = 6f * density
        val keyGap = 4.5f * density

        val availH = totalH - padTop - padBottom - rowGap * (rows.size - 1)
        val rowH = availH / rows.size

        for (rIndex in rows.indices) {
            val row = rows[rIndex]
            val totalWeight = row.sumOf { it.weight.toDouble() }.toFloat()

            // Center narrower rows like ASDFGHJKL (9 keys) naturally on alpha layout
            val isNineKeyAlphaRow = mode == KeyboardMode.ALPHA &&
                ((showNumberRow && rIndex == 2) || (!showNumberRow && rIndex == 1))
            val sideInset = if (isNineKeyAlphaRow) totalW * 0.048f else 0f

            val rowLeft = padH + sideInset
            val rowAvailW = (totalW - padH * 2f - sideInset * 2f) - keyGap * (row.size - 1)
            val top = padTop + rIndex * (rowH + rowGap)
            val bottom = top + rowH

            var cursorX = rowLeft
            for (spec in row) {
                val kw = rowAvailW * (spec.weight / totalWeight)
                val rect = RectF(cursorX, top, cursorX + kw, bottom)
                keyBoxes.add(KeyBox(spec, rect))
                cursorX += kw + keyGap
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        bgPaint.color = theme.background
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        val cornerRadius = 7.5f * density
        for (box in keyBoxes) {
            val isPressed = box == pressedKey && !spaceSwipeActive
            keyPaint.color = when {
                box.spec.isAccent -> if (isPressed) theme.keyPressedBg else theme.accentBg
                isPressed -> theme.keyPressedBg
                box.spec.code == KeyCode.SHIFT && shiftState == ShiftState.CAPS_LOCK -> theme.chipHighlightBg
                box.spec.isSpecial -> theme.specialKeyBg
                else -> theme.keyBg
            }
            canvas.drawRoundRect(box.rect, cornerRadius, cornerRadius, keyPaint)

            // Key label
            val label = resolveLabel(box.spec)
            val isSmallText = label.length > 2
            labelPaint.textSize = when {
                box.spec.code == KeyCode.SPACE -> 12f * density
                isSmallText -> 13f * density
                else -> 19.5f * density
            }
            labelPaint.typeface = if (box.spec.isAccent || (box.spec.code == KeyCode.SHIFT && shiftState != ShiftState.OFF)) {
                Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            } else {
                Typeface.DEFAULT
            }
            labelPaint.color = when {
                box.spec.isAccent -> theme.accentText
                box.spec.code == KeyCode.SPACE -> theme.textSecondary
                box.spec.code == KeyCode.SHIFT && shiftState != ShiftState.OFF -> theme.accentBg
                else -> theme.textPrimary
            }
            val ly = box.rect.centerY() - (labelPaint.descent() + labelPaint.ascent()) / 2f
            canvas.drawText(label, box.rect.centerX(), ly, labelPaint)

            // Secondary hint in top-right corner
            val hint = box.spec.hint
            if (hint != null && !(mode == KeyboardMode.ALPHA && showNumberRow && hint[0].isDigit())) {
                hintPaint.color = theme.textSecondary
                canvas.drawText(
                    hint,
                    box.rect.right - 4.5f * density,
                    box.rect.top + 11f * density,
                    hintPaint
                )
            }
        }

        // Draw key press preview popup or long-press options popup
        val lpKey = longPressKey
        if (lpKey != null && longPressOptionRects.isNotEmpty()) {
            drawLongPressPopup(canvas, lpKey)
        } else if (keyPopupEnabled && !spaceSwipeActive) {
            val pk = pressedKey
            if (pk != null && !pk.spec.isSpecial && pk.spec.code != KeyCode.SPACE) {
                drawKeyPreviewPopup(canvas, pk)
            }
        }
    }

    private fun drawKeyPreviewPopup(canvas: Canvas, box: KeyBox) {
        val popupH = box.rect.height() * 1.05f
        val popupW = (box.rect.width() * 1.28f).coerceAtLeast(38f * density)
        val cx = box.rect.centerX().coerceIn(popupW / 2f + 4f * density, width - popupW / 2f - 4f * density)
        val bottom = box.rect.top - 4f * density
        val top = (bottom - popupH).coerceAtLeast(2f * density)
        tempRect.set(cx - popupW / 2f, top, cx + popupW / 2f, top + popupH)

        popupPaint.color = theme.popupBg
        canvas.drawRoundRect(tempRect, 9f * density, 9f * density, popupPaint)

        labelPaint.textSize = 23f * density
        labelPaint.color = theme.textPrimary
        labelPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        val ty = tempRect.centerY() - (labelPaint.descent() + labelPaint.ascent()) / 2f
        canvas.drawText(resolveLabel(box.spec), tempRect.centerX(), ty, labelPaint)
    }

    private fun buildLongPressPopupRects(box: KeyBox) {
        val options = box.spec.longPressOptions
        if (options.isEmpty()) {
            longPressOptionRects = emptyList()
            return
        }
        val optW = 36f * density
        val optH = 42f * density
        val totalW = optW * options.size
        val startX = (box.rect.centerX() - totalW / 2f)
            .coerceIn(6f * density, (width - totalW - 6f * density).coerceAtLeast(6f * density))
        val bottom = box.rect.top - 4f * density
        val top = (bottom - optH).coerceAtLeast(2f * density)

        val rects = ArrayList<RectF>(options.size)
        for (i in options.indices) {
            rects.add(RectF(startX + i * optW, top, startX + (i + 1) * optW, top + optH))
        }
        longPressOptionRects = rects
    }

    private fun drawLongPressPopup(canvas: Canvas, box: KeyBox) {
        val options = box.spec.longPressOptions
        if (longPressOptionRects.isEmpty() || options.isEmpty()) return
        val container = RectF(
            longPressOptionRects.first().left - 3f * density,
            longPressOptionRects.first().top - 3f * density,
            longPressOptionRects.last().right + 3f * density,
            longPressOptionRects.last().bottom + 3f * density
        )
        popupPaint.color = theme.popupBg
        canvas.drawRoundRect(container, 10f * density, 10f * density, popupPaint)

        for (i in options.indices) {
            val r = longPressOptionRects[i]
            if (i == selectedLongPressIndex) {
                keyPaint.color = theme.accentBg
                canvas.drawRoundRect(r, 7f * density, 7f * density, keyPaint)
                labelPaint.color = theme.accentText
            } else {
                labelPaint.color = theme.textPrimary
            }
            labelPaint.textSize = 18f * density
            labelPaint.typeface = Typeface.DEFAULT
            val ty = r.centerY() - (labelPaint.descent() + labelPaint.ascent()) / 2f
            canvas.drawText(options[i], r.centerX(), ty, labelPaint)
        }
    }

    private fun resolveLabel(spec: KeySpec): String {
        return when (spec.code) {
            KeyCode.ENTER -> enterLabel
            KeyCode.SHIFT -> when (shiftState) {
                ShiftState.OFF -> "⇧"
                ShiftState.ONCE -> "⬆"
                ShiftState.CAPS_LOCK -> "⇪"
            }
            else -> {
                if (spec.code > 0 && spec.label.length == 1 && spec.label[0].isLetter() &&
                    shiftState != ShiftState.OFF
                ) {
                    spec.label.uppercase()
                } else {
                    spec.label
                }
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val action = event.actionMasked
        val index = event.actionIndex

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                activePointerId = event.getPointerId(0)
                handlePressDown(event.x, event.y)
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                // Fast multi-finger typing: commit previous pressed key immediately and track new finger
                if (pressedKey != null && !spaceSwipeActive && longPressKey == null) {
                    commitKey(pressedKey!!)
                }
                cancelPendingTimers()
                activePointerId = event.getPointerId(index)
                handlePressDown(event.getX(index), event.getY(index))
            }

            MotionEvent.ACTION_MOVE -> {
                val pIdx = event.findPointerIndex(activePointerId)
                if (pIdx < 0) return true
                val x = event.getX(pIdx)
                val y = event.getY(pIdx)

                if (longPressKey != null && longPressOptionRects.isNotEmpty()) {
                    var bestIdx = 0
                    var bestDist = Float.MAX_VALUE
                    for (i in longPressOptionRects.indices) {
                        val d = abs(x - longPressOptionRects[i].centerX())
                        if (d < bestDist) {
                            bestDist = d
                            bestIdx = i
                        }
                    }
                    if (bestIdx != selectedLongPressIndex) {
                        selectedLongPressIndex = bestIdx
                        invalidate()
                    }
                    return true
                }

                val cur = pressedKey
                if (cur != null && cur.spec.code == KeyCode.SPACE) {
                    val dx = x - spaceSwipeAnchorX
                    if (!spaceSwipeActive && abs(dx) > touchSlop) {
                        spaceSwipeActive = true
                        spaceSwipeAnchorX = x
                        invalidate()
                    } else if (spaceSwipeActive && abs(dx) >= spaceStepPx) {
                        val steps = (dx / spaceStepPx).toInt()
                        if (steps != 0) {
                            spaceSwipeAnchorX += steps * spaceStepPx
                            listener?.onMoveCursor(steps)
                        }
                    }
                    return true
                }

                val underFinger = findKeyAt(x, y)
                if (underFinger != pressedKey && pressedKey?.spec?.code != KeyCode.BACKSPACE) {
                    cancelPendingTimers()
                    pressedKey = underFinger
                    if (underFinger != null && underFinger.spec.longPressOptions.isNotEmpty()) {
                        handler.postDelayed(longPressRunnable, LONG_PRESS_DELAY_MS)
                    }
                    invalidate()
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val pid = event.getPointerId(index)
                if (pid != activePointerId) return true
                handleRelease()
            }

            MotionEvent.ACTION_CANCEL -> {
                cancelPendingTimers()
                pressedKey = null
                longPressKey = null
                longPressOptionRects = emptyList()
                spaceSwipeActive = false
                activePointerId = MotionEvent.INVALID_POINTER_ID
                invalidate()
            }
        }
        return true
    }

    private fun handlePressDown(x: Float, y: Float) {
        cancelPendingTimers()
        longPressKey = null
        longPressOptionRects = emptyList()
        spaceSwipeActive = false
        backspaceHeldMillis = 0L

        val hit = findKeyAt(x, y)
        pressedKey = hit
        if (hit != null) {
            performHapticIfEnabled()
            if (hit.spec.code == KeyCode.SPACE) {
                spaceSwipeAnchorX = x
            } else if (hit.spec.code == KeyCode.BACKSPACE) {
                handler.postDelayed(backspaceRepeatRunnable, INITIAL_REPEAT_DELAY_MS)
            } else if (hit.spec.longPressOptions.isNotEmpty()) {
                handler.postDelayed(longPressRunnable, LONG_PRESS_DELAY_MS)
            }
        }
        invalidate()
    }

    private fun handleRelease() {
        cancelPendingTimers()
        val lpKey = longPressKey
        if (lpKey != null && lpKey.spec.longPressOptions.isNotEmpty()) {
            val chosen = lpKey.spec.longPressOptions.getOrNull(selectedLongPressIndex)
            if (chosen != null) {
                listener?.onKeyInput(chosen[0].code, chosen)
            }
        } else if (!spaceSwipeActive) {
            val key = pressedKey
            if (key != null && !(key.spec.code == KeyCode.BACKSPACE && backspaceHeldMillis > 0L)) {
                commitKey(key)
            }
        }
        pressedKey = null
        longPressKey = null
        longPressOptionRects = emptyList()
        spaceSwipeActive = false
        activePointerId = MotionEvent.INVALID_POINTER_ID
        invalidate()
    }

    private fun commitKey(box: KeyBox) {
        val spec = box.spec
        if (spec.code < 0) {
            listener?.onKeyInput(spec.code, null)
        } else {
            val out = resolveLabel(spec)
            listener?.onKeyInput(out[0].code, out)
        }
    }

    private fun findKeyAt(x: Float, y: Float): KeyBox? {
        for (box in keyBoxes) {
            if (box.rect.contains(x, y)) return box
        }
        // Nearest key within generous hit margin near edges
        var nearest: KeyBox? = null
        var bestDist = Float.MAX_VALUE
        for (box in keyBoxes) {
            val dx = when {
                x < box.rect.left -> box.rect.left - x
                x > box.rect.right -> x - box.rect.right
                else -> 0f
            }
            val dy = when {
                y < box.rect.top -> box.rect.top - y
                y > box.rect.bottom -> y - box.rect.bottom
                else -> 0f
            }
            val dist = dx * dx + dy * dy
            if (dist < bestDist) {
                bestDist = dist
                nearest = box
            }
        }
        return if (bestDist <= (18f * density) * (18f * density)) nearest else null
    }

    private fun cancelPendingTimers() {
        handler.removeCallbacks(longPressRunnable)
        handler.removeCallbacks(backspaceRepeatRunnable)
    }

    private fun performHapticIfEnabled() {
        if (hapticsEnabled) {
            performHapticFeedback(
                HapticFeedbackConstants.KEYBOARD_TAP,
                HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING
            )
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cancelPendingTimers()
    }

    companion object {
        private const val LONG_PRESS_DELAY_MS = 380L
        private const val INITIAL_REPEAT_DELAY_MS = 360L
        private const val REPEAT_INTERVAL_MS = 55L
        private const val WORD_DELETE_THRESHOLD_MS = 1600L
    }
}
