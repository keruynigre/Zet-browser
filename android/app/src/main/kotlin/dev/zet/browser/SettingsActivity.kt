package dev.zet.browser

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** Ayarlar ekranı. Değişiklikler SharedPreferences'a yazılır; tarayıcı dönüşte okur. */
class SettingsActivity : Activity() {

    private val dp by lazy { resources.displayMetrics.density }
    private fun px(v: Int) = (v * dp).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Settings.prefs(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(4), 0, px(8), 0)
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_back)
                scaleType = ImageView.ScaleType.CENTER
                layoutParams = LinearLayout.LayoutParams(px(48), px(48))
                setOnClickListener { finish() }
            })
            addView(TextView(context).apply {
                text = getString(R.string.settings)
                textSize = 20f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(getColor(R.color.text))
                layoutParams = LinearLayout.LayoutParams(0, px(56), 1f)
                gravity = Gravity.CENTER_VERTICAL
            })
        }
        content.addView(header)

        section(content, R.string.settings_general)
        val engineRow = row(content, getString(R.string.search_engine), Settings.engine(this).name) {}
        engineRow.setOnClickListener {
            val names = Settings.ENGINES.map { it.name }.toTypedArray()
            val current = Settings.ENGINES.indexOf(Settings.engine(this))
            AlertDialog.Builder(this)
                .setTitle(R.string.search_engine)
                .setSingleChoiceItems(names, current) { d, which ->
                    prefs.edit().putString(Settings.ENGINE, Settings.ENGINES[which].id).apply()
                    (engineRow.getChildAt(1) as TextView).text = names[which]
                    d.dismiss()
                }
                .show()
        }

        section(content, R.string.settings_privacy)
        toggle(content, R.string.shields, R.string.shields_desc, Settings.ADBLOCK, true)
        toggle(content, R.string.save_history, R.string.save_history_desc, Settings.SAVE_HISTORY, true)
        row(content, getString(R.string.clear_history), getString(R.string.clear_history_desc)) {
            confirm(R.string.clear_history_confirm) {
                ZetDb.get(this).clearHistory()
                Toast.makeText(this, R.string.cleared, Toast.LENGTH_SHORT).show()
            }
        }
        row(content, getString(R.string.clear_site_data), getString(R.string.clear_site_data_desc)) {
            confirm(R.string.clear_site_data_confirm) {
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
                WebStorage.getInstance().deleteAllData()
                WebView(this).apply { clearCache(true); destroy() }
                Toast.makeText(this, R.string.cleared, Toast.LENGTH_SHORT).show()
            }
        }

        section(content, R.string.settings_media)
        toggle(content, R.string.background_play, R.string.background_play_desc, Settings.BACKGROUND_PLAY, true)

        section(content, R.string.settings_about)
        val version = packageManager.getPackageInfo(packageName, 0).versionName
        row(content, "Zet", getString(R.string.about_desc, version)) {}

        setContentView(ScrollView(this).apply {
            setBackgroundColor(getColor(R.color.bar_bg))
            addView(content)
        })
        window.statusBarColor = getColor(R.color.bar_bg)
    }

    private fun section(parent: LinearLayout, title: Int) {
        parent.addView(TextView(this).apply {
            text = getString(title)
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(getColor(R.color.accent))
            setPadding(px(20), px(18), px(20), px(6))
        })
    }

    private fun row(parent: LinearLayout, title: String, subtitle: String, onClick: () -> Unit): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20), px(12), px(20), px(12))
            background = getDrawable(android.R.drawable.list_selector_background)
            addView(TextView(context).apply {
                text = title
                textSize = 16f
                setTextColor(getColor(R.color.text))
            })
            addView(TextView(context).apply {
                text = subtitle
                textSize = 13f
                setTextColor(getColor(R.color.text_dim))
            })
            setOnClickListener { onClick() }
        }
        parent.addView(row)
        return row
    }

    private fun toggle(parent: LinearLayout, title: Int, desc: Int, key: String, default: Boolean) {
        val prefs = Settings.prefs(this)
        val sw = Switch(this).apply {
            isChecked = prefs.getBoolean(key, default)
            setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean(key, on).apply() }
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(20), px(12), px(16), px(12))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(context).apply {
                    text = getString(title)
                    textSize = 16f
                    setTextColor(getColor(R.color.text))
                })
                addView(TextView(context).apply {
                    text = getString(desc)
                    textSize = 13f
                    setTextColor(getColor(R.color.text_dim))
                })
            })
            addView(sw)
            setOnClickListener { sw.toggle() }
        }
        parent.addView(row)
    }

    private fun confirm(message: Int, action: () -> Unit) {
        AlertDialog.Builder(this)
            .setMessage(message)
            .setPositiveButton(R.string.ok) { _, _ -> action() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
