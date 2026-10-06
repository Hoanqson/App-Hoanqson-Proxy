# BÁO CÁO KIỂM TOÁN AN TOÀN THÔNG TIN (SECURITY AUDIT) — HOANQSON PROXY

Tài liệu này lưu trữ kết quả kiểm toán bảo mật toàn diện trên mã nguồn, tệp cấu hình và hạ tầng của HoanqSon Proxy, phân loại rõ các hạng mục đã hoàn thành và các hạng mục bảo lưu cho tương lai.

---

## 1. Các hạng mục đã hoàn thành (COMPLETED)

| Hạng mục kiểm tra | Giải pháp thực tế đã triển khai | Trạng thái |
| :--- | :--- | :---: |
| **Không rò rỉ Secrets trong APK** | Quét toàn bộ repository: Không chứa `service_role`, `sb_secret`, private key, signing password hay database root credential. | ✅ COMPLETED |
| **Bảo vệ truyền thông mạng (HTTPS Only)** | Cấu hình `cleartextTrafficPermitted="false"` trong `network_security_config.xml`. Toàn bộ giao tiếp mạng bắt buộc dùng TLS. | ✅ COMPLETED |
| **Chặn tấn công MITM (No User CA in Release)** | Bản Release chỉ chấp nhận chứng chỉ hệ thống (`<certificates src="system" />`), vô hiệu hóa các chứng chỉ người dùng cài đặt (User CA). Chặn đứng việc đọc trộm traffic qua Charles/Burp. | ✅ COMPLETED |
| **Tắt Debug Logging trên Release** | Đặt `AppLogger.isDebugEnabled = BuildConfig.DEBUG`. Bản Release tự động tắt toàn bộ log debug trên Logcat. | ✅ COMPLETED |
| **Tự động làm mờ dữ liệu nhạy cảm (Data Masking)** | `AppLogger.maskSensitive()` dùng Regex che giấu token query param, mật khẩu proxy và Authorization header trước khi ghi log. | ✅ COMPLETED |
| **Thu gọn và làm mờ mã nguồn (R8)** | Bật `minifyEnabled true` và `shrinkResources true` cho Release. Code được obfuscate chống dịch ngược, dung lượng APK giảm 3.6MB. | ✅ COMPLETED |
| **Bảo vệ phân quyền Supabase (RLS)** | Khóa hoàn toàn quyền `SELECT/UPDATE/DELETE` đối với client role `anon`. Client không thể đọc trộm danh sách key của nhau. | ✅ COMPLETED |
| **Chống Duplicate Key trên Cloud** | Tạo ràng buộc `UNIQUE (api_key)` ở database PostgreSQL. App xử lý 201 Created và 409 Conflict mượt mà, chống race condition. | ✅ COMPLETED |
| **Bảo mật lưu trữ cục bộ (Keystore)** | Sử dụng `EncryptedSharedPreferences` qua Android Keystore (AES-256 GCM) để lưu trữ API token. | ✅ COMPLETED |
| **Chống trích xuất dữ liệu qua ADB** | Khai báo `android:allowBackup="false"` trong `AndroidManifest.xml` ngăn ngừa trích xuất dữ liệu khi cắm cáp USB. | ✅ COMPLETED |
| **Bảo vệ thành phần hệ thống (Manifest Security)** | `KeyProxyVpnService` đặt `exported="false"`. Chỉ duy nhất `MainActivity` được `exported="true"` với filter launcher chuẩn. | ✅ COMPLETED |

---

## 2. Các rủi ro còn tồn tại & Bảo lưu tương lai (FUTURE HARDENING)

> [!NOTE]
> Các hạng mục dưới đây được ghi nhận rõ ràng để các AI coding agent hoặc kỹ sư sau này không lầm tưởng rằng chúng đã được xử lý:

### 2.1. Mã hóa cơ sở dữ liệu SQLite cục bộ (SQLite Field Encryption)
- **Hiện trạng**: Bảng `proxy_keys` và `rotation_history` trong `AppDatabaseHelper.kt` lưu trữ plaintext.
- **Rủi ro**: Trên các máy Android đã Root (hoặc bẻ khóa quyền superuser), kẻ tấn công có thể truy cập `/data/data/...` để đọc file SQLite.
- **Đề xuất tương lai**: Áp dụng mã hóa SQLCipher hoặc mã hóa từng trường `key` bằng `MasterKey` trước khi lưu vào SQLite.

### 2.2. Cơ chế Fallback của SecureKeyStorage
- **Hiện trạng**: Khi phần cứng Keystore bị lỗi (xảy ra trên một số ROM Android tùy biến), code fallback sang SharedPreferences không mã hóa (`Context.MODE_PRIVATE`).
- **Đề xuất tương lai**: Triển khai giải pháp mã hóa mềm (software-backed encryption) với seed được dẫn xuất an toàn nếu Keystore phần cứng từ chối khởi tạo.

### 2.3. Chống Spam Insert trên Supabase (Rate Limiting)
- **Hiện trạng**: Bảng `api_key_logs` cho phép role `anon` thực hiện lệnh `INSERT`. Kẻ tấn công có thể trích xuất Publishable Key và gửi nhiều request rác.
- **Đề xuất tương lai**: Triển khai Supabase Edge Function hoặc PostgreSQL Trigger kiểm tra độ dài/định dạng regex của chuỗi `api_key` và giới hạn số lần insert theo IP trong 1 phút.

### 2.4. Khóa cứng chứng chỉ SSL (Certificate Pinning)
- **Hiện trạng**: App chưa ghim hash SHA-256 của chứng chỉ SSL máy chủ `app.homeproxy.vn`.
- **Lý do bảo lưu**: Để tránh rủi ro ứng dụng bị tê liệt khi máy chủ nhà cung cấp thay đổi hoặc gia hạn chứng chỉ SSL hàng năm.

### 2.5. Phát hiện môi trường Root & Toàn vẹn thiết bị (Play Integrity / Root Detection)
- **Hiện trạng**: Chưa tích hợp bộ phát hiện Magisk/KernelSU/Root và Google Play Integrity API.
