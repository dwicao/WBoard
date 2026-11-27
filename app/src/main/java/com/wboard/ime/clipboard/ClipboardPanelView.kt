package com.wboard.ime.clipboard

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.wboard.ime.R
import com.wboard.ime.ui.KeyboardTheme

/**
 * Lightweight, themed clipboard panel rendered inside the keyboard frame at the exact same
 * height as WBoardKeyboardView so opening/closing clipboard causes zero layout jump.
 */
class ClipboardPanelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    interface Listener {
        fun onClipSelected(text: String)
        fun onClipDelete(text: String)
        fun onClipboardClearAll()
        fun onClipboardClose()
    }

    var listener: Listener? = null

    private var theme: KeyboardTheme = KeyboardTheme.LIGHT
    private var targetHeightPx: Int = 0
    private var clips: List<String> = emptyList()

    private val density = resources.displayMetrics.density
    private val titleView: TextView
    private val clearButton: Button
    private val closeButton: Button
    private val listContainer: LinearLayout
    private val emptyView: TextView

    init {
        orientation = VERTICAL
        val pad = (8 * density).toInt()
        setPadding(pad, (6 * density).toInt(), pad, pad)

        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((4 * density).toInt(), 0, (4 * density).toInt(), (6 * density).toInt())
        }

        titleView = TextView(context).apply {
            text = context.getString(R.string.clipboard_title)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTypeface(null, Typeface.BOLD)
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }

        clearButton = Button(context).apply {
            text = context.getString(R.string.clipboard_clear)
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            minHeight = 0
            minimumHeight = 0
            setPadding((10 * density).toInt(), (4 * density).toInt(), (10 * density).toInt(), (4 * density).toInt())
            setOnClickListener { listener?.onClipboardClearAll() }
        }

        closeButton = Button(context).apply {
            text = "ABC"
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(null, Typeface.BOLD)
            minHeight = 0
            minimumHeight = 0
            setPadding((12 * density).toInt(), (4 * density).toInt(), (12 * density).toInt(), (4 * density).toInt())
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginStart = (6 * density).toInt()
            }
            setOnClickListener { listener?.onClipboardClose() }
        }

        header.addView(titleView)
        header.addView(clearButton)
        header.addView(closeButton)
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        listContainer = LinearLayout(context).apply {
            orientation = VERTICAL
        }

        emptyView = TextView(context).apply {
            text = context.getString(R.string.clipboard_empty)
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, (24 * density).toInt(), 0, (24 * density).toInt())
        }

        val scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = true
            addView(
                listContainer,
                ViewGroup.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            )
        }
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }

    fun setTargetHeightPx(heightPx: Int) {
        if (heightPx > 0 && targetHeightPx != heightPx) {
            targetHeightPx = heightPx
            requestLayout()
        }
    }

    fun applyTheme(newTheme: KeyboardTheme) {
        theme = newTheme
        setBackgroundColor(theme.background)
        titleView.setTextColor(theme.textPrimary)

        clearButton.setTextColor(theme.textSecondary)
        clearButton.background = roundedBg(theme.specialKeyBg, 8f)

        closeButton.setTextColor(theme.accentText)
        closeButton.background = roundedBg(theme.accentBg, 8f)

        emptyView.setTextColor(theme.textSecondary)
        rebuildList()
    }

    fun setClips(items: List<String>) {
        clips = items
        rebuildList()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val hSpec = if (targetHeightPx > 0) {
            MeasureSpec.makeMeasureSpec(targetHeightPx, MeasureSpec.EXACTLY)
        } else {
            heightMeasureSpec
        }
        super.onMeasure(widthMeasureSpec, hSpec)
    }

    private fun rebuildList() {
        listContainer.removeAllViews()
        if (clips.isEmpty()) {
            listContainer.addView(emptyView)
            return
        }

        val itemPadH = (10 * density).toInt()
        val itemPadV = (8 * density).toInt()
        val gap = (6 * density).toInt()

        for (clip in clips) {
            val row = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = roundedBg(theme.keyBg, 9f)
                setPadding(itemPadH, itemPadV, (6 * density).toInt(), itemPadV)
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = gap
                }
            }

            val text = TextView(context).apply {
                this.text = clip.replace('\n', ' ')
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(theme.textPrimary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { listener?.onClipSelected(clip) }
            }

            val del = TextView(context).apply {
                this.text = "✕"
                gravity = Gravity.CENTER
                setTextColor(theme.textSecondary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding((10 * density).toInt(), (4 * density).toInt(), (8 * density).toInt(), (4 * density).toInt())
                setOnClickListener { listener?.onClipDelete(clip) }
            }

            row.setOnClickListener { listener?.onClipSelected(clip) }
            row.addView(text)
            row.addView(del)
            listContainer.addView(row)
        }
    }

    private fun roundedBg(color: Int, radiusDp: Float): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusDp * density
            setColor(color)
        }
}
