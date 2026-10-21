# Muse Voice — App trò chuyện bằng giọng nói với Muse

App Android để nói chuyện rảnh tay với Muse (chính là trợ lý trong app Muse):

- **Muse gửi tin nhắn → app tự đọc to** bằng giọng tiếng Việt (TTS).
- **Bạn nói → app chuyển thành chữ → gửi cho Muse** (nhận diện giọng nói vi-VN).
- Kèm **lệnh điều khiển thiết bị** chạy ngay trên máy, kể cả **tắt màn hình**.

## Cách nói chuyện (bộ lệnh đã chốt)

1. Mở app → đăng nhập Muse 1 lần trong trang hiện ra (app nhớ đăng nhập).
2. Bấm **🎙 Bật giọng nói** (cấp quyền micro khi được hỏi).
3. Nói **“Nghe này”** hoặc **“OK Muse”** → app đáp “Em nghe đây” và bắt đầu ghi lệnh.
4. Đọc nội dung — nói mấy lượt cũng được, app gom hết vào bộ đệm, màn hình hiện phần đã ghi.
5. Kết thúc bằng **“Xong rồi”**, **“Gửi đi”**, **“Hết”** hoặc **“Enter”**
   (nói ở cuối câu, hoặc nói riêng 1 lượt sau khi đọc xong) → app gửi cho Muse.
6. Muse trả lời → app **tự đọc to**. Đọc xong app quay về chờ câu gọi ở bước 3.

Ví dụ: *“OK Muse — kiểm tra giúp thầy đề Toán 11 hôm nay có lỗi gì không — gửi đi.”*

## Lệnh thiết bị (nói thẳng, không gửi cho Muse)

| Nói | App làm |
|---|---|
| “tắt màn hình” / “khóa màn hình” | Khóa/tắt màn hình ngay (cần bật quyền 1 lần, xem dưới) |
| “bật đèn pin” / “tắt đèn pin” | Bật/tắt đèn flash |
| “tăng âm lượng” / “giảm âm lượng” | Chỉnh âm lượng nghe |
| “mở SAOBAY” / “mở web trường” | Mở web trường trong app |
| “về Muse” | Quay lại trang trò chuyện |
| “dừng đọc” / “im lặng” | Ngắt giọng đọc ngay |
| “đọc lại” | Đọc lại câu trả lời gần nhất |

## Tự tạo lệnh mới ngay trên giao diện

Bấm **➕ Lệnh tự tạo** ở màn hình chính:

1. Nhập **câu bạn sẽ nói** (ví dụ: “mở nhạc thư giãn”).
2. Chọn **hành động**: Mở trang web / Gửi sẵn 1 câu cho Muse / Đọc to 1 câu /
   Tắt màn hình / Bật–tắt đèn pin / Tăng–giảm âm lượng.
3. Điền **nội dung thêm** nếu hành động cần (URL, hoặc câu sẽ gửi/đọc) → **Lưu**.

Lệnh có hiệu lực ngay, lưu trên máy. Chạm vào lệnh trong danh sách để xóa.

Ví dụ lệnh hay: nói “báo cáo sáng nay” → tự gửi cho Muse câu
“Báo cáo nhanh tình hình SAOBAY sáng nay: có gì mới, có lỗi gì cần thầy xử lý không?”

## Quyền tắt màn hình (làm 1 lần)

Android chỉ cho app khóa màn hình khi được cấp quyền quản trị thiết bị:
bấm **⚙️ Quyền tắt màn hình** trong app → bật cho Muse Voice.
Quyền này chỉ dùng đúng 1 việc: khóa màn hình khi bạn ra lệnh. App không xóa
dữ liệu, không đổi mật khẩu. Gỡ quyền bất cứ lúc nào trong
Cài đặt > Bảo mật > Ứng dụng quản trị thiết bị.

## Tải APK

Project này nằm trong thư mục `muse-voice/` của repo **saobay/saobay-app**.
Vào tab **Actions** của repo → chọn lần chạy **Build Muse Voice APK** mới nhất →
tải artifact **muse-voice-debug** → giải nén được `app-debug.apk` →
chép vào điện thoại và cài (cho phép “cài app không rõ nguồn gốc” 1 lần).

## Yêu cầu

- Android 8.0 (API 26) trở lên, có ứng dụng Google (để nhận diện giọng nói tiếng Việt).
- Muốn giọng đọc tiếng Việt hay: Cài đặt > Ngôn ngữ / Văn bản thành giọng nói >
  chọn Google Text-to-Speech, tải giọng tiếng Việt.

## Ghi chú kỹ thuật

- App gồm WebView mở `muse.ai` + cầu nối JS (`assets/voice-bridge.js`):
  điền tin nhắn vào ô chat, bấm gửi, và theo dõi tin nhắn mới của Muse để đọc.
  Nếu một bản cập nhật web làm gửi tự động không chạy, app sẽ hiện hộp thoại
  kèm nút **Sao chép** để dán tay — không mất nội dung đã nói.
- Không dùng thư viện ngoài nào: SpeechRecognizer + TextToSpeech là API sẵn của Android.
