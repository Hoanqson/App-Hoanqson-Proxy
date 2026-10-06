# HƯỚNG DẪN ĐÓNG GÓP & CHỈNH SỬA (CONTRIBUTING) — HOANQSON PROXY

Dành cho các lập trình viên hoặc AI Coding Agent tham gia phát triển dự án HoanqSon Proxy.

---

## 1. Quy trình bắt buộc trước khi sửa code (Before Editing)

Trước khi tiến hành sửa đổi bất kỳ tệp tin nào trong repository, bạn **BẮT BUỘC** phải:

1. **Đọc kỹ [README.md](README.md)**: Để hiểu rõ mục đích và các tính năng của dự án.
2. **Đọc kỹ [AI_CONTEXT.md](AI_CONTEXT.md)**: Nắm vững các quy tắc hoạt động, trạng thái hiện tại và triết lý làm việc.
3. **Đọc kỹ [DEVELOPMENT.md](DEVELOPMENT.md)**: Hiểu rõ luồng dữ liệu của các thành phần và vị trí file liên quan.
4. **Kiểm tra trực tiếp source code liên quan**: Đọc mã nguồn thực tế trước khi sửa, tuyệt đối không đoán mò hành vi của code.
5. **Thực hiện thay đổi với phạm vi nhỏ nhất có thể (Smallest Possible Change)**:
   > **"CHỈ SỬA CÁI ĐÓ — KHÔNG XÓA, KHÔNG THÊM."**
6. **Biên dịch và kiểm tra (Build & Verify)**: Luôn chạy lệnh build sạch sau khi sửa:
   ```powershell
   .\gradlew.bat assembleDebug assembleRelease --no-daemon
   ```
7. **Báo cáo rõ ràng**: Liệt kê chính xác các file đã sửa, nguyên nhân và kết quả kiểm thử.

---

## 2. Các điều cấm kỵ tuyệt đối (Strict Prohibitions)

- **KHÔNG** refactor lớn các đoạn mã đang hoạt động ổn định.
- **KHÔNG** xóa bất kỳ tính năng hiện có nào (Connect, Disconnect, Rotate, Auto Rotate, Timer, Settings, History, v.v.).
- **KHÔNG** thay đổi package name `app.hoanqson.proxy`.
- **KHÔNG** thay đổi endpoint HomeProxy API hay xóa cờ `checkOnly=true`.
- **KHÔNG** commit các file chứa secret, keystore, token hoặc credential thật lên Git.
- **KHÔNG** sửa cấu hình native packaging (`useLegacyPackaging` và `extractNativeLibs`).
