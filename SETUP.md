# SETUP ENVIRONMENT GUIDE - KEY PROXY

Tài liệu này hướng dẫn chi tiết các bước thiết lập môi trường để phát triển, chỉnh sửa và đóng gói ứng dụng **KeyProxyApp**.

---

## 1. Yêu Cầu Phần Cứng & Phần Mềm

- **Hệ điều hành**: Windows 10/11, macOS, hoặc Linux (Ubuntu 20.04+).
- **RAM**: Tối thiểu 8 GB (Khuyến nghị 16 GB để chạy Android Studio và Emulator mượt mà).
- **Ổ cứng**: Còn trống tối thiểu 10 GB.
- **Java Development Kit**: JDK 17 (Amazon Corretto 17, Eclipse Temurin 17, hoặc OpenJDK 17).
- **Android Studio**: Android Studio Koala Feature Drop hoặc mới hơn.

---

## 2. Cài Đặt Android SDK

Mở **Android Studio** -> **SDK Manager** (Settings -> Languages & Frameworks -> Android SDK):

1. **SDK Platforms**:
   - [x] Android 14.0 ("UpsideDownCake") - API Level 34
   - [x] Android 13.0 ("Tiramisu") - API Level 33

2. **SDK Tools**:
   - [x] Android SDK Build-Tools 34.0.0
   - [x] Android SDK Command-line Tools (latest)
   - [x] Android SDK Platform-Tools
   - [x] NDK (Side by side) 25.x hoặc 26.x (Tùy chọn nếu muốn tự build native core)

---

## 3. Cấu Trúc Dự Án

```
KeyProxyApp/
├── app/
│   ├── libs/
│   │   └── tun2socks.aar          <-- Lõi tun2socks biên dịch sẵn đầy đủ 4 ABI
│   ├── src/main/
│   │   ├── java/vn/homeproxy/keyrotator/
│   │   │   ├── MainActivity.kt    <-- UI chính của app
│   │   │   ├── model/             <-- ProxyConfig, ProxyStatus, UI states
│   │   │   ├── proxy/             <-- KeyProxyClient, IpChecker
│   │   │   ├── storage/           <-- SecureKeyStorage (Keystore encrypted)
│   │   │   ├── util/              <-- AppLogger (masking sensitive data)
│   │   │   └── vpn/               <-- KeyProxyVpnService, Tun2SocksManager
│   │   ├── res/                   <-- Layouts, drawables, colors, strings
│   │   └── AndroidManifest.xml    <-- VpnService & Permissions configuration
│   └── build.gradle               <-- App module dependencies
├── gradle/wrapper/                <-- Gradle 8.2 wrapper
├── build.gradle                   <-- Root project gradle with AGP 8.2.2
├── settings.gradle                <-- Project settings & flatDir config
├── README.md                      <-- Tổng quan dự án
├── SETUP.md                       <-- Tài liệu cài đặt này
└── BUILD.md                       <-- Hướng dẫn biên dịch APK
```

---

## 4. Kiểm Tra ABI của Thiết Bị

Thư viện `tun2socks.aar` trong `app/libs` đã được tích hợp sẵn 4 kiến trúc:
- `arm64-v8a` (Hầu hết thiết bị Android hiện đại ngày nay)
- `armeabi-v7a` (Các thiết bị Android 32-bit cũ)
- `x86` (Giả lập 32-bit)
- `x86_64` (Giả lập 64-bit trên PC)

Không cần cài đặt thêm bất kỳ công cụ Go Mobile hay NDK nào trừ khi bạn muốn tự build lại file `.aar`.
