package com.hermesandroid.bridge.overlay

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import kotlin.math.roundToInt

object HermesDisplaySettings {

    private const val PREFS = "hermes_overlay_prefs"

    const val KEY_TEXT_SCALE = "text_scale"
    const val KEY_LINE_SPACING = "line_spacing"
    const val KEY_LETTER_SPACING = "letter_spacing"
    const val KEY_ELEMENT_SPACING = "element_spacing"

    const val DEFAULT_TEXT_SCALE = 1.0f
    const val DEFAULT_LINE_SPACING = 0.20f
    const val DEFAULT_LETTER_SPACING = 0.0f
    const val DEFAULT_ELEMENT_SPACING = 2

    fun show(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                dp(context, 18),
                dp(context, 4),
                dp(context, 18),
                dp(context, 4)
            )
        }

        addSlider(
            context,
            root,
            "文字大小",
            prefs.getFloat(KEY_TEXT_SCALE, DEFAULT_TEXT_SCALE),
            80,
            140,
            100,
            "%"
        ) { value ->
            prefs.edit()
                .putFloat(KEY_TEXT_SCALE, value / 100f)
                .apply()
            refreshOverlay(context)
        }

        addSlider(
            context,
            root,
            "行距",
            prefs.getFloat(KEY_LINE_SPACING, DEFAULT_LINE_SPACING),
            0,
            80,
            20,
            "%"
        ) { value ->
            prefs.edit()
                .putFloat(KEY_LINE_SPACING, value / 100f)
                .apply()
            refreshOverlay(context)
        }

        addSlider(
            context,
            root,
            "字间距",
            prefs.getFloat(KEY_LETTER_SPACING, DEFAULT_LETTER_SPACING),
            -3,
            8,
            0,
            "%"
        ) { value ->
            prefs.edit()
                .putFloat(KEY_LETTER_SPACING, value / 100f)
                .apply()
            refreshOverlay(context)
        }

        addSlider(
            context,
            root,
            "元素间距",
            prefs.getInt(KEY_ELEMENT_SPACING, DEFAULT_ELEMENT_SPACING).toFloat(),
            0,
            12,
            DEFAULT_ELEMENT_SPACING,
            "dp"
        ) { value ->
            prefs.edit()
                .putInt(KEY_ELEMENT_SPACING, value)
                .apply()
            refreshOverlay(context)
        }

        AlertDialog.Builder(context)
            .setTitle("Hermes 显示设置")
            .setView(root)
            .setNegativeButton("关闭", null)
            .setNeutralButton("恢复默认") { _, _ ->
                prefs.edit()
                    .putFloat(KEY_TEXT_SCALE, DEFAULT_TEXT_SCALE)
                    .putFloat(KEY_LINE_SPACING, DEFAULT_LINE_SPACING)
                    .putFloat(KEY_LETTER_SPACING, DEFAULT_LETTER_SPACING)
                    .putInt(KEY_ELEMENT_SPACING, DEFAULT_ELEMENT_SPACING)
                    .apply()

                refreshOverlay(context)
            }
            .show()
    }

    private fun addSlider(
        context: Context,
        root: LinearLayout,
        title: String,
        stored: Float,
        min: Int,
        max: Int,
        defaultValue: Int,
        suffix: String,
        onChanged: (Int) -> Unit,
    ) {
        val initial = when (suffix) {
            "%" -> (stored * 100f).roundToInt()
            "dp" -> stored.roundToInt()
            else -> defaultValue
        }.coerceIn(min, max)

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(context, 6), 0, dp(context, 6))
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val label = TextView(context).apply {
            text = title
            textSize = 15f
            setTextColor(Color.WHITE)
        }

        val valueText = TextView(context).apply {
            text = formatValue(initial, suffix)
            textSize = 13f
            setTextColor(Color.rgb(170, 174, 184))
        }

        header.addView(
            label,
            LinearLayout.LayoutParams(0, -2, 1f)
        )
        header.addView(valueText)

        val bar = SeekBar(context).apply {
            this.max = max - min
            progress = initial - min

            setOnSeekBarChangeListener(
                object : SeekBar.OnSeekBarChangeListener {

                    override fun onProgressChanged(
                        seekBar: SeekBar?,
                        progress: Int,
                        fromUser: Boolean
                    ) {
                        val value = progress + min
                        valueText.text = formatValue(value, suffix)

                        if (fromUser) {
                            onChanged(value)
                        }
                    }

                    override fun onStartTrackingTouch(
                        seekBar: SeekBar?
                    ) = Unit

                    override fun onStopTrackingTouch(
                        seekBar: SeekBar?
                    ) = Unit
                }
            )
        }

        row.addView(header)
        row.addView(bar)

        root.addView(row)
    }

    private fun formatValue(
        value: Int,
        suffix: String
    ): String {
        return "$value$suffix"
    }

    private fun refreshOverlay(context: Context) {
        HermesPopupOverlay.refresh(context)
    }

    private fun dp(
        context: Context,
        value: Int
    ): Int {
        return (
            value * context.resources.displayMetrics.density
        ).roundToInt()
    }
}
