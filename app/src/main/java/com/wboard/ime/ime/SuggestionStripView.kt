package com.wboard.ime.ime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.wboard.ime.R
import com.wboard.ime.prediction.IndonesianPredictionEngine.Suggestion
import com.wboard.ime.ui.KeyboardTheme
import kotlin.math.max

/**
 * Minimalist top strip hosting:
 * - Up to 3 Indonesian prediction chips
 * - Privacy shield indicator on password fields
 * - Voice mic button + inline Indonesian voice waveform & partial transcript overlay
 * - Quick settings gear button
 */
class SuggestionStripView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    interface Listener {
        fun onSuggestionClicked(suggestion: Suggestion)
        fun onClipboardClicked()
        fun onVoiceClicked()
        fun onVoiceCancelClicked()
        fun onSettingsClicked()
    }

    var listener: Listener? = null

    private var theme: KeyboardTheme = KeyboardTheme.LIGHT
    private var suggestions: List<Suggestion> = emptyList()
    private var isPasswordMode: Boolean = false
    private var showMicButton: Boolean = true
    private var showClipboardButton: Boolean = true
    private var clipboardActive: Boolean = false

    // Voice inline state
    private var voiceActive: Boolean = false
    private var voiceOnDevice: Boolean = false
    private var voicePartialText: String = ""
    private var voiceStatusText: String = ""
    private var voiceRmsLevel: Float = 0f

    private val density = resources.displayMetrics.density
    private val stripHeightPx = (42f * density).toInt()

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val chipPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val buttonPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 14.5f * density
    }
    private val subTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
        textSize = 12.5f * density
    }
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 9.5f * density
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val chipRects = ArrayList<RectF>(3)
    private val clipRect = RectF()
    private val micRect = RectF()
    private val settingsRect = RectF()
    private val cancelRect = RectF()
    private val tempRect = RectF()

    fun applyTheme(newTheme: KeyboardTheme) {
        theme = newTheme
        invalidate()
    }

    fun setSuggestions(list: List<Suggestion>) {
        suggestions = list
        invalidate()
    }

    fun setPolicyState(passwordMode: Boolean, allowMic: Boolean, allowClipboard: Boolean) {
        isPasswordMode = passwordMode
        showMicButton = allowMic
        showClipboardButton = allowClipboard
        invalidate()
    }

    fun setClipboardActive(active: Boolean) {
        clipboardActive = active
        invalidate()
    }

    fun setVoiceState(
        active: Boolean,
        onDevice: Boolean = false,
        partialText: String = "",
        statusText: String = ""
    ) {
        voiceActive = active
        voiceOnDevice = onDevice
        voicePartialText = partialText
        voiceStatusText = statusText
        if (!active) voiceRmsLevel = 0f
        invalidate()
    }

    fun updateVoiceRms(level: Float) {
        voiceRmsLevel = (voiceRmsLevel * 0.45f + level * 0.55f).coerceIn(0f, 1f)
        if (voiceActive) invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, stripHeightPx)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        bgPaint.color = theme.stripBackground
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        dividerPaint.color = theme.divider
        dividerPaint.strokeWidth = max(1f, density)
        canvas.drawLine(0f, h - 1f, w, h - 1f, dividerPaint)

        if (voiceActive) {
            drawVoiceBar(canvas, w, h)
            return
        }

        val actionBtnW = 40f * density
        val pad = 6f * density
        var rightEdge = w - pad

        // Settings button on far right
        settingsRect.set(rightEdge - actionBtnW, pad, rightEdge, h - pad)
        rightEdge -= actionBtnW + 4f * density

        // Mic button next to settings
        if (showMicButton && !isPasswordMode) {
            micRect.set(rightEdge - actionBtnW, pad, rightEdge, h - pad)
            rightEdge -= actionBtnW + 4f * density
        } else {
            micRect.setEmpty()
        }

        // Clipboard button next to mic
        if (showClipboardButton && !isPasswordMode) {
            clipRect.set(rightEdge - actionBtnW, pad, rightEdge, h - pad)
            rightEdge -= actionBtnW + 6f * density
        } else {
            clipRect.setEmpty()
            if (!micRect.isEmpty) rightEdge -= 2f * density
        }

        drawIconButtons(canvas)

        if (isPasswordMode) {
            subTextPaint.color = theme.textSecondary
            subTextPaint.textAlign = Paint.Align.LEFT
            val msg = context.getString(R.string.ime_password_shield)
            val y = h / 2f - (subTextPaint.descent() + subTextPaint.ascent()) / 2f
            canvas.drawText("🔒  $msg", 12f * density, y, subTextPaint)
            chipRects.clear()
            return
        }

        // Prediction chips area
        chipRects.clear()
        val areaLeft = 6f * density
        val areaWidth = (rightEdge - areaLeft).coerceAtLeast(0f)
        if (suggestions.isNotEmpty() && areaWidth > 20f) {
            val count = suggestions.size.coerceAtMost(3)
            val gap = 4f * density
            val chipW = (areaWidth - gap * (count - 1)) / count
            for (i in 0 until count) {
                val left = areaLeft + i * (chipW + gap)
                val rect = RectF(left, 5f * density, left + chipW, h - 5f * density)
                chipRects.add(rect)

                val item = suggestions[i]
                if (item.isCorrection || (i == 0 && item.text.isNotEmpty() && !item.isVerbatim)) {
                    chipPaint.color = if (item.isCorrection) theme.chipHighlightBg else theme.keyBg
                    canvas.drawRoundRect(rect, 8f * density, 8f * density, chipPaint)
                }

                textPaint.color = if (item.isCorrection) theme.accentBg else theme.textPrimary
                textPaint.typeface = if (item.isCorrection) {
                    Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                } else {
                    Typeface.DEFAULT
                }
                val textY = rect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f
                val clipped = ellipsize(item.text, rect.width() - 12f * density, textPaint)
                canvas.drawText(clipped, rect.centerX(), textY, textPaint)
            }
        } else if (voiceStatusText.isNotEmpty()) {
            subTextPaint.color = theme.textSecondary
            subTextPaint.textAlign = Paint.Align.LEFT
            val y = h / 2f - (subTextPaint.descent() + subTextPaint.ascent()) / 2f
            canvas.drawText(voiceStatusText, 12f * density, y, subTextPaint)
        }
    }

    private fun drawIconButtons(canvas: Canvas) {
        val r = 8f * density
        if (!clipRect.isEmpty) {
            buttonPaint.color = if (clipboardActive) theme.chipHighlightBg else theme.keyBg
            canvas.drawRoundRect(clipRect, r, r, buttonPaint)
            drawClipboardGlyph(
                canvas,
                clipRect.centerX(),
                clipRect.centerY(),
                if (clipboardActive) theme.accentBg else theme.textSecondary
            )
        }
        // Mic icon (drawn cleanly with geometric shapes)
        if (!micRect.isEmpty) {
            buttonPaint.color = theme.keyBg
            canvas.drawRoundRect(micRect, r, r, buttonPaint)
            drawMicGlyph(canvas, micRect.centerX(), micRect.centerY(), theme.accentBg)
        }
        // Settings gear / sliders glyph
        buttonPaint.color = theme.specialKeyBg
        canvas.drawRoundRect(settingsRect, r, r, buttonPaint)
        drawSlidersGlyph(canvas, settingsRect.centerX(), settingsRect.centerY(), theme.textSecondary)
    }

    private fun drawClipboardGlyph(canvas: Canvas, cx: Float, cy: Float, color: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = 1.6f * density
        }
        val bw = 5.4f * density
        val bh = 6.8f * density
        tempRect.set(cx - bw, cy - bh + 1.5f * density, cx + bw, cy + bh + 1f * density)
        canvas.drawRoundRect(tempRect, 2f * density, 2f * density, p)

        val clipW = 2.8f * density
        tempRect.set(cx - clipW, cy - bh - 0.5f * density, cx + clipW, cy - bh + 2.5f * density)
        canvas.drawRoundRect(tempRect, 1.2f * density, 1.2f * density, p)
    }

    private fun drawVoiceBar(canvas: Canvas, w: Float, h: Float) {
        chipRects.clear()
        settingsRect.setEmpty()

        val pad = 6f * density
        val btnW = 40f * density
        cancelRect.set(w - pad - btnW, pad, w - pad, h - pad)
        micRect.set(w - pad - btnW * 2 - 4f * density, pad, w - pad - btnW - 4f * density, h - pad)

        // Cancel button
        buttonPaint.color = theme.specialKeyBg
        canvas.drawRoundRect(cancelRect, 8f * density, 8f * density, buttonPaint)
        textPaint.color = theme.textPrimary
        textPaint.typeface = Typeface.DEFAULT
        val cy = cancelRect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText("✕", cancelRect.centerX(), cy, textPaint)

        // Stop/Done mic button
        buttonPaint.color = theme.voiceActiveBg
        canvas.drawRoundRect(micRect, 8f * density, 8f * density, buttonPaint)
        textPaint.color = theme.accentText
        textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        canvas.drawText("✓", micRect.centerX(), cy, textPaint)

        // Badge ("OFFLINE" or "ID")
        val badgeText = if (voiceOnDevice) {
            context.getString(R.string.ime_voice_offline_badge)
        } else {
            "ID"
        }
        val badgeW = if (voiceOnDevice) 46f * density else 26f * density
        tempRect.set(8f * density, 10f * density, 8f * density + badgeW, h - 10f * density)
        chipPaint.color = theme.chipHighlightBg
        canvas.drawRoundRect(tempRect, 6f * density, 6f * density, chipPaint)
        badgePaint.color = theme.accentBg
        val by = tempRect.centerY() - (badgePaint.descent() + badgePaint.ascent()) / 2f
        canvas.drawText(badgeText, tempRect.centerX(), by, badgePaint)

        // Animated RMS bars
        val waveLeft = tempRect.right + 8f * density
        wavePaint.color = theme.accentBg
        val bars = 4
        val barW = 3f * density
        val barGap = 3f * density
        val maxBarH = h - 16f * density
        val minBarH = 5f * density
        val factors = floatArrayOf(0.55f, 1.0f, 0.78f, 0.45f)
        for (i in 0 until bars) {
            val bh = minBarH + (maxBarH - minBarH) * (0.15f + voiceRmsLevel * 0.85f) * factors[i]
            val bx = waveLeft + i * (barW + barGap)
            val top = (h - bh) / 2f
            canvas.drawRoundRect(bx, top, bx + barW, top + bh, barW / 2f, barW / 2f, wavePaint)
        }

        // Partial speech text or prompt
        val textLeft = waveLeft + bars * (barW + barGap) + 8f * density
        val maxTextW = (micRect.left - textLeft - 8f * density).coerceAtLeast(10f)
        val display = if (voicePartialText.isNotEmpty()) {
            voicePartialText
        } else {
            context.getString(R.string.ime_voice_listening)
        }
        subTextPaint.color = theme.textPrimary
        subTextPaint.textAlign = Paint.Align.LEFT
        val ty = h / 2f - (subTextPaint.descent() + subTextPaint.ascent()) / 2f
        canvas.drawText(ellipsize(display, maxTextW, subTextPaint), textLeft, ty, subTextPaint)
    }

    private fun drawMicGlyph(canvas: Canvas, cx: Float, cy: Float, color: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = 1.8f * density
            strokeCap = Paint.Cap.ROUND
        }
        val fillP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.FILL
        }
        val capW = 3.2f * density
        val capH = 5.5f * density
        tempRect.set(cx - capW, cy - capH - 1f * density, cx + capW, cy + 1.5f * density)
        canvas.drawRoundRect(tempRect, capW, capW, fillP)

        val arcR = 5.6f * density
        tempRect.set(cx - arcR, cy - 3.5f * density, cx + arcR, cy + 4.5f * density)
        canvas.drawArc(tempRect, 0f, 180f, false, p)
        canvas.drawLine(cx, cy + 4.5f * density, cx, cy + 7.5f * density, p)
        canvas.drawLine(cx - 3.2f * density, cy + 7.5f * density, cx + 3.2f * density, cy + 7.5f * density, p)
    }

    private fun drawSlidersGlyph(canvas: Canvas, cx: Float, cy: Float, color: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            strokeWidth = 1.7f * density
            strokeCap = Paint.Cap.ROUND
        }
        val halfW = 6.5f * density
        val dy = 4.2f * density
        canvas.drawLine(cx - halfW, cy - dy, cx + halfW, cy - dy, p)
        canvas.drawLine(cx - halfW, cy, cx + halfW, cy, p)
        canvas.drawLine(cx - halfW, cy + dy, cx + halfW, cy + dy, p)
        canvas.drawCircle(cx - 2.2f * density, cy - dy, 1.8f * density, p)
        canvas.drawCircle(cx + 2.5f * density, cy, 1.8f * density, p)
        canvas.drawCircle(cx - 1.5f * density, cy + dy, 1.8f * density, p)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_UP) {
            return event.actionMasked == MotionEvent.ACTION_DOWN
        }
        val x = event.x
        val y = event.y

        if (voiceActive) {
            when {
                cancelRect.contains(x, y) -> listener?.onVoiceCancelClicked()
                micRect.contains(x, y) -> listener?.onVoiceClicked()
            }
            return true
        }

        if (settingsRect.contains(x, y)) {
            listener?.onSettingsClicked()
            return true
        }
        if (!micRect.isEmpty && micRect.contains(x, y)) {
            listener?.onVoiceClicked()
            return true
        }
        if (!clipRect.isEmpty && clipRect.contains(x, y)) {
            listener?.onClipboardClicked()
            return true
        }
        for (i in chipRects.indices) {
            if (chipRects[i].contains(x, y) && i < suggestions.size) {
                listener?.onSuggestionClicked(suggestions[i])
                return true
            }
        }
        return true
    }

    private fun ellipsize(text: String, maxWidth: Float, paint: Paint): String {
        if (paint.measureText(text) <= maxWidth) return text
        val suffix = "…"
        var end = text.length
        while (end > 1 && paint.measureText(text, 0, end) + paint.measureText(suffix) > maxWidth) {
            end--
        }
        return text.substring(0, end) + suffix
    }
}
