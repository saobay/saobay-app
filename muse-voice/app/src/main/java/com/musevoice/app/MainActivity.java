package com.musevoice.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.admin.DevicePolicyManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.view.View;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.text.Normalizer;
import java.util.ArrayList;
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
    private static final int REQ_AUDIO = 42;
    private static final int REQ_ADMIN = 43;

    private WebView web;
    private TextView statusView;
    private TextView transcriptView;
    private Button btnMic;

    private TextToSpeech tts;
    private boolean ttsReady = false;
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

    // ------------------------------------------------------------ lifecycle
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusView = findViewById(R.id.status);
        transcriptView = findViewById(R.id.transcript);
        btnMic = findViewById(R.id.btnMic);
        web = findViewById(R.id.webview);

        customCommands = CommandsStore.load(this);
        setupWebView();
        setupTts();
        setupButtons();

        if (savedInstanceState != null) web.restoreState(savedInstanceState);
        else web.loadUrl(HOME);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Nap lai lenh tu tao (user co the vua them/sua/xoa o man hinh Lenh tu tao)
        customCommands = CommandsStore.load(this);
    }

    @Override
    protected void onDestroy() {
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
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);

        web.addJavascriptInterface(new NativeBridge(), "MuseVoiceNative");
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                // Chi nap cau noi giong noi tren trang Muse — trang SAOBAY
                // mo trong cung WebView khong bi doc linh tinh.
                if (url != null && url.contains("muse.ai")) {
                    injectBridge();
                    setStatus("Đã tải trang trò chuyện. Đăng nhập Muse (nếu được hỏi), rồi bấm “Bật giọng nói”.");
                }
            }
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
        tts = new TextToSpeech(this, status -> {
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
        if (!ttsReady || raw == null) return;
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
        String normText = stripAccents(text.toLowerCase(Locale.ROOT)).trim();

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
        String js = "window.MuseVoiceSend && window.MuseVoiceSend("
                + JSONArray.quote(text) + ");";
        web.evaluateJavascript(js, null);
        // Cho Muse tra loi: khong nghe trong luc cho + doc (mic se thu tieng doc)
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

    // ------------------------------------------------------------ UI
    private void setupButtons() {
        btnMic = findViewById(R.id.btnMic);
        btnMic.setOnClickListener(v -> setVoiceMode(!voiceMode));

        findViewById(R.id.btnStopSpeak).setOnClickListener(v -> {
            if (tts != null) tts.stop();
            speaking = false;
            setStatus("🔇 Đã dừng đọc.");
            if (voiceMode) startListening();
        });
        findViewById(R.id.btnReplay).setOnClickListener(v -> {
            if (!lastReply.isEmpty()) speak(lastReply);
            else toast("Chưa có câu trả lời nào để đọc lại.");
        });
        findViewById(R.id.btnLock).setOnClickListener(v -> lockScreen());
        findViewById(R.id.btnAdmin).setOnClickListener(v -> requestAdmin());
        findViewById(R.id.btnSaobay).setOnClickListener(v -> web.loadUrl(SAOBAY));
        findViewById(R.id.btnHome).setOnClickListener(v -> web.loadUrl(HOME));
        findViewById(R.id.btnCommands).setOnClickListener(v ->
                startActivity(new Intent(this, CustomCommandsActivity.class)));
    }

    private void setVoiceMode(boolean on) {
        voiceMode = on;
        capturing = false;
        buffer.setLength(0);
        btnMic.setText(on ? "🎙 Đang nghe… (bấm để tắt)" : "🎙 Bật giọng nói");
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
        public void speak(String text) {
            if (text == null) return;
            String clean = cleanForSpeech(text);
            if (clean.length() < 2) return;
            if (clean.equals(cleanForSpeech(lastReply))) return; // trung cau vua doc
            ui.post(() -> speak(text));
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
