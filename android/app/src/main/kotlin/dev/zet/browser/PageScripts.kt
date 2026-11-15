package dev.zet.browser

/** Sayfa araçlarının WebView'a enjekte ettiği küçük betikler. */
object PageScripts {

    /**
     * Sayfadaki sınıf ve kimlik adlarını toplar: {"c":[...],"i":[...]}.
     * Genel gizleme kuralları (çerez uyarıları, reklam kutuları) bunlara göre seçilir.
     */
    const val COLLECT_CLASS_IDS = """(function(){
      var c={},i={},n=0,els=document.querySelectorAll('[class],[id]');
      for(var k=0;k<els.length&&n<6000;k++){var e=els[k];
        if(e.id&&!i[e.id]){i[e.id]=1;n++;}
        var cl=e.classList;if(cl)for(var j=0;j<cl.length;j++){if(!c[cl[j]]){c[cl[j]]=1;n++;}}}
      return JSON.stringify({c:Object.keys(c),i:Object.keys(i)});
    })()"""

    fun injectStyle(id: String, css: String): String = """(function(){
      var s=document.getElementById('$id');
      if(!s){s=document.createElement('style');s.id='$id';
        (document.head||document.documentElement).appendChild(s);}
      s.textContent=${org.json.JSONObject.quote(css)};
    })();"""

    /**
     * Koyu sayfa: zaten koyu olan sitelere dokunmaz, açık renklileri
     * ters çevirir; resim/video ikinci kez çevrilerek doğal kalır.
     */
    const val DARK_ON = """(function(){
      if(document.getElementById('zet-dark'))return;
      function lum(el){if(!el)return -1;var c=getComputedStyle(el).backgroundColor;
        var m=c.match(/[\d.]+/g);if(!m||(m.length>3&&+m[3]===0))return -1;
        return (0.2126*m[0]+0.7152*m[1]+0.0722*m[2])/255;}
      var l=lum(document.body);if(l<0)l=lum(document.documentElement);
      var meta=document.querySelector('meta[name="color-scheme"]');
      if((l>=0&&l<0.45)||(l<0&&meta&&/^\s*dark/.test(meta.content)))return;
      var s=document.createElement('style');s.id='zet-dark';
      s.textContent='html{filter:invert(1) hue-rotate(180deg)!important;background:#fff!important}'+
        'img,video,picture,canvas,iframe,embed,object,svg image,[style*="background-image"]'+
        '{filter:invert(1) hue-rotate(180deg)!important}';
      (document.head||document.documentElement).appendChild(s);
    })();"""

    const val DARK_OFF = "(function(){var s=document.getElementById('zet-dark');if(s)s.remove();})();"

    /**
     * Okuma modu: en çok paragraf metni içeren öğeyi bulur, sade bir kopyasını
     * sayfanın üstüne tam ekran katman olarak koyar. Kapatınca sayfa aynen kalır.
     * Dönüş: "ok" veya "none".
     */
    const val READER = """(function(){
      var old=document.getElementById('zet-reader');
      if(old){old.remove();document.documentElement.style.overflow='';return 'ok';}
      var best=null,score=0,ps=document.querySelectorAll('p');
      var map=new Map();
      for(var k=0;k<ps.length;k++){var p=ps[k],t=(p.innerText||'').trim().length;
        if(t<40)continue;var par=p.parentElement;if(!par)continue;
        map.set(par,(map.get(par)||0)+t);
        var gp=par.parentElement;if(gp)map.set(gp,(map.get(gp)||0)+t*0.5);}
      map.forEach(function(v,e){if(v>score){score=v;best=e;}});
      var art=document.querySelector('article');
      if(art&&(art.innerText||'').length>score*0.8)best=art;
      if(!best||score<400)return 'none';
      var clone=best.cloneNode(true);
      clone.querySelectorAll('script,style,noscript,iframe,form,button,input,nav,aside,footer,header,svg,'+
        '[aria-hidden="true"],[class*="share"],[class*="related"],[class*="comment"],[class*="ad-"],[id*="ad-"]')
        .forEach(function(e){e.remove();});
      clone.querySelectorAll('*').forEach(function(e){e.removeAttribute('style');e.removeAttribute('class');
        e.removeAttribute('width');e.removeAttribute('height');
        if(e.tagName==='IMG'){var ds=e.getAttribute('data-src')||e.getAttribute('data-lazy-src');
          if(ds&&!e.getAttribute('src'))e.setAttribute('src',ds);}});
      var h1=document.querySelector('h1');
      var title=(h1&&h1.innerText.trim())||document.title;
      clone.querySelectorAll('h1').forEach(function(e){if(e.textContent.trim()===title.trim())e.remove();});
      var dark=matchMedia('(prefers-color-scheme: dark)').matches||!!document.getElementById('zet-dark');
      var box=document.createElement('div');box.id='zet-reader';
      var st=document.createElement('style');
      st.textContent='#zet-reader{position:fixed;inset:0;z-index:2147483647;overflow:auto;'+
        'background:#faf7f0;color:#222;font:19px/1.65 Georgia,serif;padding:20px 20px 80px;box-sizing:border-box}'+
        '#zet-reader *{max-width:100%!important;color:inherit!important;background:none!important;'+
        'font-family:inherit!important;position:static!important;float:none!important;filter:none!important}'+
        '#zet-reader img,#zet-reader figure{height:auto!important;display:block;margin:12px auto}'+
        '#zet-reader h1.zt{font:bold 26px/1.3 sans-serif!important;margin:8px 0 18px}'+
        '#zet-reader a{color:#1d4ed8!important}'+
        '#zet-reader .zx{position:fixed!important;right:14px;bottom:18px;width:52px;height:52px;'+
        'border-radius:26px;border:0;background:#16a34a!important;color:#fff!important;font:24px sans-serif!important}';
      box.appendChild(st);
      var h=document.createElement('h1');h.className='zt';h.textContent=title;box.appendChild(h);
      box.appendChild(clone);
      var x=document.createElement('button');x.className='zx';x.textContent='✕';
      x.onclick=function(){box.remove();document.documentElement.style.overflow='';};
      box.appendChild(x);
      document.documentElement.appendChild(box);
      document.documentElement.style.overflow='hidden';
      if(dark){box.style.background='#1c1c1e';box.style.color='#e5e5e5';}
      if(document.getElementById('zet-dark')){box.style.filter='invert(1) hue-rotate(180deg)';}
      return 'ok';
    })()"""

    /** Ortak yardımcılar: görünürlük ve şifre alanının kullanıcı adı eşi. */
    private const val PW_HELPERS = """
      function zv(e){return !!(e.offsetWidth||e.offsetHeight||e.getClientRects().length);}
      function zpair(pw){var root=pw.form||document,ins=[].slice.call(root.querySelectorAll('input'));
        var idx=ins.indexOf(pw);
        for(var i=idx-1;i>=0;i--){var t=(ins[i].type||'text').toLowerCase();
          if((t==='text'||t==='email'||t==='tel')&&zv(ins[i]))return ins[i];}
        return null;}"""

    /**
     * Giriş formlarını izler: gönderim / düğme / Enter anında dolu şifre alanını
     * ZetPw.offer(kullanıcı, şifre) ile bildirir. Kaydetme sorusu sayfa
     * değişince sorulur (göz düğmesi vb. yanlış tetiklemeleri önlemek için).
     */
    val PW_CAPTURE = """(function(){if(window.__zetPw)return;window.__zetPw=1;$PW_HELPERS
      function grab(){var pws=[].slice.call(document.querySelectorAll('input[type=password]'))
          .filter(function(p){return p.value;});
        if(!pws.length||!window.ZetPw)return;var pw=pws[0],u=zpair(pw);
        try{ZetPw.offer(u?u.value:'',pw.value);}catch(e){}}
      document.addEventListener('submit',grab,true);
      document.addEventListener('click',function(e){var b=e.target&&e.target.closest&&
        e.target.closest('button,input[type=submit],[role=button]');if(b)setTimeout(grab,0);},true);
      document.addEventListener('keydown',function(e){if(e.key==='Enter'&&e.target&&
        e.target.type==='password')grab();},true);
    })();"""

    /** Boş şifre alanını (ve eşi boş kullanıcı alanını) doldurur. Dönüş: 1 = dolduruldu. */
    fun pwFill(user: String, pass: String): String = """(function(u,p){$PW_HELPERS
      var pws=[].slice.call(document.querySelectorAll('input[type=password]')).filter(zv);
      if(!pws.length)return 0;var pw=pws[0];if(pw.value)return 0;
      function set(e,v){var d=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value');
        d.set.call(e,v);e.dispatchEvent(new Event('input',{bubbles:true}));
        e.dispatchEvent(new Event('change',{bubbles:true}));}
      var us=zpair(pw);if(us&&!us.value&&u)set(us,u);set(pw,p);return 1;
    })(${org.json.JSONObject.quote(user)},${org.json.JSONObject.quote(pass)})"""

    /** Masaüstü tarayıcı kimliği; Chrome sürümü mobil kimlikten alınır. */
    fun desktopUserAgent(mobileUa: String): String {
        val chrome = Regex("Chrome/([\\d.]+)").find(mobileUa)?.groupValues?.get(1) ?: "130.0.0.0"
        return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/$chrome Safari/537.36"
    }
}
