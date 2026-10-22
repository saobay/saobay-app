package com.saobay.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Man hinh chinh app Sao Bay (cho giao vien): cham thi trac nghiem,
 * nhac gio len lop, mo web truong, dong bo du lieu, nut noi chup man hinh,
 * cap nhat app. Mo app phai dang nhap bang tai khoan web SAOBAY.
 */
public class MainActivity extends Activity {
    private static final int REQ_PROJECTION = 45;
    private static final String VERSION_URL =
            "https://www.saobayhighschool.workers.dev/downloads/saobay-version.json";

    private TextView tvHello, tvRole, tvSyncStatus, tvVersion;
    private Button btnFloat, btnUpdate;
    private MediaProjectionManager projMgr;
    private long downloadId = -1;
    private boolean syncing = false;

    private final BroadcastReceiver dlReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
            if (id != downloadId) return;
            DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            if (dm == null) return;
            Uri uri = dm.getUriForDownloadedFile(id);
            if (uri == null) {
                toast("Tải bản mới chưa xong — kiểm tra mạng rồi bấm Cập nhật lại.");
                return;
            }
            openInstaller(uri);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!Session.isLoggedIn(this)) {
            startActivity(new Intent(this, LoginActivity.class));
            finish();
            return;
        }
        setContentView(R.layout.activity_main);
        tvHello = findViewById(R.id.tvHello);
        tvRole = findViewById(R.id.tvRole);
        tvSyncStatus = findViewById(R.id.tvSyncStatus);
        tvVersion = findViewById(R.id.tvVersion);
        btnFloat = findViewById(R.id.btnFloat);
        btnUpdate = findViewById(R.id.btnUpdate);

        String name = Session.name(this);
        tvHello.setText("Xin chào, " + (name.isEmpty() ? "giáo viên" : name));
        tvRole.setText(roleLabel(Session.role(this)) + " • " + Session.phone(this));
        tvVersion.setText("Sao Bay v" + versionName() + " • Tài khoản: " + Session.uid(this));
        tvSyncStatus.setText(prefs().getString("sync_status", "Chưa đồng bộ lần nào — bấm Đồng bộ để tải lịch + đề chấm mới nhất."));

        findViewById(R.id.btnGrade).setOnClickListener(v ->
                startActivity(new Intent(this, GraderActivity.class)));
        findViewById(R.id.btnRemind).setOnClickListener(v ->
                startActivity(new Intent(this, ClassTimesActivity.class)));
        findViewById(R.id.btnWeb).setOnClickListener(v ->
                startActivity(new Intent(this, WebActivity.class)));
        findViewById(R.id.btnSync).setOnClickListener(v -> syncNow(false));
        findViewById(R.id.btnLogout).setOnClickListener(v -> confirmLogout());
        btnFloat.setOnClickListener(v -> toggleFloat());
        btnUpdate.setOnClickListener(v -> checkUpdate(true));

        projMgr = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);

        IntentFilter f = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(dlReceiver, f, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(dlReceiver, f);
        }

        // Mo app: dong bo nhe 1 lan (lich + de) neu chua dong bo trong 1 gio
        long last = prefs().getLong("sync_at", 0);
        if (System.currentTimeMillis() - last > 3600_000L) syncNow(true);
        checkUpdate(false);
        syncFloatLabel();
    }

    @Override
    protected void onResume() {
        super.onResume();
        syncFloatLabel();
    }

    @Override
    protected void onDestroy() {
        try { unregisterReceiver(dlReceiver); } catch (Exception ignored) {}
        super.onDestroy();
    }

    private SharedPreferences prefs() {
        return getSharedPreferences("home", MODE_PRIVATE);
    }

    private String roleLabel(String role) {
        if ("admin".equals(role)) return "Quản trị viên";
        if ("bgh".equals(role)) return "Ban giám hiệu";
        if ("teacher".equals(role)) return "Giáo viên";
        return role;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    // ------------------------------------------------------------ dang xuat
    private void confirmLogout() {
        new AlertDialog.Builder(this)
                .setTitle("Đăng xuất?")
                .setMessage("Đăng xuất khỏi app Sao Bay trên máy này? Lịch nhắc giờ đã tải và kết quả chấm đã lưu vẫn còn trong máy.")
                .setPositiveButton("Đăng xuất", (d, w) -> {
                    Session.clear(this);
                    startActivity(new Intent(this, LoginActivity.class));
                    finish();
                })
                .setNegativeButton("Ở lại", null)
                .show();
    }

    // ------------------------------------------------------------ dong bo
    private void syncNow(boolean quiet) {
        if (syncing) return;
        syncing = true;
        if (!quiet) toast("Đang đồng bộ lịch báo giảng + đề chấm…");
        new Thread(() -> {
            boolean schedOk = ReminderStore.fetchSync(getApplicationContext());
            int alarms = 0;
            if (schedOk) alarms = ReminderScheduler.scheduleAll(getApplicationContext());
            int exams = ExamStore.fetchSync(getApplicationContext());
            String when = new SimpleDateFormat("HH:mm dd/MM", Locale.US).format(new Date());
            String status = "Đã đồng bộ " + when + " — lịch báo giảng: " + (schedOk ? "mới nhất (" + alarms + " báo thức)" : "chưa tải được, đang dùng bản đã lưu")
                    + " • đề chấm: " + (exams >= 0 ? exams + " đề" : "chưa tải được");
            prefs().edit().putLong("sync_at", System.currentTimeMillis()).putString("sync_status", status).apply();
            runOnUiThread(() -> {
                syncing = false;
                tvSyncStatus.setText(status);
                if (!quiet) toast(status);
            });
        }).start();
    }

    // ------------------------------------------------------------ nut noi
    private void syncFloatLabel() {
        if (btnFloat != null) {
            btnFloat.setText(FloatService.running
                    ? "📸🔴 Nút nổi chụp & ghi màn hình: ĐANG BẬT (bấm để tắt)"
                    : "📸🔴 Nút nổi chụp & ghi màn hình: TẮT");
        }
    }

    private void toggleFloat() {
        if (FloatService.running) {
            stopService(new Intent(this, FloatService.class));
            syncFloatLabel();
            toast("Đã tắt nút nổi.");
            return;
        }
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            toast("Cấp quyền 'Hiển thị trên ứng dụng khác' cho Sao Bay để nút nổi hoạt động, rồi bấm lại nút này.");
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception ignored) {}
            return;
        }
        if (projMgr == null) {
            toast("Máy không hỗ trợ quay/chụp màn hình.");
            return;
        }
        startActivityForResult(projMgr.createScreenCaptureIntent(), REQ_PROJECTION);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PROJECTION) {
            if (resultCode == RESULT_OK && data != null) {
                Intent svc = new Intent(this, FloatService.class);
                svc.putExtra("resultCode", resultCode);
                svc.putExtra("data", data);
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc);
                else startService(svc);
                syncFloatLabel();
                moveTaskToBack(true);
            } else {
                toast("Chưa cấp quyền chụp màn hình nên nút nổi chưa bật được.");
            }
        }
    }

    // ------------------------------------------------------------ cap nhat app
    private String versionName() {
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            return pi.versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    private long versionCode() {
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    private void checkUpdate(boolean manual) {
        new Thread(() -> {
            try {
                String s = Api.getText(VERSION_URL);
                JSONObject j = new JSONObject(s);
                long remote = j.optLong("versionCode", 0);
                String rname = j.optString("versionName", "");
                String url = j.optString("url", "");
                if (remote > versionCode() && !url.isEmpty()) {
                    runOnUiThread(() -> {
                        btnUpdate.setText("🔄 Cập nhật app — có bản mới " + rname + "!");
                        if (manual) {
                            new AlertDialog.Builder(this)
                                    .setTitle("Có bản Sao Bay " + rname)
                                    .setMessage(j.optString("notes", ""))
                                    .setPositiveButton("Tải & cài ngay", (d, w) -> startDownload(url, rname))
                                    .setNegativeButton("Để sau", null)
                                    .show();
                        } else {
                            toast("Có bản Sao Bay mới " + rname + " — bấm nút Cập nhật app để tải.");
                        }
                    });
                } else if (manual) {
                    runOnUiThread(() -> toast("App đã là bản mới nhất (" + versionName() + ")."));
                }
            } catch (Exception e) {
                if (manual) runOnUiThread(() -> toast("Chưa kiểm tra được bản mới (mất mạng?)."));
            }
        }).start();
    }

    private void startDownload(String url, String verName) {
        try {
            if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
                toast("Cho phép Sao Bay 'cài ứng dụng không rõ nguồn gốc' để tự cài bản mới, rồi bấm Cập nhật lại.");
                Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + getPackageName()));
                startActivity(i);
                return;
            }
            DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            if (dm == null) return;
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
            req.setTitle("Sao Bay " + verName);
            req.setDescription("Đang tải bản cập nhật Sao Bay…");
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS,
                    "SaoBay-" + verName + ".apk");
            req.setMimeType("application/vnd.android.package-archive");
            downloadId = dm.enqueue(req);
            toast("Đang tải bản " + verName + "… tải xong app sẽ tự mở trình cài đặt.");
        } catch (Exception e) {
            toast("Không tải được bản mới: " + e.getMessage());
        }
    }

    private void openInstaller(Uri uri) {
        try {
            Intent it = new Intent(Intent.ACTION_VIEW);
            it.setDataAndType(uri, "application/vnd.android.package-archive");
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(it);
        } catch (Exception e) {
            toast("Đã tải xong bản mới nhưng chưa mở được trình cài đặt: " + e.getMessage());
        }
    }
}
