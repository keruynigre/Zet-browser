package dev.zet.browser

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.webkit.WebView

/**
 * Ekran kapanınca / uygulama arka plana geçince video çalmaya devam edebilen WebView.
 *
 * Android pencere görünmez olduğunda WebView'a bunu bildirir; Chromium da
 * sayfayı "gizli" sayıp videoyu duraklatır. [keepPlaying] açıkken bu bildirimi
 * "görünür" olarak iletiriz. Yalnızca o anda medya çalan sekmede açılır;
 * diğer sekmeler normal şekilde uyutulur (pil).
 */
class ZetWebView(context: Context) : WebView(context) {

    var keepPlaying: Boolean = false

    override fun onWindowVisibilityChanged(visibility: Int) {
        if (keepPlaying && visibility != View.VISIBLE) {
            super.onWindowVisibilityChanged(View.VISIBLE)
        } else {
            super.onWindowVisibilityChanged(visibility)
        }
    }

    // ---- Aşağı çekip yenileme ----------------------------------------------

    /** Çekme miktarı (piksel); 0 = bırakıldı/iptal. */
    var onPull: ((Float) -> Unit)? = null

    /** Parmak kalkınca: true = yenile. */
    var onPullEnd: ((Boolean) -> Unit)? = null
    var pullEnabled = true

    private var downY = 0f
    private var startedAtTop = false
    private var overscrolledTop = false
    private var pulling = false
    private val threshold = 110 * resources.displayMetrics.density

    override fun onOverScrolled(scrollX: Int, scrollY: Int, clampedX: Boolean, clampedY: Boolean) {
        super.onOverScrolled(scrollX, scrollY, clampedX, clampedY)
        // Sayfa (veya içteki kaydırılabilir alan) en üstteyken aşağı çekilmeye devam ediliyor.
        if (clampedY && scrollY == 0) overscrolledTop = true
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (pullEnabled) trackPull(ev)
        return super.onTouchEvent(ev)
    }

    private fun trackPull(ev: MotionEvent) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downY = ev.y
                startedAtTop = scrollY == 0
                overscrolledTop = false
                pulling = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> cancelPull() // yakınlaştırma hareketi
            MotionEvent.ACTION_MOVE -> {
                val dy = ev.y - downY
                if (startedAtTop && overscrolledTop && scrollY == 0 && dy > 0) {
                    pulling = true
                    onPull?.invoke(dy)
                } else if (pulling) {
                    cancelPull()
                }
            }
            MotionEvent.ACTION_UP -> {
                if (pulling) {
                    pulling = false
                    onPullEnd?.invoke(ev.y - downY >= threshold)
                }
            }
            MotionEvent.ACTION_CANCEL -> cancelPull()
        }
    }

    private fun cancelPull() {
        if (pulling) {
            pulling = false
            onPullEnd?.invoke(false)
        }
        startedAtTop = false
    }

    val pullThreshold get() = threshold
}
