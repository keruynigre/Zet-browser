package dev.zet.browser

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Kayıtlı şifreler. Tüm liste tek blok halinde, Android Keystore'daki
 * (cihazdan çıkarılamayan) AES-GCM anahtarıyla şifrelenip saklanır.
 */
class PasswordStore private constructor(context: Context) {

    data class Cred(val origin: String, val user: String, val pass: String, val used: Long)

    private val prefs = context.getSharedPreferences("zet_pw", Context.MODE_PRIVATE)
    private val creds = mutableListOf<Cred>()
    private val never = HashSet<String>()

    init {
        load()
    }

    @Synchronized
    fun forOrigin(origin: String): List<Cred> =
        creds.filter { it.origin == origin }.sortedByDescending { it.used }

    @Synchronized
    fun all(): List<Cred> = creds.sortedWith(compareBy({ it.origin }, { it.user }))

    @Synchronized
    fun isNever(origin: String) = origin in never

    @Synchronized
    fun setNever(origin: String) {
        never += origin
        persist()
    }

    /** Aynı kaynak+kullanıcı varsa günceller. */
    @Synchronized
    fun save(origin: String, user: String, pass: String) {
        creds.removeAll { it.origin == origin && it.user == user }
        creds += Cred(origin, user, pass, System.currentTimeMillis())
        persist()
    }

    @Synchronized
    fun remove(c: Cred) {
        creds.removeAll { it.origin == c.origin && it.user == c.user }
        persist()
    }

    @Synchronized
    fun markUsed(c: Cred) {
        val i = creds.indexOfFirst { it.origin == c.origin && it.user == c.user }
        if (i >= 0) {
            creds[i] = creds[i].copy(used = System.currentTimeMillis())
            persist()
        }
    }

    private fun load() {
        val blob = prefs.getString(KEY_BLOB, null) ?: return
        try {
            val json = JSONObject(decrypt(blob))
            val arr = json.optJSONArray("c") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                creds += Cred(o.getString("o"), o.getString("u"), o.getString("p"), o.optLong("t"))
            }
            val nv = json.optJSONArray("n") ?: JSONArray()
            for (i in 0 until nv.length()) never += nv.getString(i)
        } catch (e: Exception) {
            Log.w(TAG, "Şifreler okunamadı", e)
        }
    }

    private fun persist() {
        val arr = JSONArray()
        for (c in creds) {
            arr.put(JSONObject().put("o", c.origin).put("u", c.user).put("p", c.pass).put("t", c.used))
        }
        val json = JSONObject().put("c", arr).put("n", JSONArray(never.toList()))
        try {
            prefs.edit().putString(KEY_BLOB, encrypt(json.toString())).apply()
        } catch (e: Exception) {
            Log.w(TAG, "Şifreler kaydedilemedi", e)
        }
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val out = c.iv + c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    private fun decrypt(blob: String): String {
        val raw = Base64.decode(blob, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, 12))
        return String(c.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8)
    }

    companion object {
        private const val TAG = "ZetPw"
        private const val ALIAS = "zet_passwords"
        private const val KEY_BLOB = "blob"

        @Volatile
        private var instance: PasswordStore? = null

        fun get(c: Context): PasswordStore =
            instance ?: synchronized(this) { instance ?: PasswordStore(c.applicationContext).also { instance = it } }

        /** "https://site.com" biçiminde kaynak. */
        fun originOf(url: String?): String? {
            val u = android.net.Uri.parse(url ?: return null)
            val scheme = u.scheme ?: return null
            if (scheme != "https" && scheme != "http") return null
            val host = u.host ?: return null
            return if (u.port > 0) "$scheme://$host:${u.port}" else "$scheme://$host"
        }
    }
}
