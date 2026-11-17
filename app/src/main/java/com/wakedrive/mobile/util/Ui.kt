package com.wakedrive.mobile.util

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import com.wakedrive.mobile.R

/** Small helpers for the programmatic screens, styled like the web console. */
object Ui {
    fun dp(context: Context, value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    fun text(context: Context, content: String, sizeSp: Float = 13f, colorRes: Int = R.color.text_primary, bold: Boolean = false): TextView {
        return TextView(context).apply {
            text = content
            textSize = sizeSp
            setTextColor(ContextCompat.getColor(context, colorRes))
            setLineSpacing(dp(context, 2).toFloat(), 1f)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(context, 2), 0, dp(context, 2))
        }
    }

    fun button(context: Context, label: String, primary: Boolean = false, danger: Boolean = false): Button {
        return Button(context).apply {
            text = label
            textSize = 13.5f
            isAllCaps = false
            letterSpacing = 0f
            stateListAnimator = null
            minHeight = dp(context, 46)
            setPadding(dp(context, 16), dp(context, 10), dp(context, 16), dp(context, 10))
            when {
                danger -> {
                    background = AppCompatResources.getDrawable(context, R.drawable.bg_button_danger)
                    setTextColor(Color.parseColor("#FFCAD2"))
                }
                primary -> {
                    background = AppCompatResources.getDrawable(context, R.drawable.bg_button_primary)
                    setTextColor(Color.parseColor("#04131B"))
                    setTypeface(typeface, Typeface.BOLD)
                }
                else -> {
                    background = AppCompatResources.getDrawable(context, R.drawable.bg_button_secondary)
                    setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                }
            }
        }
    }

    fun chip(context: Context, label: String, colorRes: Int): TextView {
        val color = ContextCompat.getColor(context, colorRes)
        return TextView(context).apply {
            text = label
            textSize = 11f
            setTextColor(color)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(context, 10), dp(context, 4), dp(context, 10), dp(context, 4))
            background = GradientDrawable().apply {
                cornerRadius = dp(context, 999).toFloat()
                setColor(Color.argb(40, Color.red(color), Color.green(color), Color.blue(color)))
                setStroke(dp(context, 1), Color.argb(120, Color.red(color), Color.green(color), Color.blue(color)))
            }
        }
    }

    fun card(context: Context, paddingDp: Int = 16): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, paddingDp), dp(context, paddingDp), dp(context, paddingDp), dp(context, paddingDp))
            background = AppCompatResources.getDrawable(context, R.drawable.bg_card)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(context, 12)
            }
        }
    }

    fun sectionTitle(context: Context, label: String): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val marker = View(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(context, 4), dp(context, 16)).apply { marginEnd = dp(context, 8) }
                setBackgroundColor(ContextCompat.getColor(context, R.color.cyan))
            }
            addView(marker)
            addView(text(context, label, 15f, R.color.text_primary, true))
        }
    }

    fun spacer(context: Context, heightDp: Int): View {
        return View(context).apply {
            layoutParams = LinearLayout.LayoutParams(1, dp(context, heightDp))
        }
    }

    fun scrollContainer(context: Context): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 16), dp(context, 14), dp(context, 16), dp(context, 20))
        }
    }

    fun row(context: Context): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
    }
}
