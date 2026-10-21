package com.musevoice.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.net.Uri;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Base64;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.Normalizer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

/**
 * Muse Voice — giao dien tro chuyen bang giong noi voi Muse.
 *
 * Vong doi thoai (bo lenh user chot 2026-10-21):
 *  1) Nguoi dung bat "Che do giong noi" -> app nghe cho o trang thai cho.
 *  2) BAT DAU NGHE: noi "nghe nay" hoac "ok muse" -> app vao trang thai
 *     ghi lenh, gom noi dung qua nhieu luot noi vao bo dem.
 *  3) KET THUC LENH: noi "xong roi" / "gui di" / "het" / "enter"
 *     (o cuoi cau hoac noi rieng 1 luot) -> gui toan bo bo dem cho Muse.
 *  4) Muse tra loi -> cau noi (bridge JS) goi speak() -> app doc to.
 *  5) Doc xong -> tu mo luot nghe moi o trang thai cho cau goi.
 *  6) Lenh thiet bi noi ngay tren may (khong gui cho Muse):
 *     "tat man hinh", "bat/tat den pin", "tang/giam am luong",
 *     "mo SAOBAY", "ve Muse", "dung doc", "doc lai"
 *     + lenh TU TAO trong man hinh "Lenh tu tao" (CommandsStore).
 */
public class MainActivity extends Activity {

    private static final String HOME = "https://muse.ai";
    private static final String SAOBAY = "https://www.saobayhighschool.workers.dev";
    // File thong tin phien ban moi nhat tren web truong (nut Cap nhat doc file nay)
    private static final String UPDATE_JSON = SAOBAY + "/downloads/musevoice-version.json";
    private static final int REQ_AUDIO = 42;
    private static final int REQ_ADMIN = 43;
    private static final int REQ_CAMERA = 44;
    private static final int REQ_PROJECTION = 45;
    private static final int REQ_FILE_CHOOSER = 46;
    // Callback cua o chon file trong trang web (WebView) dang cho ket qua
    private ValueCallback<Uri[]> filePathCallback;
    private Uri pendingPhotoUri;
    private volatile boolean attachPending = false;

    private WebView web;
    private TextView statusView;
    private TextView transcriptView;
    private Button btnMic;
    private Button btnUpdate;
    private Button btnFloat;
    private View urlBar;
    private String pendingShotPath;
    private long pendingDownloadId = -1;

    private TextToSpeech tts;
    private boolean ttsReady = false;
    private boolean ttsIniting = false;
    private SpeechRecognizer recognizer;
    private boolean recognizerBusy = false;

    private boolean voiceMode = false;
    private boolean speaking = false;
    private String lastReply = "";
    private boolean torchOn = false;

    // Trang thai ghi lenh: false = cho cau goi ("nghe nay"/"ok muse"),
    // true = dang gom noi dung vao buffer cho den tu ket thuc.
    private boolean capturing = false;
    private final StringBuilder buffer = new StringBuilder();
    private ArrayList<CommandsStore.Cmd> customCommands = new ArrayList<>();

    private final Handler ui = new Handler(Looper.getMainLooper());

    // Khi tai xong file (dac biet la APK cap nhat) -> tu mo trinh cai dat
    private final BroadcastReceiver downloadDone = new BroadcastReceiver() {
        @Override public void onReceive(Context ctx, Intent intent) {
            long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
            if (id == -1 || id != pendingDownloadId) return;
            pendingDownloadId = -1;
            installDownloadedApk(id);
        }
    };

    // ------------------------------------------------------------ lifecycle
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusView = findViewById(R.id.status);
        transcriptView = findViewById(R.id.transcript);
        web = findViewById(R.id.webview);

        customCommands = CommandsStore.load(this);
        setupWebView();
        setupTts();
        setupButtons();

        // Nhan su kien "tai xong" cua trinh tai he thong + kiem tra ban moi (chay ngam)
        IntentFilter dlFilter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(downloadDone, dlFilter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(downloadDone, dlFilter);
        }
        checkForUpdate(false);

        // Nhac gio len lop: tai lich bao giang moi nhat + dat lai bao thuc (chay nen, khong chan giao dien)
        new Thread(() -> {
            ReminderStore.fetchSync(getApplicationContext());
            ReminderScheduler.scheduleAll(getApplicationContext());
        }).start();

        if (savedInstanceState != null) web.restoreState(savedInstanceState);
        else web.loadUrl(HOME);

        // Mo len tu nut noi kem anh chup san -> dan vao khung soan
        handleShotIntent(getIntent());
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Nap lai lenh tu tao (user co the vua them/sua/xoa o man hinh Lenh tu tao)
        customCommands = CommandsStore.load(this);
    }

    @Override
    protected void onDestroy() {
        try { unregisterReceiver(downloadDone); } catch (Exception ignored) {}
        if (recognizer != null) { recognizer.destroy(); recognizer = null; }
        if (tts != null) { tts.stop(); tts.shutdown(); tts = null; }
        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (web != null) web.saveState(outState);
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    // ------------------------------------------------------------ WebView
    private void setupWebView() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        // Dang nhap Muse bang tai khoan Google (Gmail): Google chan OAuth
        // trong WebView neu User-Agent chua dau "wv". Gia lap UA Chrome that
        // tren Android (khong "wv") de dang nhap Google di qua duoc, kem
        // cookie ben de khoi phai dang nhap lai moi lan mo app.
        s.setUserAgentString("Mozilla/5.0 (Linux; Android 14; Pixel 8) "
                + "AppleWebKit/537.36 (KHTML, like Gecko) "
                + "Chrome/126.0.0.0 Mobile Safari/537.36");
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);

        web.addJavascriptInterface(new NativeBridge(), "MuseVoiceNative");
        // SB_FILECHOOSER_20261022: BAT BUOC co onShowFileChooser. Truoc day
        // dung WebChromeClient rong -> moi lan trang chat (muse.ai) mo o
        // chon file (bam nut dinh kem cua trang, hoac cau noi JS bam menu
        // tai anh roi goi input.click()) WebView tu choi trong im lang:
        // khong mo duoc trinh chon anh, duong dinh kem tay cua trang chet
        // han, duong tu dong cua cau noi cung dut theo (o file bi huy ngay
        // khi trinh chon bi tu choi). Nay mo trinh chon file cua may that;
        // anh MuseVoice vua chup/chup man hinh da luu san trong Thu vien
        // anh nen thay chon anh moi nhat la vao khung soan.
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view,
                                             ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (filePathCallback != null) {
                    try { filePathCallback.onReceiveValue(null); } catch (Exception ignored) {}
                }
                filePathCallback = callback;
                try {
                    Intent it = (params != null) ? params.createIntent() : null;
                    if (it == null) {
                        it = new Intent(Intent.ACTION_GET_CONTENT);
                        it.addCategory(Intent.CATEGORY_OPENABLE);
                        it.setType("image/*");
                    }
                    startActivityForResult(it, REQ_FILE_CHOOSER);
                    return true;
                } catch (Exception e) {
                    filePathCallback = null;
                    return false;
                }
            }
        });
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                // Bam link APK trong trang chat -> tai bang trinh tai cua may,
                // khong de WebView mo ra trang trang xoa nhu truoc.
                if (url != null && url.toLowerCase(Locale.US).contains(".apk")) {
                    startDownload(url, null);
                    return true;
                }
                return false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                // Chi nap cau noi giong noi tren trang Muse — trang SAOBAY
                // mo trong cung WebView khong bi doc linh tinh.
                if (url != null && url.contains("muse.ai")) {
                    injectBridge();
                    setStatus("Đã tải trang trò chuyện. Đăng nhập Muse (nếu được hỏi), rồi bấm “Bật giọng nói”.");
                    // Dong bo cong tac tu doc sang cau noi
                    ui.postDelayed(() -> web.evaluateJavascript(
                            "window.__museVoiceAutoRead=" + autoReadEnabled() + ";", null), 500);
                    // Anh chup tu nut noi dang cho dan vao khung soan
                    tryAttachPendingShot();
                }
            }
        });
        // Moi link tai file trong trang (APK, PDF, Word...) di qua trinh tai
        // cua may: co thong bao tien trinh, tai xong APK thi mo trinh cai dat.
        web.setDownloadListener((url, userAgent, contentDisposition, mimetype, contentLength) -> {
            String name = null;
            if (contentDisposition != null) {
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("filename=\"?([^\";]+)\"?").matcher(contentDisposition);
                if (m.find()) name = m.group(1);
            }
            startDownload(url, name);
        });
    }

    private void injectBridge() {
        try {
            BufferedReader r = new BufferedReader(
                    new InputStreamReader(getAssets().open("voice-bridge.js")));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            r.close();
            web.evaluateJavascript(sb.toString(), null);
        } catch (Exception e) {
            setStatus("Lỗi nạp cầu nối giọng nói: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------ TTS
    private void setupTts() {
        ttsIniting = true;
        tts = new TextToSpeech(this, status -> {
            ttsIniting = false;
            if (status == TextToSpeech.SUCCESS) {
                int r = tts.setLanguage(new Locale("vi", "VN"));
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts.setLanguage(Locale.US);
                    toast("Máy chưa có giọng đọc tiếng Việt — đang dùng giọng mặc định. Có thể cài thêm trong Cài đặt > Văn bản thành giọng nói.");
                }
                ttsReady = true;
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override public void onStart(String id) { speaking = true; }
                    @Override public void onDone(String id) {
                        speaking = false;
                        // Doc xong cau tra loi -> tu mo luot nghe moi
                        if (voiceMode) ui.postDelayed(() -> startListening(), 700);
                    }
                    @Override public void onError(String id) {
                        speaking = false;
                        if (voiceMode) ui.postDelayed(() -> startListening(), 700);
                    }
                });
            } else {
                toast("Không khởi tạo được giọng đọc (TTS).");
            }
        });
    }

    /** Doc to 1 doan text (lam sach markdown truoc khi doc). */
    private void speak(String raw) {
        if (raw == null) return;
        if (!ttsReady) {
            // Ban cu: chua san sang la IM LANG nuot luon cau tra loi.
            // Nay: khoi dong lai giong doc + bao thay biet, khong nuot im lang.
            if (!ttsIniting) setupTts();
            toast("Giọng đọc chưa sẵn sàng — em đang khởi động lại, thầy bấm 🔊 Đọc lại sau ít giây nhé.");
            return;
        }
        String text = cleanForSpeech(raw);
        if (text.isEmpty()) return;
        lastReply = text;
        stopListening();
        // Cat doan dai thanh tung mach < 900 ky tu de TTS khong bi cat giua chung
        ArrayList<String> chunks = chunkText(text, 900);
        for (int i = 0; i < chunks.size(); i++) {
            int mode = (i == 0) ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD;
            tts.speak(chunks.get(i), mode, null, "mv_" + System.currentTimeMillis() + "_" + i);
        }
        setStatus("🔊 Đang đọc câu trả lời của Muse…");
    }

    private String cleanForSpeech(String s) {
        String t = s;
        t = t.replaceAll("```[\\s\\S]*?```", " (đoạn mã) ");
        t = t.replaceAll("`([^`]*)`", "$1");
        t = t.replaceAll("\\[([^\\]]*)\\]\\([^)]*\\)", "$1");
        t = t.replaceAll("[#>*_~|]", " ");
        t = t.replaceAll("https?://\\S+", " (đường liên kết) ");
        t = t.replaceAll("\\s+", " ").trim();
        return t;
    }

    private ArrayList<String> chunkText(String text, int max) {
        ArrayList<String> out = new ArrayList<>();
        String rest = text;
        while (rest.length() > max) {
            int cut = rest.lastIndexOf(". ", max);
            if (cut < max / 3) cut = rest.lastIndexOf(" ", max);
            if (cut <= 0) cut = max;
            out.add(rest.substring(0, cut + 1).trim());
            rest = rest.substring(cut + 1).trim();
        }
        if (!rest.isEmpty()) out.add(rest);
        return out;
    }

    // ------------------------------------------------------------ STT
    private void ensureAudioPermissionThenListen() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
        } else {
            startListening();
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] grants) {
        super.onRequestPermissionsResult(req, perms, grants);
        if (req == REQ_AUDIO) {
            if (grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) {
                if (voiceMode) startListening();
            } else {
                toast("Cần quyền micro để nghe giọng nói.");
                setVoiceMode(false);
            }
        }
    }

    private void startListening() {
        if (!voiceMode || speaking || recognizerBusy) return;
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            toast("Máy chưa có dịch vụ nhận diện giọng nói (cần ứng dụng Google).");
            setVoiceMode(false);
            return;
        }
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this);
            recognizer.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle b) {
                    setStatus(capturing
                            ? "🎧 Đang ghi lệnh… nói “Xong rồi” / “Gửi đi” / “Hết” / “Enter” để gửi."
                            : "🎧 Đang chờ… nói “Nghe này” hoặc “OK Muse” để bắt đầu ra lệnh.");
                }
                @Override public void onBeginningOfSpeech() { }
                @Override public void onRmsChanged(float v) { }
                @Override public void onBufferReceived(byte[] b) { }
                @Override public void onEndOfSpeech() { recognizerBusy = false; }
                @Override public void onError(int error) {
                    recognizerBusy = false;
                    if (!voiceMode || speaking) return;
                    // Loi tam thoi (khong nghe thay, mang...) -> nghe lai nhe nhang
                    if (error == SpeechRecognizer.ERROR_NO_MATCH
                            || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                            || error == SpeechRecognizer.ERROR_NETWORK
                            || error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT) {
                        ui.postDelayed(() -> startListening(), 600);
                    } else {
                        setStatus("Lỗi nghe (mã " + error + "). Vẫn đang ở chế độ giọng nói — thử nói lại.");
                        ui.postDelayed(() -> startListening(), 1200);
                    }
                }
                @Override public void onResults(Bundle results) {
                    recognizerBusy = false;
                    ArrayList<String> list = results.getStringArrayList(
                            SpeechRecognizer.RESULTS_RECOGNITION);
                    if (list != null && !list.isEmpty()) handleUtterance(list.get(0));
                    else if (voiceMode) ui.postDelayed(() -> startListening(), 400);
                }
                @Override public void onPartialResults(Bundle results) {
                    ArrayList<String> list = results.getStringArrayList(
                            SpeechRecognizer.RESULTS_RECOGNITION);
                    if (list != null && !list.isEmpty()) {
                        transcriptView.setText("🎧 Đang nghe: " + list.get(0));
                    }
                }
                @Override public void onEvent(int t, Bundle b) { }
            });
        }
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "vi-VN");
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L);
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L);
        try {
            recognizerBusy = true;
            recognizer.startListening(intent);
        } catch (Exception e) {
            recognizerBusy = false;
        }
    }

    private void stopListening() {
        if (recognizer != null) {
            try { recognizer.stopListening(); recognizer.cancel(); } catch (Exception ignored) { }
        }
        recognizerBusy = false;
    }

    // ------------------------------------------------------------ Xu ly 1 luot noi
    // Cau goi bat dau nghe (dang khong dau de so khop)
    private static final String[] WAKE_WORDS = {
            "nghe nay", "ok muse", "okay muse", "o ke muse", "ok miu", "oc muse"
    };
    // Tu ket thuc lenh (dang khong dau), xep dai truoc de uu tien khop dai
    private static final String[] END_WORDS = {
            "xong roi", "gui di", "enter", "en to", "het"
    };

    private void handleUtterance(String rawText) {
        String text = rawText == null ? "" : rawText.trim();
        if (text.isEmpty()) { if (voiceMode) startListening(); return; }
        transcriptView.setText("🗣 Bạn nói: " + text);
        String normText = stripAccents(text.toLowerCase(Locale.ROOT)).trim();

        if (!capturing) {
            // --- Trang thai cho: tim cau goi ---
            String after = afterWakeWord(text, normText);
            if (after != null) {
                capturing = true;
                buffer.setLength(0);
                setStatus("👂 Đang ghi lệnh… cứ nói tiếp, kết thúc bằng “Xong rồi” / “Gửi đi” / “Hết” / “Enter”.");
                speakShort("Em nghe đây.");
                // Phan noi tiep ngay sau cau goi (cung 1 luot) cung la noi dung lenh
                String rest = after.trim();
                if (!rest.isEmpty()) {
                    // Cho TTS "Em nghe day" doc xong roi xu ly phan duoi nhu dang ghi
                    ui.postDelayed(() -> processCapturing(rest), 1200);
                }
                return;
            }
            // Chua co cau goi: van cho phep noi thang lenh thiet bi / lenh tu tao
            if (handleDeviceCommand(normText)) return;
            if (handleCustomCommand(normText)) return;
            setStatus("🎧 Đang chờ câu gọi “Nghe này” / “OK Muse”… (vừa nghe: “" + text + "”)");
            if (voiceMode) ui.postDelayed(this::startListening, 300);
            return;
        }

        processCapturing(text);
    }

    /** Neu luot noi co cau goi: tra ve phan con lai sau cau goi; khong co: null. */
    private String afterWakeWord(String original, String normText) {
        for (String wake : WAKE_WORDS) {
            int idx = normText.indexOf(wake);
            if (idx >= 0) {
                int end = idx + wake.length();
                // Anh xa chi so tu ban khong dau sang ban goc (STT tra chu
                // co dau dang 1 ky tu/ chu nen do dai gan nhu bang nhau)
                if (end <= original.length()) return original.substring(end);
                return "";
            }
        }
        return null;
    }

    /** Xu ly 1 luot noi khi dang o trang thai ghi lenh. */
    private void processCapturing(String text) {
        // Bo dau cau thua o cuoi (may nhan giong hay tu them "." / "?" lam
        // truot khop lenh "gui di" — mot nguyen nhan "kho gui" da gap).
        String normText = stripAccents(text.toLowerCase(Locale.ROOT)).trim()
                .replaceAll("[.!?…]+$", "").trim();

        // Tim tu ket thuc: bang ca cau, hoac o cuoi cau
        String endHit = null;
        for (String end : END_WORDS) {
            if (normText.equals(end) || normText.endsWith(" " + end)) { endHit = end; break; }
        }
        if (endHit != null) {
            int keep = normText.length() - endHit.length();
            String head = keep > 0 && keep <= text.length() ? text.substring(0, keep).trim() : "";
            head = head.replaceAll("[,;:]+$", "").trim();
            if (!head.isEmpty()) appendToBuffer(head);
            finalizeBuffer();
            return;
        }

        appendToBuffer(text);
        setStatus("📝 Đã ghi: “" + buffer.toString().trim()
                + "” — nói tiếp hoặc nói “Gửi đi” để gửi.");
        if (voiceMode) ui.postDelayed(this::startListening, 300);
    }

    private void appendToBuffer(String piece) {
        if (buffer.length() > 0) buffer.append(' ');
        buffer.append(piece.trim());
    }

    /** Chot lenh: chay lenh thiet bi / lenh tu tao neu khop, con lai gui Muse. */
    private void finalizeBuffer() {
        String full = buffer.toString().trim();
        buffer.setLength(0);
        capturing = false;
        if (full.isEmpty()) {
            setStatus("🎧 Chưa có nội dung lệnh. Nói “Nghe này” rồi đọc nội dung nhé.");
            if (voiceMode) ui.postDelayed(this::startListening, 400);
            return;
        }
        transcriptView.setText("✅ Lệnh hoàn chỉnh: " + full);
        String normFull = stripAccents(full.toLowerCase(Locale.ROOT)).trim();
        if (handleDeviceCommand(normFull)) return;
        if (handleCustomCommand(normFull)) return;
        sendToClaude(full);
    }

    /** Lenh TU TAO: khop cau noi voi phrase da luu -> chay hanh dong tuong ung. */
    private boolean handleCustomCommand(String normText) {
        for (CommandsStore.Cmd c : customCommands) {
            String trigger = c.phraseNorm();
            if (trigger.isEmpty()) continue;
            if (normText.equals(trigger) || normText.contains(trigger)) {
                runCustomCommand(c);
                return true;
            }
        }
        return false;
    }

    private void runCustomCommand(CommandsStore.Cmd c) {
        setStatus("⭐ Lệnh tự tạo: “" + c.phrase + "” → " + c.actionLabel());
        switch (c.action) {
            case "url":
                web.loadUrl(c.param);
                speakShort("Đã mở trang web.");
                break;
            case "send":
                sendToClaude(c.param);
                break;
            case "speak":
                speak(c.param);
                break;
            case "lock":
                speakShort("Đã tắt màn hình.");
                ui.postDelayed(this::lockScreen, 900);
                break;
            case "torch_on":
                setTorch(true); speakShort("Đã bật đèn pin.");
                break;
            case "torch_off":
                setTorch(false); speakShort("Đã tắt đèn pin.");
                break;
            case "vol_up":
                adjustVolume(true); speakShort("Đã tăng âm lượng.");
                break;
            case "vol_down":
                adjustVolume(false); speakShort("Đã giảm âm lượng.");
                break;
            default:
                if (voiceMode) ui.postDelayed(this::startListening, 400);
                break;
        }
    }

    private boolean handleDeviceCommand(String n) {
        if (n.contains("tat man hinh") || n.contains("khoa man hinh")) {
            speakShort("Đã tắt màn hình.");
            ui.postDelayed(this::lockScreen, 900);
            return true;
        }
        if (n.contains("dung doc") || n.contains("im lang") || n.contains("dung noi")) {
            if (tts != null) tts.stop();
            speaking = false;
            setStatus("🔇 Đã dừng đọc.");
            if (voiceMode) ui.postDelayed(this::startListening, 400);
            return true;
        }
        if (n.contains("doc lai")) {
            if (!lastReply.isEmpty()) speak(lastReply);
            else speakShort("Chưa có câu trả lời nào để đọc lại.");
            return true;
        }
        if (n.contains("bat den pin") || n.contains("mo den pin")) {
            setTorch(true); speakShort("Đã bật đèn pin."); return true;
        }
        if (n.contains("tat den pin")) {
            setTorch(false); speakShort("Đã tắt đèn pin."); return true;
        }
        if (n.contains("mo saobay") || n.contains("mo web truong")) {
            web.loadUrl(SAOBAY);
            speakShort("Đã mở web trường SAOBAY.");
            return true;
        }
        if (n.contains("ve muse") || n.contains("ve trang tro chuyen") || n.contains("mo muse")) {
            web.loadUrl(HOME);
            speakShort("Đã về trang trò chuyện với Muse.");
            return true;
        }
        if (n.contains("tang am luong")) {
            adjustVolume(true); speakShort("Đã tăng âm lượng."); return true;
        }
        if (n.contains("giam am luong")) {
            adjustVolume(false); speakShort("Đã giảm âm lượng."); return true;
        }
        return false;
    }

    private void speakShort(String text) {
        // Loi xac nhan ngan: doc xong lai nghe tiep neu dang o che do giong noi
        if (ttsReady) {
            stopListening();
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "mv_short");
        } else if (voiceMode) {
            ui.postDelayed(this::startListening, 500);
        }
    }

    private void sendToClaude(String text) {
        setStatus("✍️ Đang gửi cho Muse: “" + text + "”");
        stopListening();
        // Kiem tra cau noi da nap vao trang chua; chua co thi nap lai roi gui.
        // (Ban cu: cau noi chua nap la lenh gui bien mat trong im lang.)
        web.evaluateJavascript("typeof window.MuseVoiceSend", value -> {
            if (value != null && value.contains("function")) {
                doSend(text);
            } else {
                injectBridge();
                ui.postDelayed(() -> web.evaluateJavascript("typeof window.MuseVoiceSend", v2 -> {
                    if (v2 != null && v2.contains("function")) {
                        doSend(text);
                    } else {
                        new NativeBridge().onSendFailed(text);
                    }
                }), 900);
            }
        });
        // Cho Muse tra loi: khong nghe trong luc cho + doc (mic se thu tieng doc)
    }

    private void doSend(String text) {
        String js = "window.MuseVoiceSend(" + JSONObject.quote(text) + ");";
        web.evaluateJavascript(js, null);
    }

    // ------------------------------------------------------------ Thiet bi
    private void lockScreen() {
        DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        ComponentName admin = new ComponentName(this, AdminReceiver.class);
        if (dpm != null && dpm.isAdminActive(admin)) {
            dpm.lockNow();
        } else {
            toast("Chưa có quyền tắt màn hình — bấm nút “⚙️ Quyền tắt màn hình” và bật cho Muse Voice trước.");
            requestAdmin();
        }
    }

    private void requestAdmin() {
        ComponentName admin = new ComponentName(this, AdminReceiver.class);
        Intent intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
        intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin);
        intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                getString(R.string.admin_desc));
        startActivityForResult(intent, REQ_ADMIN);
    }

    private void setTorch(boolean on) {
        try {
            CameraManager cm = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            if (cm != null && cm.getCameraIdList().length > 0) {
                cm.setTorchMode(cm.getCameraIdList()[0], on);
                torchOn = on;
            }
        } catch (Exception e) {
            toast("Không bật/tắt được đèn pin: " + e.getMessage());
        }
    }

    private void adjustVolume(boolean up) {
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (am != null) {
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                    up ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER,
                    AudioManager.FLAG_SHOW_UI);
        }
    }

    // ------------------------------------------------------------ Anh: camera / chup man hinh
    private void takePhoto() {
        try {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Images.Media.DISPLAY_NAME,
                    "MuseVoice_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".jpg");
            cv.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            pendingPhotoUri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
            if (pendingPhotoUri == null) { toast("Không tạo được nơi lưu ảnh."); return; }
            Intent it = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            it.putExtra(MediaStore.EXTRA_OUTPUT, pendingPhotoUri);
            it.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if (it.resolveActivity(getPackageManager()) != null) {
                startActivityForResult(it, REQ_CAMERA);
            } else {
                toast("Máy không có ứng dụng camera.");
            }
        } catch (Exception e) {
            toast("Không mở được camera: " + e.getMessage());
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_FILE_CHOOSER) {
            // Tra file thay vua chon ve cho o chon file cua trang chat
            if (filePathCallback != null) {
                Uri[] results = null;
                if (resultCode == RESULT_OK && data != null) {
                    ClipData clip = data.getClipData();
                    if (clip != null && clip.getItemCount() > 0) {
                        results = new Uri[clip.getItemCount()];
                        for (int i = 0; i < clip.getItemCount(); i++) {
                            results[i] = clip.getItemAt(i).getUri();
                        }
                    } else if (data.getData() != null) {
                        results = new Uri[]{ data.getData() };
                    }
                }
                try { filePathCallback.onReceiveValue(results); } catch (Exception ignored) {}
                filePathCallback = null;
            }
        }
        if (requestCode == REQ_CAMERA) {
            Uri uri = pendingPhotoUri;
            pendingPhotoUri = null;
            if (resultCode == RESULT_OK && uri != null) {
                byte[] bytes = readScaledJpeg(uri, 1600, 80);
                if (bytes != null) attachBytesToChat(bytes, "photo.jpg");
                else toast("Không đọc được ảnh vừa chụp.");
            }
        }
        if (requestCode == REQ_PROJECTION) {
            if (resultCode == RESULT_OK && data != null) {
                Intent svc = new Intent(this, FloatService.class);
                svc.putExtra("resultCode", resultCode);
                svc.putExtra("data", data);
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc);
                else startService(svc);
                if (btnFloat != null) btnFloat.setText("📸 Nút nổi: ĐANG BẬT (bấm để tắt)");
                toast("Nút nổi đã bật — em ẩn xuống đây. Thầy mở app khác, bấm nút 📸 nổi là chụp màn hình và gửi thẳng vào khung soạn Muse.");
                moveTaskToBack(true);
            } else {
                toast("Chưa được cấp quyền quay màn hình nên chưa bật được nút nổi.");
            }
        }
    }

    // ------------------------------------------------------------ Nut noi chup man hinh
    private void toggleFloat() {
        if (FloatService.running) {
            stopService(new Intent(this, FloatService.class));
            if (btnFloat != null) btnFloat.setText("📸 Nút nổi");
            toast("Đã tắt nút nổi.");
            return;
        }
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            toast("Thầy cấp quyền 'Hiển thị trên ứng dụng khác' cho Muse Voice trước nhé — cấp xong bấm lại nút Nút nổi.");
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
            return;
        }
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        if (mpm == null) { toast("Máy không hỗ trợ quay màn hình."); return; }
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_PROJECTION);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleShotIntent(intent);
    }

    /** Nhan anh chup tu nut noi (FloatService gui ve kem duong dan file). */
    private void handleShotIntent(Intent intent) {
        if (intent == null) return;
        String path = intent.getStringExtra("shot_path");
        if (path == null) return;
        intent.removeExtra("shot_path");
        pendingShotPath = path;
        tryAttachPendingShot();
    }

    /** Dan anh dang cho vao khung soan khi dang o trang chat Muse
     *  (luong chinh hay phu deu duoc: WebView van nguyen trang do). */
    private void tryAttachPendingShot() {
        if (pendingShotPath == null || web == null) return;
        String url = web.getUrl();
        if (url != null && url.contains("muse.ai")) {
            final String path = pendingShotPath;
            pendingShotPath = null;
            ui.postDelayed(() -> attachShotFile(path), 600);
        }
    }

    private void attachShotFile(String path) {
        try {
            java.io.File f = new java.io.File(path);
            byte[] bytes = readScaledJpeg(Uri.fromFile(f), 1600, 85);
            if (bytes == null) {
                toast("Không đọc được ảnh vừa chụp.");
                return;
            }
            saveToGallery(bytes, "MuseVoice_shot_" + new SimpleDateFormat(
                    "yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".jpg");
            attachBytesToChat(bytes, "screenshot.jpg");
        } catch (Exception e) {
            toast("Không đính kèm được ảnh: " + e.getMessage());
        }
    }

    /** Doc anh tu Uri, thu nho neu lon, nen JPEG -> byte[]. */
    private byte[] readScaledJpeg(Uri uri, int maxDim, int quality) {
        try {
            InputStream is = getContentResolver().openInputStream(uri);
            if (is == null) return null;
            Bitmap bmp = BitmapFactory.decodeStream(is);
            is.close();
            if (bmp == null) return null;
            int w = bmp.getWidth(), h = bmp.getHeight();
            int m = Math.max(w, h);
            if (m > maxDim) {
                float s = (float) maxDim / m;
                bmp = Bitmap.createScaledBitmap(bmp, Math.round(w * s), Math.round(h * s), true);
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.JPEG, quality, bos);
            return bos.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    /** Chup vung hien thi cua WebView (dung nhu thay nhin tren man hinh). */
    private void captureScreen() {
        try {
            if (web == null || web.getWidth() == 0 || web.getHeight() == 0) {
                toast("Trang chưa tải xong để chụp.");
                return;
            }
            Bitmap bmp = Bitmap.createBitmap(web.getWidth(), web.getHeight(), Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bmp);
            web.draw(canvas);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.JPEG, 85, bos);
            byte[] bytes = bos.toByteArray();
            // Luu vao thu vien anh: thay dinh kem tay duoc neu tu dong that bai
            saveToGallery(bytes, "MuseVoice_shot_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".jpg");
            attachBytesToChat(bytes, "screenshot.jpg");
        } catch (Exception e) {
            toast("Không chụp được màn hình: " + e.getMessage());
        }
    }

    private void saveToGallery(byte[] bytes, String name) {
        try {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Images.Media.DISPLAY_NAME, name);
            cv.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) return;
            OutputStream os = getContentResolver().openOutputStream(uri);
            if (os != null) { os.write(bytes); os.close(); }
        } catch (Exception ignored) {}
    }

    /** Dua anh (base64) sang cau noi JS de gan vao o dinh kem cua trang chat. */
    private void attachBytesToChat(byte[] bytes, final String filename) {
        final String b64 = Base64.encodeToString(bytes, Base64.NO_WRAP);
        attachPending = true;
        setStatus("🖼 Đang đính kèm ảnh vào ô chat…");
        web.evaluateJavascript("typeof window.MuseVoiceAttach", val -> {
            if (val != null && val.contains("function")) {
                doAttach(b64, filename);
            } else {
                injectBridge();
                ui.postDelayed(() -> doAttach(b64, filename), 900);
            }
        });
        // Cho theo doi: sau 6s khong co ket qua tra ve thi bao loi ro rang
        ui.postDelayed(() -> {
            if (attachPending) {
                attachPending = false;
                setStatus("⚠️ Không đính kèm tự động được. Ảnh đã lưu trong thư viện Ảnh — bấm nút đính kèm của trang chat và chọn ảnh MuseVoice mới nhất.");
                speakShort("Chưa đính kèm tự động được. Ảnh đã lưu trong thư viện ảnh.");
            }
        }, 9000);
    }

    private void doAttach(String b64, String filename) {
        String js = "window.MuseVoiceAttach(" + JSONObject.quote(b64) + ", " + JSONObject.quote(filename) + ");";
        web.evaluateJavascript(js, null);
    }

    // ------------------------------------------------------------ UI
    private void setupButtons() {
        // Nut noi chup man hinh app khac (thay cho cac nut am thanh da bo):
        // bat -> cap quyen ve-tren-app-khac + quay man hinh (moi quyen 1 lan)
        // -> app tu an xuong, nut 📸 noi lo lung; cham nut noi la chup man
        // hinh app dang xem, quay ve day va dan anh vao khung soan Muse.
        btnFloat = findViewById(R.id.btnFloat);
        if (FloatService.running) btnFloat.setText("📸 Nút nổi: ĐANG BẬT (bấm để tắt)");
        btnFloat.setOnClickListener(v -> toggleFloat());
        findViewById(R.id.btnCamera).setOnClickListener(v -> takePhoto());
        findViewById(R.id.btnShot).setOnClickListener(v -> captureScreen());
        findViewById(R.id.btnRemind).setOnClickListener(v ->
                startActivity(new Intent(this, ClassTimesActivity.class)));
        findViewById(R.id.btnLock).setOnClickListener(v -> lockScreen());
        findViewById(R.id.btnAdmin).setOnClickListener(v -> requestAdmin());
        findViewById(R.id.btnSaobay).setOnClickListener(v -> { hideUrlBar(); web.loadUrl(SAOBAY); });
        findViewById(R.id.btnHome).setOnClickListener(v -> { hideUrlBar(); web.loadUrl(HOME); });
        findViewById(R.id.btnCommands).setOnClickListener(v ->
                startActivity(new Intent(this, CustomCommandsActivity.class)));

        // Muc "Cai dat" thu gon: nut bat/tat nam o CUOI dai nut cuon ngang
        // (thay chot: de dong rieng ton dien tich); bam vao xo hang nut phu
        // chua Quyen tat man hinh + Lenh tu tao + Thu giong doc.
        View settingsRow = findViewById(R.id.settingsRow);
        Button settingsToggle = findViewById(R.id.btnSettingsToggle);
        settingsToggle.setOnClickListener(v -> {
            boolean open = settingsRow.getVisibility() == View.VISIBLE;
            settingsRow.setVisibility(open ? View.GONE : View.VISIBLE);
            settingsToggle.setText(open ? "⚙️ Cài đặt ▸" : "⚙️ Cài đặt ▾");
        });

        // Nut "Cap nhat": tu kiem tra ban moi tren web truong, co thi tai
        // ve va mo trinh cai dat luon - thay chi viec bam "Cai dat".
        btnUpdate = findViewById(R.id.btnUpdate);
        btnUpdate.setOnClickListener(v -> checkForUpdate(true));

        // Nut "Thu giong doc" (trong muc Cai dat): doc thu 1 cau bang dung
        // duong doc cau tra loi + kiem tra cau noi trang chat, de thay tu
        // chan benh khi khong nghe thay tieng doc.
        findViewById(R.id.btnTtsTest).setOnClickListener(v -> {
            if (!ttsReady) {
                if (!ttsIniting) setupTts();
                toast("Giọng đọc đang khởi động — thầy chờ 2 giây rồi bấm lại nút này.");
                return;
            }
            speak("Em là Muse. Nếu thầy nghe rõ câu này, thì giọng đọc trên máy chạy tốt.");
            web.evaluateJavascript("typeof window.MuseVoiceReadLast", val -> toast(
                    (val != null && val.contains("function"))
                            ? "Cầu nối trang chat: đã nạp ✓"
                            : "Cầu nối trang chat: CHƯA nạp — thầy bấm 💬 Về Muse rồi thử lại"));
        });

        // Cong tac "Tu doc tra loi" (trong muc Cai dat): cau noi chi tu doc
        // khi co nay dang BAT; thay go tay gui tin nhan cau noi cung canh.
        Button btnAutoRead = findViewById(R.id.btnAutoRead);
        Runnable syncAutoRead = () -> btnAutoRead.setText(
                autoReadEnabled() ? "🔊 Tự đọc trả lời: BẬT" : "🔊 Tự đọc trả lời: TẮT");
        syncAutoRead.run();
        btnAutoRead.setOnClickListener(v -> {
            boolean on = !autoReadEnabled();
            getSharedPreferences("musevoice", MODE_PRIVATE).edit()
                    .putBoolean("auto_read", on).apply();
            syncAutoRead.run();
            web.evaluateJavascript("window.__museVoiceAutoRead=" + on + ";", null);
            toast(on ? "Đã BẬT tự đọc câu trả lời của Muse."
                     : "Đã TẮT tự đọc câu trả lời.");
        });

        // Nut "Web": hien/an thanh dia chi o day man hinh (kieu Coc Coc),
        // go dia chi roi bam "Di" (hoac phim Go tren ban phim) de mo trang.
        urlBar = findViewById(R.id.urlBar);
        EditText etUrl = findViewById(R.id.etUrl);
        findViewById(R.id.btnWeb).setOnClickListener(v -> {
            boolean open = urlBar.getVisibility() == View.VISIBLE;
            urlBar.setVisibility(open ? View.GONE : View.VISIBLE);
            if (!open) {
                etUrl.requestFocus();
                InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(etUrl, InputMethodManager.SHOW_IMPLICIT);
            }
        });
        findViewById(R.id.btnGo).setOnClickListener(v -> goToUrl(etUrl));
        etUrl.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                goToUrl(etUrl);
                return true;
            }
            return false;
        });
    }

    private boolean autoReadEnabled() {
        return getSharedPreferences("musevoice", MODE_PRIVATE)
                .getBoolean("auto_read", true);
    }

    /** Mo dia chi thay go o thanh Web: co http(s) thi mo thang; chi co ten
     *  mien thi them https://; con lai coi nhu tu khoa tim kiem Google. */
    private void goToUrl(EditText etUrl) {
        String q = etUrl.getText().toString().trim();
        if (q.isEmpty()) return;
        String url;
        if (q.startsWith("http://") || q.startsWith("https://")) {
            url = q;
        } else if (!q.contains(" ") && q.contains(".")) {
            url = "https://" + q;
        } else {
            try {
                url = "https://www.google.com/search?q=" + URLEncoder.encode(q, "UTF-8");
            } catch (Exception e) {
                url = "https://www.google.com/search?q=" + q;
            }
        }
        web.loadUrl(url);
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(etUrl.getWindowToken(), 0);
        etUrl.clearFocus();
        hideUrlBar();
    }

    /** Cat thanh dia chi Web di (thay yeu cau: nhap xong/thoat Web la phai
     *  cat o duong dan, khoi ton dien tich). */
    private void hideUrlBar() {
        if (urlBar != null) urlBar.setVisibility(View.GONE);
    }

    // ------------------------------------------------------------ Cap nhat + tai file
    /** Doc file phien ban tren web truong; ban moi hon thi (khi thay bam nut)
     *  tai ve ngay va mo trinh cai dat. Chay ngam luc mo app chi doi nhan nut. */
    private void checkForUpdate(boolean manual) {
        if (manual) toast("Đang kiểm tra bản mới…");
        new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(UPDATE_JSON).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
                r.close();
                JSONObject j = new JSONObject(sb.toString());
                final int remoteCode = j.optInt("versionCode", 0);
                final String remoteName = j.optString("versionName", "");
                final String apkUrl = j.optString("url", "");
                final int myCode = getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
                ui.post(() -> {
                    if (remoteCode > myCode && !apkUrl.isEmpty()) {
                        if (btnUpdate != null) btnUpdate.setText("🔄 Cập nhật (" + remoteName + ") ⬆");
                        if (manual) {
                            startDownload(apkUrl, "MuseVoice-" + remoteName + ".apk");
                        } else {
                            toast("Có bản Muse Voice mới (" + remoteName + ") — bấm nút 🔄 Cập nhật để cài.");
                        }
                    } else if (manual) {
                        toast("Thầy đang dùng bản mới nhất rồi (" + remoteName + ").");
                        speakShort("Thầy đang dùng bản mới nhất rồi.");
                    }
                });
            } catch (Exception e) {
                if (manual) ui.post(() -> toast("Chưa kiểm tra được (mất mạng?) — thầy thử lại sau nhé."));
            }
        }).start();
    }

    /** Tai file bang trinh tai cua he thong; APK tai xong tu mo trinh cai dat. */
    private void startDownload(String url, String suggestedName) {
        try {
            boolean isApk = url.toLowerCase(Locale.US).contains(".apk");
            // Android 8+: app phai duoc cap quyen "cai ung dung" rieng (1 lan duy nhat)
            if (isApk && Build.VERSION.SDK_INT >= 26
                    && !getPackageManager().canRequestPackageInstalls()) {
                toast("Thầy cấp quyền cài ứng dụng cho Muse Voice 1 lần này nhé — cấp xong bấm lại nút Cập nhật.");
                speakShort("Thầy cấp quyền cài ứng dụng một lần này giúp em nhé.");
                Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + getPackageName()));
                startActivity(i);
                return;
            }
            String name = suggestedName;
            if (name == null || name.isEmpty()) {
                name = Uri.parse(url).getLastPathSegment();
                if (name == null || name.isEmpty()) name = isApk ? "MuseVoice.apk" : "tep-tai-ve";
            }
            DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            if (dm == null) { toast("Máy không có trình tải xuống."); return; }
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
            req.setTitle("Muse Voice — " + name);
            req.setDescription("Đang tải xuống…");
            if (isApk) req.setMimeType("application/vnd.android.package-archive");
            req.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, name);
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            pendingDownloadId = dm.enqueue(req);
            if (isApk) {
                toast("Đang tải bản cập nhật — tải xong máy tự mở trình cài đặt, thầy bấm Cài đặt nhé.");
                speakShort("Đang tải bản cập nhật. Tải xong thầy bấm cài đặt giúp em.");
            } else {
                toast("Đang tải file " + name + "…");
            }
        } catch (Exception e) {
            toast("Không tải được: " + e.getMessage());
        }
    }

    /** Mo trinh cai dat Android cho APK vua tai xong. */
    private void installDownloadedApk(long downloadId) {
        try {
            DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            if (dm == null) return;
            Uri uri = dm.getUriForDownloadedFile(downloadId);
            if (uri == null) {
                toast("Đã tải xong bản cập nhật — thầy mở thông báo tải xuống để cài nhé.");
                return;
            }
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(i);
        } catch (Exception e) {
            toast("Không mở được trình cài đặt: " + e.getMessage());
        }
    }

    private void setVoiceMode(boolean on) {
        voiceMode = on;
        capturing = false;
        buffer.setLength(0);
        if (btnMic != null) btnMic.setText(on ? "🎙 Đang nghe… (bấm để tắt)" : "🎙 Bật giọng nói");
        if (on) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            setStatus("🎙 Chế độ giọng nói: BẬT. Nói “Nghe này” hoặc “OK Muse” để bắt đầu ra lệnh.");
            speakShort("Chế độ giọng nói đã bật. Thầy nói nghe này để bắt đầu.");
            ensureAudioPermissionThenListen();
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            stopListening();
            if (tts != null) tts.stop();
            speaking = false;
            setStatus("🎙 Chế độ giọng nói: TẮT.");
        }
    }

    private void setStatus(String s) {
        ui.post(() -> statusView.setText(s));
    }

    private void toast(String s) {
        ui.post(() -> Toast.makeText(this, s, Toast.LENGTH_LONG).show());
    }

    private String stripAccents(String s) {
        String n = Normalizer.normalize(s, Normalizer.Form.NFD);
        n = n.replaceAll("\\p{M}+", "");
        return n.replace('đ', 'd').replace('Đ', 'D');
    }

    // ------------------------------------------------------------ Cau noi JS -> native
    public class NativeBridge {
        @JavascriptInterface
        public void noteReply(String text) {
            if (text != null) lastReply = text;
        }

        @JavascriptInterface
        public void speak(String text) {
            if (text == null) return;
            String clean = cleanForSpeech(text);
            if (clean.length() < 2) return;
            // KHONG duoc so sanh voi lastReply de bo qua: noteReply() ghi
            // lastReply TRUOC khi speak() duoc goi (cau noi v2) -> moi cau
            // tra loi deu bi coi la "trung cau vua doc" va khong bao gio doc.
            // Chong doc trung da co o tang canh tra loi (expect chi ban 1 lan).
            ui.post(() -> speak(text));
        }

        @JavascriptInterface
        public void reportNoReply() {
            ui.post(() -> {
                setStatus("🔊 Chưa tìm thấy câu trả lời nào trên trang để đọc.");
                speakShort("Chưa tìm thấy câu trả lời nào trên trang.");
            });
        }

        @JavascriptInterface
        public void onAttachResult(boolean ok, String how) {
            attachPending = false;
            ui.post(() -> {
                if (ok) {
                    setStatus("🖼 Đã đính kèm ảnh vào ô chat — thầy kiểm tra rồi bấm Gửi trên trang chat.");
                    speakShort("Đã đính kèm ảnh vào ô chat.");
                } else {
                    setStatus("⚠️ Chưa đính kèm tự động được. Ảnh đã lưu trong thư viện Ảnh — bấm nút đính kèm của trang chat và chọn ảnh MuseVoice mới nhất.");
                    toast("Chưa đính kèm được (mã: " + how + ") — ảnh đã lưu trong thư viện Ảnh, thầy đính kèm tay giúp em.");
                    speakShort("Chưa đính kèm tự động được. Ảnh đã lưu trong thư viện ảnh.");
                }
            });
        }

        @JavascriptInterface
        public void onSendOk(String text) {
            setStatus("✅ Đã gửi cho Muse. Đang chờ Muse trả lời…");
        }

        @JavascriptInterface
        public void onSendFailed(String text) {
            ui.post(() -> {
                setStatus("⚠️ Chưa gửi tự động được. Hãy dán tay vào ô chat của Muse.");
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("Chưa gửi tự động được")
                        .setMessage("Trang Muse chưa nhận được tin nhắn tự động.\n\nNội dung: “" + text + "”\n\nBấm “Sao chép” rồi dán vào ô chat của Muse và bấm Gửi.")
                        .setPositiveButton("Sao chép", (d, w) -> {
                            ClipboardManager cb = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                            if (cb != null) cb.setPrimaryClip(ClipData.newPlainText("muse-voice", text));
                            toast("Đã sao chép. Dán vào ô chat của Muse nhé.");
                        })
                        .setNegativeButton("Đóng", null)
                        .show();
                if (voiceMode) ui.postDelayed(() -> startListening(), 800);
            });
        }
    }
}
