package com.saobay.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.app.AlertDialog;
import android.content.pm.PackageInfo;
import android.os.Handler;
import android.os.Looper;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
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
    private SwipeRefreshLayout swipeRefresh;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        web = findViewById(R.id.webview);
        // SB_PULLREFRESH_20261019: kéo xuống để làm mới trang
        swipeRefresh = findViewById(R.id.swipe_refresh);
        swipeRefresh.setOnRefreshListener(() -> {
            web.reload();
        });
        swipeRefresh.setColorSchemeResources(
                android.R.color.holo_blue_bright,
                android.R.color.holo_green_light,
                android.R.color.holo_orange_light,
                android.R.color.holo_red_light);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                // SB_PULLREFRESH_20261019: tắt vòng xoay khi tải xong
                if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
            }
        });
        web.setWebChromeClient(new WebChromeClient());
        // SB_CASTBRIDGE_20261018: web gọi SaobayApp.openCast() để mở chiếu màn hình
        web.addJavascriptInterface(new Object() {
            @android.webkit.JavascriptInterface
            public void openCast() {
                runOnUiThread(() -> startActivity(new Intent(MainActivity.this, CastActivity.class)));
            }
        }, "SaobayApp");

        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState);
        } else {
            web.loadUrl(HOME);
        }

        // SB_NOFLOAT_20261018: bỏ nút nổi, vào chiếu màn hình từ trang live
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
