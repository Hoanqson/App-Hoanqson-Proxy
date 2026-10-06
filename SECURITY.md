# SECURITY POLICY & ARCHITECTURE — HOANQSON PROXY

Tài liệu này ghi lại chi tiết các cơ chế bảo mật đã triển khai, các quy tắc kiểm soát rủi ro và các hạng mục được bảo lưu trong dự án HoanqSon Proxy.

---

## 1. Các biện pháp bảo mật đã triển khai (Implemented Security)

### 1.1. Network Security (Mạng & Giao tiếp)
- **Tắt hoàn toàn Cleartext Traffic**: Trong `network_security_config.xml`, `cleartextTrafficPermitted="false"` được áp dụng trên toàn bộ ứng dụng. Mọi kết nối bắt buộc phải thông qua HTTPS/TLS.
- **Phân tách tin cậy chứng chỉ (Certificate Trust Separation)**:
  - **Release Build**: Chỉ tin cậy chứng chỉ gốc hệ thống (`<certificates src="system" />`). Các chứng chỉ do người dùng tự cài đặt (User-installed CAs) bị vô hiệu hóa hoàn toàn, chặn đứng nguy cơ tấn công Man-in-the-Middle (MITM) qua các công cụ sniff mạng như Charles Proxy, Fiddler, Burp Suite, HTTP Catcher.
  - **Debug Build**: Cho phép User CA thông qua thẻ `<debug-overrides>` để phục vụ việc debug khi cần thiết.

### 1.2. Logging & Chống rò rỉ thông tin nhạy cảm
- **Debug Logging theo môi trường**: `AppLogger.isDebugEnabled` được liên kết với `BuildConfig.DEBUG`. Khi biên dịch bản Release, toàn bộ log mức `d` (debug) sẽ tự động bị ngắt, không hiển thị trên Logcat thiết bị.
- **Tự động che mờ (Sensitive Data Masking)**:
  - Hàm `AppLogger.maskSensitive()` sử dụng Regex tự động che các thông tin nhạy cảm trước khi xuất ra log:
    - Ẩn token trong URL: `token=abcde` ➔ `token=***`.
    - Ẩn mật khẩu proxy: `host:port:user:password` ➔ `host:port:user:***`.
    - Ẩn Authorization Header: `Authorization: Bearer ...` ➔ `Authorization: ***`.

### 1.3. Bảo vệ mã nguồn (R8 / ProGuard Obfuscation)
- **Minification & Shrinking**: Bản Release được bật `minifyEnabled true` và `shrinkResources true`.
- **Rút gọn và làm mờ**: Toàn bộ tên biến, phương thức và lớp nội bộ được làm mờ (obfuscate), gây khó khăn tối đa cho việc dịch ngược (reverse-engineering).
- **Keep Rules tối thiểu**: Chỉ giữ lại các model parse JSON qua reflection (`vn.homeproxy.keyrotator.model.**`), JNI native interface (`engine.**`) và VpnService để đảm bảo hiệu năng và tính ổn định.

### 1.4. Quản lý Secret & Phân quyền Supabase Cloud
- **Không nhúng Private Secret vào APK**: Toàn bộ mã nguồn và cấu hình build không chứa `service_role`, `sb_secret`, private signing key hay database password.
- **Sử dụng Publishable / Anon Key**: App chỉ sử dụng khóa công khai `sb_publishable_...` cho phía client.
- **Phân quyền nghiêm ngặt (Row Level Security - RLS)**:
  - Role `anon` chỉ được cấp quyền `INSERT` vào bảng `api_key_logs`.
  - Quyền `SELECT`, `UPDATE`, `DELETE` bị khóa hoàn toàn đối với client ➔ Người dùng cài APK không thể đọc trộm danh sách key của người khác hoặc chỉnh sửa/xóa dữ liệu.

### 1.5. Lưu trữ an toàn trên thiết bị (Local Storage)
- **Android Keystore Encryption**: `SecureKeyStorage` sử dụng thư viện `androidx.security.crypto.EncryptedSharedPreferences` với thuật toán mã hóa AES-256 GCM và AES-256 SIV, khóa mã hóa được quản lý trực tiếp bởi phần cứng Android Keystore.
- **Android Backup Disabled**: Thẻ `android:allowBackup="false"` trong `AndroidManifest.xml` ngăn chặn việc trích xuất dữ liệu ứng dụng qua lệnh `adb backup`.

---

## 2. Các hạng mục chưa triển khai (Future Hardening - Not Yet Implemented)

Để duy trì tính tương thích và theo đúng yêu cầu không mở rộng phạm vi, các hạng mục sau hiện **chưa triển khai**:

1. **SQLite Field Encryption**: Cơ sở dữ liệu nội bộ `AppDatabaseHelper` lưu trữ bảng `proxy_keys` và `rotation_history` dưới dạng SQLite tiêu chuẩn (chưa tích hợp SQLCipher).
2. **SecureKeyStorage Fallback Hardening**: Khi phần cứng Keystore bị lỗi trên một số ROM tùy biến, hệ thống fallback sang `Context.MODE_PRIVATE` không mã hóa.
3. **Supabase Rate Limiting**: Chưa có Edge Function giới hạn tần suất gửi bản ghi của một IP để chống spam INSERT.
4. **Certificate Pinning**: Chưa áp dụng SSL Pinning cố định SHA-256 cert để tránh rủi ro đứt kết nối khi máy chủ thay đổi chứng chỉ SSL.
5. **Play Integrity / Root Detection**: Chưa tích hợp bộ kiểm tra môi trường thiết bị đã Root.
