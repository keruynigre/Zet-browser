package dev.zet.browser

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView

/**
 * Brave tarzı tam ekran sekme ızgarası: önizlemeli kartlar, normal/gizli
 * sekme geçişi, sekmelerde arama, ortada + düğmesi, ⋮ ile tümünü kapatma.
 */
class TabSwitcher(
    private val activity: Activity,
    private val container: FrameLayout,
    private val callbacks: Callbacks,
) {
    data class Info(
        val id: Int,
        val title: String,
        val url: String,
        val incognito: Boolean,
        val thumb: Bitmap?,
        val current: Boolean,
    )

    interface Callbacks {
        fun tabs(): List<Info>
        fun select(id: Int)
        fun close(id: Int)
        fun newTab(incognito: Boolean)
        fun closeAll(incognito: Boolean)
    }

    private val dp = activity.resources.displayMetrics.density
    private fun px(v: Int) = (v * dp).toInt()

    private var view: View? = null
    private var showIncognito = false
    private var query = ""
    private var items: List<Info> = emptyList()
    private lateinit var adapter: BaseAdapter
    private lateinit var normalToggle: TextView
    private lateinit var incognitoToggle: ImageView
    private lateinit var root: LinearLayout

    val isShowing get() = view != null

    fun show(incognito: Boolean) {
        hide()
        showIncognito = incognito
        query = ""
        root = build()
        view = root
        container.addView(root, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        refresh()
    }

    fun hide() {
        view?.let { container.removeView(it) }
        view = null
    }

    fun refresh() {
        if (view == null) return
        val all = callbacks.tabs()
        items = all.filter { it.incognito == showIncognito }
            .filter { query.isBlank() || it.title.contains(query, true) || it.url.contains(query, true) }
        normalToggle.text = all.count { !it.incognito }.toString()
        styleToggles()
        adapter.notifyDataSetChanged()
    }

    private fun styleToggles() {
        val on = activity.getColor(R.color.menu_bg)
        normalToggle.background = GradientDrawable().apply {
            cornerRadius = 18 * dp
            setColor(if (!showIncognito) on else Color.TRANSPARENT)
        }
        incognitoToggle.background = GradientDrawable().apply {
            cornerRadius = 18 * dp
            setColor(if (showIncognito) on else Color.TRANSPARENT)
        }
        root.setBackgroundColor(activity.getColor(if (showIncognito) R.color.incognito_bg else R.color.bar_bg))
    }

    private fun build(): LinearLayout {
        val text = activity.getColor(R.color.text)
        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
        }

        // Üst: normal / gizli geçişi
        val segment = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(px(6), px(6), px(6), px(6))
            background = GradientDrawable().apply {
                cornerRadius = 24 * dp
                setColor(activity.getColor(R.color.field_bg))
            }
        }
        normalToggle = TextView(activity).apply {
            gravity = Gravity.CENTER
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(text)
            layoutParams = LinearLayout.LayoutParams(px(64), px(40))
            setOnClickListener { showIncognito = false; refresh() }
        }
        incognitoToggle = ImageView(activity).apply {
            setImageResource(R.drawable.ic_incognito)
            scaleType = ImageView.ScaleType.CENTER
            layoutParams = LinearLayout.LayoutParams(px(64), px(40))
            setOnClickListener { showIncognito = true; refresh() }
        }
        segment.addView(normalToggle)
        segment.addView(incognitoToggle)
        layout.addView(FrameLayout(activity).apply {
            setPadding(0, px(14), 0, px(10))
            addView(segment, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        })

        // Sekmelerde ara
        layout.addView(EditText(activity).apply {
            hint = activity.getString(R.string.search_tabs)
            setSingleLine()
            textSize = 15f
            setTextColor(text)
            setHintTextColor(activity.getColor(R.color.text_dim))
            background = GradientDrawable().apply {
                cornerRadius = 24 * dp
                setColor(activity.getColor(R.color.field_bg))
            }
            setPadding(px(18), 0, px(18), 0)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(48)).apply {
                setMargins(px(16), 0, px(16), px(8))
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    query = s?.toString() ?: ""
                    refresh()
                }
            })
        })

        // Kart ızgarası
        adapter = object : BaseAdapter() {
            override fun getCount() = items.size
            override fun getItem(position: Int) = items[position]
            override fun getItemId(position: Int) = items[position].id.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View =
                card(items[position])
        }
        layout.addView(GridView(activity).apply {
            numColumns = 2
            horizontalSpacing = px(12)
            verticalSpacing = px(14)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            setPadding(px(16), px(4), px(16), px(16))
            clipToPadding = false
            selector = GradientDrawable().apply { setColor(Color.TRANSPARENT) }
            this.adapter = this@TabSwitcher.adapter
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        })

        // Alt: + ve ⋮
        val bottom = FrameLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(76))
        }
        bottom.addView(ImageView(activity).apply {
            setImageResource(R.drawable.ic_add)
            imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            scaleType = ImageView.ScaleType.CENTER
            background = GradientDrawable().apply {
                cornerRadius = 20 * dp
                setColor(activity.getColor(R.color.accent))
            }
            elevation = 6 * dp
            setOnClickListener { callbacks.newTab(showIncognito) }
        }, FrameLayout.LayoutParams(px(64), px(56), Gravity.CENTER))
        bottom.addView(ImageView(activity).apply {
            setImageResource(R.drawable.ic_more)
            scaleType = ImageView.ScaleType.CENTER
            setOnClickListener { v ->
                PopupMenu(activity, v).apply {
                    menu.add(R.string.close_all_tabs)
                    setOnMenuItemClickListener {
                        callbacks.closeAll(showIncognito)
                        true
                    }
                }.show()
            }
        }, FrameLayout.LayoutParams(px(56), px(56), Gravity.CENTER_VERTICAL or Gravity.END).apply {
            marginEnd = px(12)
        })
        layout.addView(bottom)
        return layout
    }

    private fun card(info: Info): View {
        val accent = activity.getColor(R.color.accent)
        val cardColor = if (info.current) accent else activity.getColor(R.color.field_bg)
        val titleColor = if (info.current) Color.WHITE else activity.getColor(R.color.text)
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(6), px(4), px(6), px(6))
            background = GradientDrawable().apply {
                cornerRadius = 22 * dp
                setColor(cardColor)
            }
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(250))
            setOnClickListener { callbacks.select(info.id) }
        }
        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(8), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(44))
        }
        header.addView(TextView(activity).apply {
            text = LibraryActivity.siteLetter(info.url)
            gravity = Gravity.CENTER
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (info.current) 0x40FFFFFF else activity.getColor(R.color.accent))
            }
            layoutParams = LinearLayout.LayoutParams(px(24), px(24)).apply { marginEnd = px(8) }
        })
        header.addView(TextView(activity).apply {
            text = info.title.ifBlank { info.url.ifBlank { activity.getString(R.string.tab_untitled) } }
            textSize = 14f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(titleColor)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(ImageView(activity).apply {
            setImageResource(R.drawable.ic_close)
            imageTintList = android.content.res.ColorStateList.valueOf(titleColor)
            scaleType = ImageView.ScaleType.CENTER
            layoutParams = LinearLayout.LayoutParams(px(40), px(40))
            setOnClickListener { callbacks.close(info.id) }
        })
        card.addView(header)
        val thumb = ImageView(activity).apply {
            scaleType = ImageView.ScaleType.MATRIX
            background = GradientDrawable().apply {
                cornerRadius = 16 * dp
                setColor(activity.getColor(R.color.menu_bg))
            }
            clipToOutline = true
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        info.thumb?.let { bmp ->
            thumb.setImageBitmap(bmp)
            // Üstten hizalı, genişliğe sığdır.
            thumb.addOnLayoutChangeListener { v, l, _, r, _, _, _, _, _ ->
                val w = r - l
                if (w > 0) {
                    val scale = w.toFloat() / bmp.width
                    (v as ImageView).imageMatrix = android.graphics.Matrix().apply { setScale(scale, scale) }
                }
            }
        }
        card.addView(thumb)
        return card
    }
}
