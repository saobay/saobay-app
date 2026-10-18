package com.saobay.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.app.AlertDialog;
import android.content.pm.PackageInfo;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Màn hình chính: WebView mở web trường SAOBAY toàn màn hình,
 * kèm nút nổi để vào chức năng chiếu màn hình.
 */
public class MainActivity extends Activity {

    private static final String HOME = "https://www.saobayhighschool.workers.dev";

    private WebView web;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        web = findViewById(R.id.webview);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new WebChromeClient());

        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState);
        } else {
            web.loadUrl(HOME);
        }

        Button btnCast = findViewById(R.id.btn_cast);
        btnCast.setOnClickListener(v ->
                startActivity(new Intent(MainActivity.this, CastActivity.class)));

        checkForUpdate();
    }

    // SB_APPUPDATE: kiểm tra version mới từ R2, admin chỉ cần sửa file version.json
    private static final String VERSION_URL = "https://www.saobayhighschool.workers.dev/app/version.json";
    private void checkForUpdate() {
        new Thread(() -> {
            try {
                URL url = new URL(VERSION_URL);
                HttpURLConnection c = (HttpURLConnection) url.openConnection();
                c.setConnectTimeout(8000); c.setReadTimeout(8000);
                if (c.getResponseCode() != 200) return;
                BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream()));
                StringBuilder sb = new StringBuilder(); String line;
                while ((line = r.readLine()) != null) sb.append(line);
                r.close();
                JSONObject j = new JSONObject(sb.toString());
                int remoteCode = j.optInt("versionCode", 0);
                String apkUrl = j.optString("apkUrl", "");
                String changelog = j.optString("changelog", "");
                PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
                int localCode = pi.versionCode;
                if (remoteCode > localCode && !apkUrl.isEmpty()) {
                    String verName = j.optString("versionName", String.valueOf(remoteCode));
                    new Handler(Looper.getMainLooper()).post(() ->
                        new AlertDialog.Builder(MainActivity.this)
                            .setTitle("Có bản cập nhật mới (v" + verName + ")")
                            .setMessage(changelog.isEmpty() ? "Đã có phiên bản mới của app SAOBAY." : changelog)
                            .setPositiveButton("Tải về", (d, w) -> {
                                Intent i = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(apkUrl));
                                startActivity(i);
                            })
                            .setNegativeButton("Để sau", null)
                            .show());
                }
            } catch (Exception ignored) {}
        }).start();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (web != null) web.saveState(outState);
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
