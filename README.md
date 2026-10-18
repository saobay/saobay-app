# SAOBAY — App Android trường THPT Sào Báy

App Android gồm 2 chức năng:

1. **Web trường**: mở `https://www.saobayhighschool.workers.dev` toàn màn hình (WebView).
2. **📲 Chiếu màn hình**: dùng MediaProjection API quay màn hình điện thoại,
   stream qua WebRTC tới máy tính đang mở `live/cast.html`
   (tái dùng signaling có sẵn của web, không cần sửa web).

## Tải APK

Vào tab **Actions** → chọn lần chạy mới nhất (**Build APK**) → tải artifact
**saobay-app-debug** → giải nén được file `app-debug.apk` → chép vào điện thoại
và cài (cho phép "cài app không rõ nguồn gốc" 1 lần).

APK tự build lại mỗi khi push code lên nhánh `main`.

## Cách dùng chức năng chiếu màn hình

1. Trên **máy tính**: mở `https://www.saobayhighschool.workers.dev/live/cast.html`
   → màn hình hiện mã phòng 6 ký tự (VD: `M2G2A9`).
2. Trên **điện thoại**: mở app SAOBAY → bấm nút **📲 Chiếu màn hình**
   → nhập mã phòng → bấm **Bắt đầu chiếu màn hình** → chấp nhận quyền
   "quay/chiếu màn hình".
3. Màn hình điện thoại hiện trong khung trên máy tính → dùng OBS quay lại
   làm video hướng dẫn.

## Build thủ công (nếu cần)

```bash
# Cài Android SDK + JDK 17, rồi:
gradle assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

## Yêu cầu

- Android 8.0 (API 26) trở lên.
- Không cần USB Debugging, không cần root, không cần cáp.
