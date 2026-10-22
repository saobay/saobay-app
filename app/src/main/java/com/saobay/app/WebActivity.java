package com.saobay.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.DownloadListener;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/** Mo web truong ngay trong app (GV xem/giao bai tren web khi can).
 *  SSO: tu gui phien dang nhap cua app sang web (localStorage 'saobay_user'
 *  ma web doc luc khoi dong) -> GV khong phai dang nhap lai. */
public class WebActivity extends Activity {
    private WebView web;
    private boolean ssoTried = false;

    @Override
    @SuppressLint("SetJavaScriptEnabled")
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_web);
        NavStrip.build(this, findViewById(R.id.navStripWeb), "web");
        web = findViewById(R.id.web);
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setMediaPlaybackRequiresUserGesture(false);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                injectSessionOnce(view);
            }
        });
        // File .apk / tai lieu tai ve -> dua sang trinh duyet cua may xu ly
        web.setDownloadListener((url, userAgent, contentDisposition, mimetype, contentLength) -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            } catch (Exception ignored) {}
        });
        findViewById(R.id.btnCloseWeb).setOnClickListener(v -> finish());
        findViewById(R.id.btnReloadWeb).setOnClickListener(v -> web.reload());
        web.loadUrl(Api.BASE + "/");
    }

    /** Neu web chua co phien (hoac phien cua tai khoan khac) thi gan
     *  localStorage.saobay_user theo phien app roi tai lai trang 1 lan. */
    private void injectSessionOnce(WebView view) {
        if (ssoTried || !Session.isLoggedIn(this)) return;
        ssoTried = true;
        try {
            org.json.JSONObject u = new org.json.JSONObject();
            u.put("id", Session.uid(this));
            u.put("name", Session.name(this));
            u.put("role", Session.role(this));
            u.put("phone", Session.phone(this));
            u.put("class_name", Session.className(this));
            u.put("session_token", Session.token(this));
            String userJson = org.json.JSONObject.quote(u.toString());
            String uidJson = org.json.JSONObject.quote(Session.uid(this));
            String js = "(function(){try{"
                    + "var cur=localStorage.getItem('saobay_user');var ok=false;"
                    + "if(cur){try{ok=JSON.parse(cur).id===" + uidJson + ";}catch(e){}}"
                    + "if(!ok){localStorage.setItem('saobay_user'," + userJson + ");location.reload();}"
                    + "}catch(e){}})();";
            view.evaluateJavascript(js, null);
        } catch (Exception ignored) {}
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }
}
