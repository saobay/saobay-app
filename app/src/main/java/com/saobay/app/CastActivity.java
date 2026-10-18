package com.saobay.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;
import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpReceiver;
import org.webrtc.DataChannel;
import org.webrtc.ScreenCapturerAndroid;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Chiếu màn hình điện thoại lên máy tính qua WebRTC.
 * Luồng: nhập mã phòng (PC tạo trước ở live/cast.html) -> xin quyền
 * MediaProjection -> tạo offer -> POST /api/v2/live/offer ->
 * poll /api/v2/live/answer -> setRemoteDescription -> stream.
 */
public class CastActivity extends Activity {

    private static final int REQ_CAPTURE = 1001;
    private static final String API_BASE = "https://www.saobayhighschool.workers.dev/api/v2/live";

    private static volatile boolean webrtcInitialized = false;

    private EditText etRoom;
    private Button btnStart;
    private Button btnStop;
    private TextView tvStatus;

    private String room;
    private Intent screenCaptureIntent;

    private EglBase eglBase;
    private PeerConnectionFactory factory;
    private PeerConnection pc;
    private ScreenCapturerAndroid capturer;
    private VideoSource videoSource;
    private SurfaceTextureHelper surfaceHelper;

    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Runnable pollRunnable;
    private volatile boolean polling = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_cast);

        etRoom = findViewById(R.id.et_room);
        btnStart = findViewById(R.id.btn_start);
        btnStop = findViewById(R.id.btn_stop);
        tvStatus = findViewById(R.id.tv_status);

        btnStart.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onStartClicked(); }
        });
        btnStop.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                stopCasting();
                setStatus("Đã dừng chiếu.");
            }
        });
    }

    private void onStartClicked() {
        room = etRoom.getText().toString().trim().toUpperCase();
        if (room.length() != 6) {
            Toast.makeText(this, "Nhập mã phòng 6 ký tự (xem trên màn hình máy tính)",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        setStatus("Đang kiểm tra phòng...");
        net.execute(new Runnable() {
            @Override public void run() {
                try {
                    String resp = httpGet(API_BASE + "/status?room="
                            + URLEncoder.encode(room, "UTF-8"));
                    final boolean ok = new JSONObject(resp).optBoolean("ok", false);
                    mainHandler.post(new Runnable() {
                        @Override public void run() {
                            if (ok) {
                                requestCapturePermission();
                            } else {
                                setStatus("Không tìm thấy phòng. Kiểm tra lại mã.");
                            }
                        }
                    });
                } catch (final Exception e) {
                    mainHandler.post(new Runnable() {
                        @Override public void run() {
                            setStatus("Lỗi kết nối máy chủ: " + e.getMessage());
                        }
                    });
                }
            }
        });
    }

    private void requestCapturePermission() {
        MediaProjectionManager mpm =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_CAPTURE) {
            if (resultCode == RESULT_OK && data != null) {
                screenCaptureIntent = data;
                startCasting();
            } else {
                setStatus("Bạn đã từ chối quyền quay màn hình.");
            }
        }
    }

    private void startCasting() {
        setStatus("Đang khởi tạo...");
        btnStart.setVisibility(View.GONE);
        btnStop.setVisibility(View.VISIBLE);
        etRoom.setEnabled(false);
        try {
            initWebRTC();
            createOffer();
        } catch (final Exception e) {
            setStatus("Lỗi: " + e.getMessage());
            stopCasting();
        }
    }

    private void initWebRTC() {
        if (!webrtcInitialized) {
            PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(this)
                            .createInitializationOptions());
            webrtcInitialized = true;
        }

        eglBase = EglBase.create();
        factory = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(new DefaultVideoEncoderFactory(
                        eglBase.getEglBaseContext(), true, true))
                .setVideoDecoderFactory(new DefaultVideoDecoderFactory(
                        eglBase.getEglBaseContext()))
                .createPeerConnectionFactory();

        capturer = new ScreenCapturerAndroid(screenCaptureIntent,
                new MediaProjection.Callback() {
                    @Override public void onStop() {
                        mainHandler.post(new Runnable() {
                            @Override public void run() {
                                setStatus("Đã dừng chia sẻ màn hình.");
                                stopCasting();
                            }
                        });
                    }
                });

        videoSource = factory.createVideoSource(capturer.isScreencast());
        surfaceHelper = SurfaceTextureHelper.create(
                "ScreenCaptureThread", eglBase.getEglBaseContext());
        capturer.initialize(surfaceHelper, getApplicationContext(),
                videoSource.getCapturerObserver());
        capturer.startCapture(720, 1280, 30);

        VideoTrack videoTrack = factory.createVideoTrack("ARDAMSv0", videoSource);

        List<PeerConnection.IceServer> iceServers = new ArrayList<PeerConnection.IceServer>();
        iceServers.add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302")
                .createIceServer());
        PeerConnection.RTCConfiguration cfg =
                new PeerConnection.RTCConfiguration(iceServers);

        pc = factory.createPeerConnection(cfg, new PeerConnection.Observer() {
            @Override public void onSignalingChange(PeerConnection.SignalingState s) {}
            @Override public void onIceConnectionChange(PeerConnection.IceConnectionState s) {}
            @Override public void onIceConnectionReceivingChange(boolean r) {}
            @Override public void onIceGatheringChange(PeerConnection.IceGatheringState s) {}
            @Override public void onIceCandidate(IceCandidate c) {}
            @Override public void onIceCandidatesRemoved(IceCandidate[] c) {}
            @Override public void onAddStream(MediaStream s) {}
            @Override public void onRemoveStream(MediaStream s) {}
            @Override public void onDataChannel(DataChannel d) {}
            @Override public void onRenegotiationNeeded() {}
            @Override public void onAddTrack(RtpReceiver r, MediaStream[] s) {}
        });
        pc.addTrack(videoTrack, Collections.singletonList("ARDAMS"));
    }

    private void createOffer() {
        setStatus("Đang tạo kết nối...");
        pc.createOffer(new SimpleSdpObserver() {
            @Override public void onCreateSuccess(final SessionDescription sdp) {
                pc.setLocalDescription(new SimpleSdpObserver() {
                    @Override public void onSetSuccess() {
                        postOffer(sdp.description);
                    }
                    @Override public void onSetFailure(final String err) {
                        mainHandler.post(new Runnable() {
                            @Override public void run() {
                                setStatus("Lỗi SDP: " + err);
                                stopCasting();
                            }
                        });
                    }
                }, sdp);
            }
            @Override public void onCreateFailure(final String err) {
                mainHandler.post(new Runnable() {
                    @Override public void run() {
                        setStatus("Không tạo được offer: " + err);
                        stopCasting();
                    }
                });
            }
        }, new MediaConstraints());
    }

    private void postOffer(final String sdp) {
        setStatus("Đang gửi tới máy tính...");
        net.execute(new Runnable() {
            @Override public void run() {
                try {
                    JSONObject body = new JSONObject();
                    body.put("room", room);
                    body.put("sdp", sdp);
                    String resp = httpPost(API_BASE + "/offer", body.toString());
                    final boolean ok = new JSONObject(resp).optBoolean("ok", false);
                    mainHandler.post(new Runnable() {
                        @Override public void run() {
                            if (ok) {
                                setStatus("Đang chờ máy tính nhận...");
                                startPolling();
                            } else {
                                setStatus("Máy chủ từ chối offer.");
                                stopCasting();
                            }
                        }
                    });
                } catch (final Exception e) {
                    mainHandler.post(new Runnable() {
                        @Override public void run() {
                            setStatus("Lỗi gửi offer: " + e.getMessage());
                            stopCasting();
                        }
                    });
                }
            }
        });
    }

    private void startPolling() {
        polling = true;
        pollRunnable = new Runnable() {
            @Override public void run() {
                if (!polling) return;
                net.execute(new Runnable() {
                    @Override public void run() {
                        try {
                            String resp = httpGet(API_BASE + "/answer?room="
                                    + URLEncoder.encode(room, "UTF-8"));
                            final String sdp = new JSONObject(resp).optString("sdp", "");
                            if (sdp != null && !sdp.isEmpty()) {
                                polling = false;
                                final SessionDescription ans =
                                        new SessionDescription(
                                                SessionDescription.Type.ANSWER, sdp);
                                mainHandler.post(new Runnable() {
                                    @Override public void run() {
                                        if (pc != null) {
                                            pc.setRemoteDescription(
                                                    new SimpleSdpObserver() {
                                                        @Override public void onSetSuccess() {
                                                            setStatus("✅ Đang chiếu lên máy tính");
                                                        }
                                                    }, ans);
                                        }
                                    }
                                });
                            } else {
                                mainHandler.postDelayed(pollRunnable, 1500);
                            }
                        } catch (Exception e) {
                            mainHandler.postDelayed(pollRunnable, 1500);
                        }
                    }
                });
            }
        };
        mainHandler.post(pollRunnable);
    }

    private void stopCasting() {
        polling = false;
        if (pollRunnable != null) mainHandler.removeCallbacks(pollRunnable);
        try { if (capturer != null) capturer.stopCapture(); } catch (Exception ignored) {}
        try { if (videoSource != null) videoSource.dispose(); } catch (Exception ignored) {}
        try { if (surfaceHelper != null) surfaceHelper.dispose(); } catch (Exception ignored) {}
        try { if (pc != null) pc.close(); } catch (Exception ignored) {}
        try { if (factory != null) factory.dispose(); } catch (Exception ignored) {}
        try { if (eglBase != null) eglBase.release(); } catch (Exception ignored) {}
        pc = null;
        factory = null;
        capturer = null;
        mainHandler.post(new Runnable() {
            @Override public void run() {
                btnStart.setVisibility(View.VISIBLE);
                btnStop.setVisibility(View.GONE);
                etRoom.setEnabled(true);
            }
        });
    }

    @Override
    protected void onDestroy() {
        stopCasting();
        net.shutdownNow();
        super.onDestroy();
    }

    // ---------- helpers ----------

    private void setStatus(String s) {
        tvStatus.setText(s);
    }

    private static String readAll(HttpURLConnection c) throws Exception {
        int code = c.getResponseCode();
        BufferedReader r = new BufferedReader(new InputStreamReader(
                code < 400 ? c.getInputStream() : c.getErrorStream(), "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) sb.append(line);
        r.close();
        c.disconnect();
        return sb.toString();
    }

    private static String httpGet(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(15000);
        c.setRequestProperty("User-Agent", "SAOBAY-App/1.0");
        return readAll(c);
    }

    private static String httpPost(String url, String json) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(15000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        c.setRequestProperty("User-Agent", "SAOBAY-App/1.0");
        byte[] body = json.getBytes("UTF-8");
        c.setFixedLengthStreamingMode(body.length);
        OutputStream os = c.getOutputStream();
        os.write(body);
        os.close();
        return readAll(c);
    }

    /** SdpObserver rỗng để khỏi viết 4 method mỗi lần dùng. */
    private abstract static class SimpleSdpObserver implements SdpObserver {
        @Override public void onCreateSuccess(SessionDescription s) {}
        @Override public void onSetSuccess() {}
        @Override public void onCreateFailure(String err) {}
        @Override public void onSetFailure(String err) {}
    }
}
