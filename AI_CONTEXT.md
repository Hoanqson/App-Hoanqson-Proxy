# AI CONTEXT & OPERATIONAL GUIDELINES — HOANQSON PROXY

Tài liệu này được soạn thảo riêng cho các AI Coding Agent (Antigravity, Cursor, Devin, Claude Code, GitHub Copilot Workspace, v.v.). Trước khi đọc hoặc sửa bất kỳ dòng code nào trong repository này, AI Agent **BẮT BUỘC PHẢI ĐỌC VÀ TUÂN THỦ** toàn bộ các nguyên tắc dưới đây.

---

## 1. Triết lý làm việc cốt lõi của User (Core User Principles)

> **"CHỈ SỬA CÁI ĐÓ — KHÔNG XÓA, KHÔNG THÊM, KHÔNG REFACTOR NGOÀI PHẠM VI YÊU CẦU."**

- **Quy tắc phạm vi hẹp (Minimal Scope)**: User thích giải quyết các vấn đề một cách chính xác, đúng trọng tâm. Khi user yêu cầu sửa một chi tiết A, chỉ sửa đúng chi tiết A. Tuyệt đối không tiện tay dọn dẹp, tối ưu hóa hay viết lại (refactor) các phần khác.
- **Thứ tự ưu tiên**: `Độ ổn định (Stability) > Bảo mật (Security) > Thay đổi giao diện (UI Changes)`.
- **Không giả định / Không mock**: Các thông số Ping, Speed, IP phải lấy từ dữ liệu thực tế, không tạo số ngẫu nhiên hoặc dữ liệu giả lập.
- **Không xóa chức năng đang chạy**: Bất kỳ tính năng nào đang hoạt động (VPN, Connect, Rotate, Auto Rotate, Timer, History, Settings, v.v.) đều phải được giữ nguyên vẹn 100%.

---

## 2. Các quy tắc cấm kỵ (Strict Project Rules)

1. **KHÔNG đổi Package Name**: Package name cố định là `app.hoanqson.proxy`.
2. **KHÔNG đổi HomeProxy API**: Endpoint cố định là `https://app.homeproxy.vn/api/v3/users/rotatev2`. Khi kiểm tra key bắt buộc phải có `checkOnly=true`. Tuyệt đối không xóa cờ này vì sẽ làm hao tốn lượt đổi IP của khách hàng.
3. **KHÔNG đổi Supabase Architecture**:
   - Sử dụng PostgREST qua HTTPS.
   - Client Android **CHỈ ĐƯỢC PHÉP DÙNG Publishable / Anon Key**.
   - **TUYỆT ĐỐI KHÔNG NHÚNG `service_role` HOẶC `sb_secret`** vào mã nguồn hay APK.
   - Client **KHÔNG ĐƯỢC CẤP QUYỀN SELECT/DELETE** trên Supabase để ngăn chặn rò rỉ key người dùng khác.
4. **KHÔNG can thiệp vào Native Packaging**:
   - Cấu hình `useLegacyPackaging = true` và `extractNativeLibs = true` là bắt buộc để đảm bảo APK tương thích hoàn hảo và không bị lỗi *"Đã xảy ra sự cố khi phân tích cú pháp gói"* trên Android 14.
5. **KHÔNG commit Secrets**: Không commit keystore cá nhân, private signing key, file `.env` chứa credential thật lên Git.

---

## 3. Trạng thái ổn định hiện tại (Current Stable State)

| Hạng mục | Trạng thái thực tế | Ghi chú kỹ thuật |
| :--- | :---: | :--- |
| **Package Name** | `app.hoanqson.proxy` | Chuẩn hóa toàn bộ Manifest và Kotlin files |
| **Version** | `1.1.0` (versionCode 3) | Target Android 14 (API 34), Min SDK 26 |
| **Build Debug** | **PASS** | `./gradlew assembleDebug` thành công 100% |
| **Build Release** | **PASS** | `./gradlew assembleRelease` thành công 100% |
| **R8 / Minification** | **PASS** | Bật `minifyEnabled true`, `shrinkResources true` cho Release |
| **Network Security** | **PASS** | `cleartextTrafficPermitted="false"`, Release chỉ trust System CA |
| **Logging** | **PASS** | `isDebugEnabled = BuildConfig.DEBUG`, tự động tắt log trên Release |
| **Supabase Sync** | **PASS** | Xử lý 201 Created và 409 Conflict an toàn qua UNIQUE constraint |
| **VPN Service** | **PASS** | `KeyProxyVpnService` (`specialUse`), tích hợp lõi Go `tun2socks` |
| **Native ABIs** | **PASS** | Đầy đủ 4 ABI: `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64` |
| **Giao diện (UI)** | **PASS** | Liquid Glass, 100% tiếng Việt, ảnh nền tràn màn hình `centerCrop` |

---

## 4. Những hạng mục bảo mật đã xử lý vs Chưa xử lý

### ĐÃ XỬ LÝ (Completed):
- [x] Network Security Config: Chặn hoàn toàn HTTP cleartext.
- [x] Chặn User CA trên bản Release (chống MITM qua Charles/Burp).
- [x] Tắt Logcat debug trên bản Release bằng `BuildConfig.DEBUG`.
- [x] R8 Minification & Resource Shrinking trên bản Release.
- [x] Quét sạch secret nhạy cảm (`service_role`, `sb_secret`, private keys).
- [x] Mã hóa lưu trữ key cục bộ bằng `EncryptedSharedPreferences` qua Android Keystore.
- [x] Xử lý duplicate key trên Supabase ở tầng database (`UNIQUE`).
- [x] Khắc phục triệt để lỗi phân tích cú pháp gói APK (Zip integrity, native library alignment).

### CHƯA XỬ LÝ (Intentionally Untouched):
- [ ] SQLite field-level encryption (SQLCipher).
- [ ] SecureKeyStorage fallback hardening (vẫn giữ fallback private prefs).
- [ ] Supabase Edge Function / rate limiting chống spam insert.
- [ ] SSL Certificate Pinning (chưa làm để tránh rủi ro đứt kết nối khi server đổi cert).
- [ ] SafetyNet / Play Integrity / Root detection.

*Lưu ý cho AI Agent: Nếu user không yêu cầu xử lý các mục trong phần "CHƯA XỬ LÝ", AI KHÔNG ĐƯỢC TỰ Ý THAY ĐỔI.*
