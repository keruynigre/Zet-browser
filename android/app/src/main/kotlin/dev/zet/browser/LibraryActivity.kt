package dev.zet.browser

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView

/** Geçmiş ve yer işaretleri listesi. Seçilen adres sonuç olarak döner. */
class LibraryActivity : Activity() {

    private lateinit var db: ZetDb
    private lateinit var mode: String
    private var items: List<ZetDb.Entry> = emptyList()
    private lateinit var adapter: BaseAdapter
    private lateinit var empty: TextView
    private var query = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = ZetDb.get(this)
        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_HISTORY
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.bar_bg))
        }

        // Başlık çubuğu
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(4), 0, px(8), 0)
        }
        header.addView(iconButton(R.drawable.ic_back, px(48)) { finish() })
        header.addView(TextView(this).apply {
            text = getString(if (mode == MODE_HISTORY) R.string.history else R.string.bookmarks)
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(getColor(R.color.text))
            layoutParams = LinearLayout.LayoutParams(0, px(56), 1f)
            gravity = Gravity.CENTER_VERTICAL
        })
        if (mode == MODE_HISTORY) {
            header.addView(iconButton(R.drawable.ic_delete, px(48)) {
                AlertDialog.Builder(this)
                    .setMessage(R.string.clear_history_confirm)
                    .setPositiveButton(R.string.clear_history) { _, _ -> db.clearHistory(); reload() }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            })
        }
        root.addView(header)

        // Arama
        root.addView(EditText(this).apply {
            hint = getString(R.string.search_in_list)
            setSingleLine()
            background = getDrawable(R.drawable.url_field_bg)
            setPadding(px(16), 0, px(16), 0)
            textSize = 15f
            setTextColor(getColor(R.color.text))
            setHintTextColor(getColor(R.color.text_dim))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(44)).apply {
                setMargins(px(12), 0, px(12), px(8))
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    query = s?.toString() ?: ""
                    reload()
                }
            })
        })

        empty = TextView(this).apply {
            text = getString(if (mode == MODE_HISTORY) R.string.history_empty else R.string.bookmarks_empty)
            gravity = Gravity.CENTER
            setTextColor(getColor(R.color.text_dim))
            setPadding(px(24), px(48), px(24), px(48))
            visibility = View.GONE
        }
        root.addView(empty)

        adapter = object : BaseAdapter() {
            override fun getCount() = items.size
            override fun getItem(position: Int) = items[position]
            override fun getItemId(position: Int) = items[position].id
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val e = items[position]
                val row = (convertView as? LinearLayout) ?: makeRow(px(16))
                (row.getChildAt(0) as TextView).text = siteLetter(e.url)
                val texts = row.getChildAt(1) as LinearLayout
                (texts.getChildAt(0) as TextView).text = e.title.ifBlank { e.url }
                (texts.getChildAt(1) as TextView).text = if (mode == MODE_HISTORY) {
                    DateUtils.getRelativeTimeSpanString(e.time).toString() + " · " + host(e.url)
                } else {
                    e.url
                }
                return row
            }
        }
        val list = ListView(this).apply {
            this.adapter = this@LibraryActivity.adapter
            divider = null
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            setOnItemClickListener { _, _, pos, _ ->
                setResult(RESULT_OK, Intent().putExtra(EXTRA_URL, items[pos].url))
                finish()
            }
            setOnItemLongClickListener { _, _, pos, _ ->
                val e = items[pos]
                AlertDialog.Builder(this@LibraryActivity)
                    .setTitle(e.title.ifBlank { e.url })
                    .setItems(arrayOf(getString(R.string.open_in_new_tab), getString(R.string.delete))) { _, which ->
                        when (which) {
                            0 -> {
                                setResult(RESULT_OK, Intent().putExtra(EXTRA_URL, e.url).putExtra(EXTRA_NEW_TAB, true))
                                finish()
                            }
                            1 -> {
                                if (mode == MODE_HISTORY) db.deleteHistory(e.id) else db.deleteBookmark(e.id)
                                reload()
                            }
                        }
                    }
                    .show()
                true
            }
        }
        root.addView(list)
        setContentView(root)
        window.statusBarColor = getColor(R.color.bar_bg)
        reload()
    }

    private fun reload() {
        items = if (mode == MODE_HISTORY) db.history(query) else db.bookmarks(query)
        adapter.notifyDataSetChanged()
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun makeRow(pad: Int): LinearLayout {
        val dp = resources.displayMetrics.density
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad, (10 * dp).toInt(), pad, (10 * dp).toInt())
            addView(TextView(context).apply {
                gravity = Gravity.CENTER
                textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(0xFFFFFFFF.toInt())
                background = getDrawable(R.drawable.new_tab_bg)
                layoutParams = LinearLayout.LayoutParams((36 * dp).toInt(), (36 * dp).toInt()).apply {
                    marginEnd = (14 * dp).toInt()
                }
            })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(context).apply {
                    textSize = 15f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setTextColor(getColor(R.color.text))
                })
                addView(TextView(context).apply {
                    textSize = 12f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                    setTextColor(getColor(R.color.text_dim))
                })
            })
        }
    }

    private fun iconButton(icon: Int, size: Int, onClick: () -> Unit) = ImageView(this).apply {
        setImageResource(icon)
        scaleType = ImageView.ScaleType.CENTER
        layoutParams = LinearLayout.LayoutParams(size, size)
        background = getDrawable(android.R.drawable.list_selector_background)
        setOnClickListener { onClick() }
    }

    companion object {
        const val EXTRA_MODE = "mode"
        const val EXTRA_URL = "url"
        const val EXTRA_NEW_TAB = "new_tab"
        const val MODE_HISTORY = "history"
        const val MODE_BOOKMARKS = "bookmarks"

        fun host(url: String): String = runCatching { android.net.Uri.parse(url).host }.getOrNull()
            ?.removePrefix("www.")?.removePrefix("m.") ?: url

        fun siteLetter(url: String): String =
            host(url).firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "•"

        fun intent(c: Context, mode: String) = Intent(c, LibraryActivity::class.java).putExtra(EXTRA_MODE, mode)
    }
}
