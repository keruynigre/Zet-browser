package dev.zet.browser

import android.content.Context
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
}
