# HoanqSon Proxy (Key Proxy App)

Ứng dụng Android quản lý, kiểm tra và xoay Proxy di động tự động / thủ công thông qua API HomeProxy v3, tích hợp Android VpnService và lõi mạng native `tun2socks` (Go netstack).

---

## 1. Giới thiệu dự án

**HoanqSon Proxy** được thiết kế để định tuyến toàn bộ lưu lượng mạng của thiết bị Android qua proxy bảo mật (hỗ trợ HTTP, HTTPS, SOCKS5). Ứng dụng hỗ trợ kiểm tra tính hợp lệ của Proxy Key, xoay IP định kỳ tự động, hiển thị thông số đo đạc mạng thực tế và đồng bộ nhật ký kiểm tra key lên Supabase Cloud dành cho quản trị viên.

- **Package Name**: `app.hoanqson.proxy`
- **Phiên bản hiện tại**: `v1.1.0` (versionCode 3)
- **Hệ điều hành mục tiêu**: Tối ưu Android 14 (API 34), hỗ trợ tối thiểu từ Android 8.0 (API 26).

---

## 2. Tech Stack thực tế

Dự án sử dụng 100% công nghệ tiêu chuẩn của Android Native:

- **Ngôn ngữ**: Kotlin (`1.9.22`), Java 17
- **Giao diện (UI)**: XML Layouts, Jetpack ViewBinding, Material Design 3, phong cách Liquid Glass (Glassmorphism).
- **Lõi định tuyến mạng**: 
  - Android `VpnService` tạo virtual TUN interface (`tun0`).
  - Lõi `tun2socks` (Go core engine qua JNI/AAR) đóng gói sẵn trong `app/libs/tun2socks.aar`, hỗ trợ đầy đủ 4 kiến trúc CPU: `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`.
- **Mạng & REST API**: OkHttp 4.12.0, Gson 2.10.1, Coroutines & Flow.
- **Lưu trữ dữ liệu**:
  - **Local Secure Storage**: Android Keystore backed `EncryptedSharedPreferences` (AES-256 GCM).
  - **Local Database**: SQLite (`AppDatabaseHelper`) lưu lịch sử key và lịch sử xoay IP.
  - **Cloud Backend**: Supabase Cloud REST API (PostgREST) đồng bộ key hợp lệ với phân quyền bảo mật Row Level Security (RLS).
- **Tối ưu hóa & Bảo vệ**: R8 / ProGuard (Code Minification, Resource Shrinking, Obfuscation).

---

## 3. Các tính năng chính

1. **Xác thực & Quản lý Proxy Key**:
   - Gọi HomeProxy API xác thực key an toàn với chế độ `checkOnly=true` (không làm tiêu hao lượt đổi IP của người dùng).
   - Lưu trữ danh sách key cục bộ trên thiết bị, hỗ trợ ẩn/hiện, chọn key đang dùng hoặc xóa.
2. **Kết nối VPN TUN Interface**:
   - Bật/tắt kết nối proxy toàn hệ thống chỉ với 1 chạm.
   - Hiển thị IP thực tế sau khi đi qua proxy (`api.ipify.org`, `api.myip.com`).
3. **Đo đạc mạng thực tế (Real Network Metrics)**:
   - Đo Ping (độ trễ socket thực tế), Download speed và Upload speed khi proxy đang kết nối.
4. **Xoay Proxy (Rotate Proxy)**:
   - Xoay IP thủ công tức thì không làm đứt kết nối mạng ngoài ý muốn.
   - Chế độ **Tự động xoay (Auto Rotate)** với chu kỳ tùy chỉnh (mặc định 120 giây).
   - Đếm ngược thời gian chờ (cooldown) khi nhà mạng giới hạn tần suất xoay.
5. **Lịch sử xoay (Rotation History)**:
   - Lưu lại lịch sử các proxy cũ và proxy mới kèm thời gian xoay.
6. **Đồng bộ Supabase Cloud**:
   - Khi key hợp lệ được nhập hoặc kiểm tra, app gửi thông tin key lên bảng `api_key_logs` trên Supabase Cloud.
   - Áp dụng ràng buộc chống ghi trùng lặp ở tầng database (`UNIQUE`).
7. **Bảo mật tăng cường**:
   - Tắt hoàn toàn cleartext HTTP (`cleartextTrafficPermitted="false"`).
   - Bản Release chỉ tin cậy chứng chỉ hệ thống (chặn đứng tấn công MITM).
   - Bật R8 thu gọn và làm mờ mã nguồn.
   - Tự động che dấu (masking) token, password proxy trong mọi luồng log.

---

## 4. Cấu trúc thư mục

```
KeyProxyApp/
├── app/
│   ├── libs/
│   │   └── tun2socks.aar            # Thư viện lõi mạng Go JNI
│   ├── src/
│   │   └── main/
│   │       ├── java/vn/homeproxy/keyrotator/
│   │       │   ├── database/        # SQLite OpenHelper & Entities
│   │       │   ├── model/           # Data models (ProxyConfig, ProxyStatus)
│   │       │   ├── net/             # SupabaseSyncClient, NetworkMetricsTracker
│   │       │   ├── proxy/           # KeyProxyClient, IpChecker
│   │       │   ├── storage/         # SecureKeyStorage (EncryptedSharedPreferences)
│   │       │   ├── util/            # AppLogger
│   │       │   ├── vpn/             # KeyProxyVpnService, Tun2SocksManager
│   │       │   └── MainActivity.kt  # Màn hình chính điều khiển & giao diện
│   │       ├── res/                 # Layouts, drawables, themes, icons
│   │       └── AndroidManifest.xml
│   ├── build.gradle
│   └── proguard-rules.pro
├── docs/                            # Tài liệu chuyên sâu kiến trúc, API, Database
│   ├── ARCHITECTURE.md
│   ├── API.md
│   ├── DATABASE.md
│   └── SECURITY_AUDIT.md
├── AI_CONTEXT.md                    # Bản ghi ngữ cảnh cho AI coding agent
├── DEVELOPMENT.md                   # Hướng dẫn phát triển cho lập trình viên
├── BUILD.md                         # Hướng dẫn build sạch từ source
├── RELEASE.md                       # Hướng dẫn quy trình đóng gói Release
├── SECURITY.md                      # Chính sách & hiện trạng bảo mật
├── CHANGELOG.md                     # Lịch sử phiên bản
└── CONTRIBUTING.md                  # Quy định đóng góp & chỉnh sửa
```

---

## 5. Hướng dẫn cài đặt & Build nhanh

Yêu cầu: JDK 17, Android SDK API 34.

```bash
# Clone project
git clone https://github.com/HoanqSon-Proxy/HoanqSon-Proxy.git
cd HoanqSon-Proxy

# Build Debug APK
.\gradlew.bat assembleDebug

# Build Release APK (đã qua R8 optimize & shrink)
.\gradlew.bat assembleRelease
```

File APK xuất xưởng sẽ nằm tại:
- Debug: `app/build/outputs/apk/debug/app-debug.apk`
- Release: `app/build/outputs/apk/release/app-release.apk`

---

## 6. Giấy phép

Phát triển bởi đội ngũ HoanqSon Proxy. Bảo lưu mọi quyền.
