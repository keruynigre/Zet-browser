package dev.zet.browser

import android.app.Activity
import android.app.AlertDialog
import android.app.KeyguardManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
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

        toggle(content, R.string.search_suggestions, R.string.search_suggestions_desc, Settings.SUGGESTIONS, true)

        section(content, R.string.settings_display)
        toggle(content, R.string.dark_pages, R.string.dark_pages_desc, Settings.DARK_PAGES, false)
        toggle(content, R.string.pull_refresh, R.string.pull_refresh_desc, Settings.PULL_REFRESH, true)
        val zoomRow = row(
            content, getString(R.string.text_size),
            getString(R.string.text_size_desc, Settings.textZoom(this)),
        ) {}
        zoomRow.setOnClickListener {
            val values = (50..200 step 10).toList()
            AlertDialog.Builder(this)
                .setTitle(R.string.text_size)
                .setItems(values.map { "%$it" }.toTypedArray()) { _, which ->
                    prefs.edit().putInt(Settings.TEXT_ZOOM, values[which]).apply()
                    (zoomRow.getChildAt(1) as TextView).text = getString(R.string.text_size_desc, values[which])
                }
                .show()
        }

        section(content, R.string.settings_privacy)
        toggle(content, R.string.shields, R.string.shields_desc, Settings.ADBLOCK, true)
        toggle(content, R.string.cookie_banners, R.string.cookie_banners_desc, Settings.COOKIE_BANNERS, true)
        toggle(content, R.string.https_first, R.string.https_first_desc, Settings.HTTPS_FIRST, true)
        toggle(content, R.string.save_passwords, R.string.save_passwords_desc, Settings.SAVE_PASSWORDS, true)
        pwRow = row(content, getString(R.string.passwords), "") { openPasswords() }
        updatePwRow()
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
        val qLabel = { id: String -> Settings.YT_QUALITIES.firstOrNull { it.first == id }?.second ?: id }
        val qRow = row(content, getString(R.string.yt_quality), qLabel(Settings.ytQuality(this))) {}
        qRow.setOnClickListener {
            val ids = Settings.YT_QUALITIES.map { it.first }
            AlertDialog.Builder(this)
                .setTitle(R.string.yt_quality)
                .setSingleChoiceItems(
                    Settings.YT_QUALITIES.map { it.second }.toTypedArray(),
                    ids.indexOf(Settings.ytQuality(this)),
                ) { d, which ->
                    prefs.edit().putString(Settings.YT_QUALITY, ids[which]).apply()
                    (qRow.getChildAt(1) as TextView).text = qLabel(ids[which])
                    d.dismiss()
                }
                .show()
        }

        section(content, R.string.settings_about)
        val version = packageManager.getPackageInfo(packageName, 0).versionName
        row(content, "Zet", getString(R.string.about_desc, version) + " · android-" + Updater.currentCode(this)) {}
        row(content, getString(R.string.check_updates), "github.com/keruynigre/Zet-browser") {
            Updater.check(this, manual = true)
        }
        toggle(content, R.string.auto_update, R.string.auto_update_desc, Updater.PREF_AUTO, true)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(getColor(R.color.bar_bg))
            addView(content)
        })
        window.statusBarColor = getColor(R.color.bar_bg)
    }

    private lateinit var pwRow: LinearLayout

    private fun updatePwRow() {
        (pwRow.getChildAt(1) as TextView).text = getString(R.string.passwords_desc, PasswordStore.get(this).all().size)
    }

    /** Şifreleri göstermeden önce cihaz kilidi (PIN/parmak izi) sorulur. */
    private fun openPasswords() {
        val km = getSystemService(KeyguardManager::class.java)
        if (km != null && km.isDeviceSecure) {
            @Suppress("DEPRECATION")
            val intent = km.createConfirmDeviceCredentialIntent(getString(R.string.passwords), null)
            if (intent != null) {
                @Suppress("DEPRECATION")
                startActivityForResult(intent, REQ_UNLOCK)
                return
            }
        }
        showPasswords()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_UNLOCK && resultCode == RESULT_OK) showPasswords()
    }

    private fun showPasswords() {
        val store = PasswordStore.get(this)
        val list = store.all()
        if (list.isEmpty()) {
            Toast.makeText(this, R.string.no_passwords, Toast.LENGTH_SHORT).show()
            return
        }
        val labels = list.map {
            val host = android.net.Uri.parse(it.origin).host ?: it.origin
            "$host\n${it.user.ifEmpty { getString(R.string.no_username) }}"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.passwords)
            .setItems(labels) { _, which -> showPassword(list[which]) }
            .show()
    }

    private fun showPassword(c: PasswordStore.Cred) {
        val host = android.net.Uri.parse(c.origin).host ?: c.origin
        val base = c.user.ifEmpty { getString(R.string.no_username) }
        val dialog = AlertDialog.Builder(this)
            .setTitle(host)
            .setMessage("$base\n" + "•".repeat(10))
            .setPositiveButton(R.string.pw_copy) { _, _ ->
                getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("password", c.pass))
                Toast.makeText(this, R.string.pw_copied, Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton(R.string.pw_show, null)
            .setNegativeButton(R.string.delete) { _, _ ->
                confirm(R.string.delete) {
                    PasswordStore.get(this).remove(c)
                    updatePwRow()
                }
            }
            .create()
        dialog.setOnShowListener {
            // "Göster" diyaloğu kapatmadan şifreyi açar.
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                dialog.setMessage("$base\n${c.pass}")
            }
        }
        dialog.show()
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

    companion object {
        private const val REQ_UNLOCK = 3
    }
}
