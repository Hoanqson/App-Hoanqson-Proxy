# CHANGELOG — HOANQSON PROXY

Toàn bộ các cập nhật đáng chú ý của dự án HoanqSon Proxy được ghi lại chi tiết tại đây.

---

## [v1.1.0] - 2026-10-06

### Security Hardening (Gia cố bảo mật)
- **Network Security**: Cập nhật `network_security_config.xml` với `cleartextTrafficPermitted="false"`. Chặn hoàn toàn User-installed CA trên bản Release (chỉ tin cậy chứng chỉ hệ thống), ngăn chặn tấn công MITM bằng các công cụ bắt gói tin (Charles/Burp/HTTP Catcher).
- **Logging Control**: Bật `buildConfig = true`, chuyển logic `AppLogger.isDebugEnabled` sang phụ thuộc vào `BuildConfig.DEBUG`. Bản Release tự động ngắt toàn bộ debug log.
- **R8 Obfuscation & Shrinking**: Kích hoạt `minifyEnabled true` và `shrinkResources true` cho build type `release`. Giảm kích thước APK từ 31.7MB xuống 28.1MB, bảo vệ mã nguồn khỏi dịch ngược.
- **Supabase Cloud Sync Hardening**: Loại bỏ hoàn toàn server local `localhost:8088`. Sử dụng PostgREST với Publishable/Anon Key. Thiết lập xử lý mã `201 Created` và `409 Conflict` kết hợp với ràng buộc `UNIQUE (api_key)` ở tầng database để chống ghi trùng lặp an toàn và chống race-condition.

### Fixed (Sửa lỗi)
- **Lỗi phân tích cú pháp gói APK (Package Parse Error)**: Sửa lỗi unaligned native libraries trên Android 14 bằng cách cấu hình `useLegacyPackaging = true` và `extractNativeLibs = true`.
- **Lỗi hiển thị hình nền**: Thay thế drawable dạng layer-list bitmap (bị co cụm thành hình chữ nhật nhỏ ở giữa) bằng `ImageView` toàn màn hình với `scaleType="centerCrop"`, ảnh nền tràn viền 100% không viền đen.
- **Lỗi sync lại key sau khi xóa trên Cloud**: Khắc phục lỗi gọi `on_conflict` bị từ chối do thiếu quyền SELECT bằng cách gửi POST INSERT chuẩn và xử lý an toàn phản hồi 201/409.

### Changed (Thay đổi giao diện)
- **Ẩn thông tin nhạy cảm trong UI Check Key**: Ẩn chuỗi proxy `IP:Port:User:Pass` trong khu vực danh sách key đã lưu, chỉ hiển thị trạng thái ngắn gọn `"Hợp lệ"`.
- **Làm sạch chữ trong Cài đặt**: Xóa bỏ chữ `(DATABASE)` trong tiêu đề và cụm từ `được lưu trong cơ sở dữ liệu.` để giao diện hoàn toàn chuyên nghiệp và thân thiện với người dùng cuối.

---

## [v1.2.0] - Template cho phiên bản tương lai

### Added
- *Các tính năng mới sẽ được thêm vào đây...*

### Changed
- *Các thay đổi hành vi hoặc giao diện...*

### Fixed
- *Các lỗi được khắc phục...*

### Security
- *Các cải tiến bảo mật...*
