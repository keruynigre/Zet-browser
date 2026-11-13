package dev.zet.browser

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

/** Brave tarzı ⋮ menüsü: ikonlu satırlar, altta hızlı eylem düğmeleri. */
class BrowserMenu(private val activity: Activity) {

    class Item(val icon: Int, val title: Int, val toggle: Boolean? = null, val action: () -> Unit)
    class Action(val icon: Int, val label: Int, val enabled: Boolean = true, val action: () -> Unit)

    private val dp = activity.resources.displayMetrics.density
    private fun px(v: Int) = (v * dp).toInt()

    /** null öğe ayırıcı çizgi olarak gösterilir. */
    fun show(anchor: View, items: List<Item?>, actions: List<Action>) {
        val text = activity.getColor(R.color.text)
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, px(10), 0, px(12))
            background = GradientDrawable().apply {
                setColor(activity.getColor(R.color.menu_bg))
                cornerRadius = 24 * dp
            }
            elevation = 16 * dp
        }
        lateinit var popup: PopupWindow

        for (item in items) {
            if (item == null) {
                card.addView(View(activity).apply {
                    setBackgroundColor(activity.getColor(R.color.divider))
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(1)).apply {
                        setMargins(px(20), px(6), px(20), px(6))
                    }
                })
                continue
            }
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(px(20), 0, px(16), 0)
                minimumHeight = px(52)
                background = activity.getDrawable(android.R.drawable.list_selector_background)
                addView(ImageView(activity).apply {
                    setImageResource(item.icon)
                    layoutParams = LinearLayout.LayoutParams(px(24), px(24)).apply { marginEnd = px(20) }
                })
                addView(TextView(activity).apply {
                    setText(item.title)
                    textSize = 17f
                    setTextColor(text)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
            }
            if (item.toggle != null) {
                val sw = Switch(activity).apply {
                    isChecked = item.toggle
                    isClickable = false
                }
                row.addView(sw)
            }
            row.setOnClickListener {
                popup.dismiss()
                item.action()
            }
            card.addView(row)
        }

        // Alt satır: yuvarlak hızlı eylem düğmeleri
        val bar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(px(12), px(10), px(12), 0)
        }
        for (a in actions) {
            bar.addView(ImageView(activity).apply {
                setImageResource(a.icon)
                contentDescription = activity.getString(a.label)
                scaleType = ImageView.ScaleType.CENTER
                alpha = if (a.enabled) 1f else 0.35f
                isEnabled = a.enabled
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(activity.getColor(R.color.field_bg))
                }
                layoutParams = LinearLayout.LayoutParams(px(52), px(52)).apply {
                    setMargins(px(8), 0, px(8), 0)
                }
                setOnClickListener {
                    popup.dismiss()
                    a.action()
                }
            })
        }
        card.addView(bar)

        val scroll = ScrollView(activity).apply { addView(card) }
        val maxHeight = (activity.resources.displayMetrics.heightPixels * 0.82).toInt()
        scroll.measure(
            View.MeasureSpec.makeMeasureSpec(px(320), View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST),
        )
        popup = PopupWindow(scroll, px(300), minOf(scroll.measuredHeight, maxHeight), true).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            elevation = 16 * dp
            isOutsideTouchable = true
        }
        popup.showAtLocation(anchor, Gravity.BOTTOM or Gravity.END, px(10), px(62))
    }
}
