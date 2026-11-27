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
 * Full-width minimalist prediction strip.
 * Clipboard, voice mic, and settings are accessed via key long-presses (?123, Space, ',')
 * to give text predictions maximum horizontal space.
 */
class SuggestionStripView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    interface Listener {
        fun onSuggestionClicked(suggestion: Suggestion)
        fun onVoiceClicked()
        fun onVoiceCancelClicked()
    }

    var listener: Listener? = null

    private var theme: KeyboardTheme = KeyboardTheme.LIGHT
    private var suggestions: List<Suggestion> = emptyList()
    private var isPasswordMode: Boolean = false

    // Voice inline state (active when user holds Spacebar)
    private var voiceActive: Boolean = false
    private var voiceOnDevice: Boolean = false
    private var voicePartialText: String = ""
    private var voiceStatusText: String = ""
    private var voiceRmsLevel: Float = 0f

    private val density = resources.displayMetrics.density
    private val stripHeightPx = (42f * density).toInt()
    private val boldTypeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    private val normalTypeface = Typeface.DEFAULT

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
        typeface = boldTypeface
    }
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val chipRects = ArrayList<RectF>(3)
    private val micRect = RectF()
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

    fun setPolicyState(passwordMode: Boolean) {
        isPasswordMode = passwordMode
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

        if (isPasswordMode) {
            subTextPaint.color = theme.textSecondary
            subTextPaint.textAlign = Paint.Align.LEFT
            val msg = context.getString(R.string.ime_password_shield)
            val y = h / 2f - (subTextPaint.descent() + subTextPaint.ascent()) / 2f
            canvas.drawText("🔒  $msg", 12f * density, y, subTextPaint)
            chipRects.clear()
            return
        }

        if (voiceStatusText.isNotEmpty()) {
            chipRects.clear()
            subTextPaint.color = theme.textSecondary
            subTextPaint.textAlign = Paint.Align.LEFT
            val y = h / 2f - (subTextPaint.descent() + subTextPaint.ascent()) / 2f
            canvas.drawText(ellipsize(voiceStatusText, w - 24f * density, subTextPaint), 12f * density, y, subTextPaint)
            return
        }

        // Full-width prediction chips area
        chipRects.clear()
        val areaLeft = 6f * density
        val areaRight = w - 6f * density
        val areaWidth = (areaRight - areaLeft).coerceAtLeast(0f)
        if (suggestions.isNotEmpty() && areaWidth > 20f) {
            val count = suggestions.size.coerceAtMost(3)
            val gap = 5f * density
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
                textPaint.typeface = if (item.isCorrection) boldTypeface else normalTypeface
                val textY = rect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f
                val clipped = ellipsize(item.text, rect.width() - 12f * density, textPaint)
                canvas.drawText(clipped, rect.centerX(), textY, textPaint)
            }
        }
    }

    private fun drawVoiceBar(canvas: Canvas, w: Float, h: Float) {
        chipRects.clear()

        val pad = 6f * density
        val btnW = 40f * density
        cancelRect.set(w - pad - btnW, pad, w - pad, h - pad)
        micRect.set(w - pad - btnW * 2 - 4f * density, pad, w - pad - btnW - 4f * density, h - pad)

        // Cancel button
        buttonPaint.color = theme.specialKeyBg
        canvas.drawRoundRect(cancelRect, 8f * density, 8f * density, buttonPaint)
        textPaint.color = theme.textPrimary
        textPaint.typeface = normalTypeface
        val cy = cancelRect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText("✕", cancelRect.centerX(), cy, textPaint)

        // Stop/Done mic button
        buttonPaint.color = theme.voiceActiveBg
        canvas.drawRoundRect(micRect, 8f * density, 8f * density, buttonPaint)
        textPaint.color = theme.accentText
        textPaint.typeface = boldTypeface
        canvas.drawText("✓", micRect.centerX(), cy, textPaint)

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
