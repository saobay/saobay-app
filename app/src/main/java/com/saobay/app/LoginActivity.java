package com.saobay.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.UUID;

/** Dang nhap bang tai khoan web SAOBAY (POST /api/v2/auth/login giong trang web). */
public class LoginActivity extends Activity {
    private EditText etPhone, etPass;
    private TextView tvMsg;
    private Button btnLogin;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Session.isLoggedIn(this)) {
            goMain();
            return;
        }
        setContentView(R.layout.activity_login);
        etPhone = findViewById(R.id.etPhone);
        etPass = findViewById(R.id.etPass);
        tvMsg = findViewById(R.id.tvLoginMsg);
        btnLogin = findViewById(R.id.btnLogin);
        btnLogin.setOnClickListener(v -> doLogin());
    }

    private void goMain() {
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }

    private String deviceId() {
        try {
            String aid = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
            if (aid != null && !aid.isEmpty()) return aid;
        } catch (Exception ignored) {}
        String id = Session.prefs(this).getString("device_uuid", "");
        if (id.isEmpty()) {
            id = UUID.randomUUID().toString();
            Session.prefs(this).edit().putString("device_uuid", id).apply();
        }
        return id;
    }

    private void setBusy(boolean busy) {
        btnLogin.setEnabled(!busy);
        btnLogin.setText(busy ? "Đang đăng nhập…" : "Đăng nhập");
    }

    private void doLogin() {
        final String phone = etPhone.getText().toString().trim();
        final String pass = etPass.getText().toString();
        if (phone.isEmpty() || pass.isEmpty()) {
            tvMsg.setText("Hãy nhập tài khoản (SĐT / Gmail / mã định danh / nickname) và mật khẩu.");
            return;
        }
        tvMsg.setText("");
        setBusy(true);
        new Thread(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("id", phone);
                body.put("password_hash", Api.sha256Hex(pass));
                body.put("device_id", deviceId());
                JSONObject j = Api.postJson(Api.BASE + "/api/v2/auth/login", body);
                JSONObject user = j.optJSONObject("user");
                if (!j.optBoolean("ok", false) || user == null) {
                    String err = j.optString("error", "");
                    String msg = "WRONG_PASSWORD".equals(err) ? "Sai mật khẩu — kiểm tra lại nhé."
                            : "NOT_FOUND".equals(err) ? "Không tìm thấy tài khoản này trên web SAOBAY."
                            : "Đăng nhập chưa được (" + (err.isEmpty() ? "lỗi không rõ" : err) + ").";
                    runOnUiThread(() -> { setBusy(false); tvMsg.setText(msg); });
                    return;
                }
                String role = user.optString("role", "");
                if (!("teacher".equals(role) || "admin".equals(role) || "bgh".equals(role))) {
                    runOnUiThread(() -> {
                        setBusy(false);
                        tvMsg.setText("Tài khoản này là tài khoản học sinh — app Sao Bay chỉ dành cho giáo viên.");
                    });
                    return;
                }
                String token = j.optString("session_token", user.optString("session_token", ""));
                Session.save(this,
                        user.optString("id", phone),
                        user.optString("name", ""),
                        role,
                        token,
                        user.optString("phone", phone));
                runOnUiThread(this::goMain);
            } catch (Exception e) {
                runOnUiThread(() -> {
                    setBusy(false);
                    tvMsg.setText("Không kết nối được máy chủ trường (kiểm tra mạng): " + e.getMessage());
                });
            }
        }).start();
    }
}
