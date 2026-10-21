# SAOBAY — Các app Android của trường THPT Sào Báy

## 📱 App "Sao Bay" (thư mục `app/`) — app cho giáo viên, viết lại 2026-10-22

Thay thế app SAOBAY cũ (WebRTC chiếu màn hình — đã xoá). Chức năng:

1. **Đăng nhập bằng tài khoản web SAOBAY** (SĐT + mật khẩu, API `/api/v2/auth/login`).
2. **📝 Chấm thi trắc nghiệm chạy ngay trên máy** (không cần mạng): chụp phiếu →
   tự tìm 4 điểm định vị → nắn phối cảnh → đọc SBD/mã đề/đáp án → chấm điểm.
   Hình học phiếu port nguyên từ `teacher/omr-grader.html` trên web
   (phiếu BGD 2025: 40 TN + 8 Đ/S + 6 TLN; phiếu 40/50 câu ABCD).
   Đề chấm đồng bộ từ `data/bank3/<uid>/omr-exams/` (GV giao bài / chuyển đề
   trong teacher hub là đề sang app); kết quả lưu trong máy, xuất CSV,
   hoặc đẩy về `data/bank2/` trên web.
3. **⏰ Nhắc giờ lên lớp** theo lịch báo giảng GV đẩy từ teacher hub
   (`data/reminders/<uid>.json`): báo trước giờ + đúng giờ, chuông + đọc to.
4. **🌐 Mở web trường** trong app; **📸 nút nổi chụp màn hình** lưu vào thư viện ảnh.
5. **🔄 Tự cập nhật** qua `downloads/saobay-version.json` trên web trường.

Icon app: ảnh cổng trường THPT Sào Báy (bảng tên trường) cắt vuông từ ảnh thật.

Tải APK: https://www.saobayhighschool.workers.dev/downloads/SaoBay.apk
(bản chính thức build bằng `build-saobay.sh` trên VM Muse — build thủ công
aapt2+javac+d8, không Gradle, không thư viện ngoài).

## 🎙 Muse Voice (thư mục `muse-voice/`)

App trợ lý giọng nói cá nhân (chat Muse bằng giọng nói + nhắc giờ),
project riêng, xem README trong thư mục đó.
