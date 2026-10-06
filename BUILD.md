# HƯỚNG DẪN BUILD VÀ KIỂM TRA DỰ ÁN — HOANQSON PROXY

Tài liệu này hướng dẫn chi tiết cách thiết lập môi trường, biên dịch từ mã nguồn và kiểm tra tính toàn vẹn của file APK trên hệ điều hành Windows.

---

## 1. Yêu cầu môi trường (System Requirements)

- **Hệ điều hành**: Windows 10 / 11 (64-bit).
- **JDK (Java Development Kit)**: JDK 17 (OpenJDK 17 hoặc Eclipse Temurin 17).
  - Kiểm tra bằng lệnh: `java -version`.
- **Android SDK**: 
  - Compile SDK: `34` (Android 14).
  - Target SDK: `34`.
  - Min SDK: `26` (Android 8.0 Oreo).
- **Android Build Tools**: `34.0.0` trở lên.
- **Gradle**: Sử dụng Gradle Wrapper đi kèm dự án (`gradle-8.2-bin.zip`).
- **Lưu ý về thư viện Native**: File `app/libs/tun2socks.aar` đã được tích hợp sẵn đầy đủ 4 ABIs (`arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`), không cần cài thêm Android NDK hay Go compiler bên ngoài.

---

## 2. Hướng dẫn Build từ dòng lệnh (Command Line)

Mở terminal PowerShell hoặc Command Prompt tại thư mục gốc của project (`KeyProxyApp`):

### 2.1. Dọn dẹp cache (Clean)
```powershell
.\gradlew.bat clean
```

### 2.2. Biên dịch bản Debug (Assemble Debug)
```powershell
.\gradlew.bat assembleDebug --no-daemon
```
- **Vị trí file đầu ra**: `app/build/outputs/apk/debug/app-debug.apk`
- **Mục đích**: Dùng để cài đặt kiểm thử nhanh, có hỗ trợ debug logging trên Logcat và cho phép User CA để bắt gói tin kiểm tra mạng.

### 2.3. Biên dịch bản Release (Assemble Release)
```powershell
.\gradlew.bat assembleRelease --no-daemon
```
- **Vị trí file đầu ra**: `app/build/outputs/apk/release/app-release.apk`
- **Mục đích**: Bản phát hành chính thức, đã bật R8 Obfuscation & Shrinking, tự động ngắt debug logging và chặn User CA.

---

## 3. Quy trình xác thực APK (Verification & Integrity)

Sau khi biên dịch bản Release, thực hiện các bước kiểm tra sau:

### 3.1. Kiểm tra tính toàn vẹn file nén (ZIP Integrity)
File APK thực chất là một kho lưu trữ ZIP. Có thể kiểm tra bằng PowerShell:
```powershell
powershell -Command "Add-Type -AssemblyName System.IO.Compression.FileSystem; [System.IO.Compression.ZipFile]::OpenRead('app/build/outputs/apk/release/app-release.apk').Entries.Count"
```
Kết quả trả về danh sách các file (khoảng 900+ entries) mà không có lỗi giải nén.

### 3.2. Kiểm tra các thư viện Native (Native ABI Verification)
Đảm bảo APK chứa đầy đủ 4 thư viện `libgojni.so`:
```powershell
python -c "import zipfile; z = zipfile.ZipFile(r'app/build/outputs/apk/release/app-release.apk'); print('\n'.join([f for f in z.namelist() if f.startswith('lib/')]))"
```
Đầu ra chuẩn:
```
lib/arm64-v8a/libgojni.so
lib/armeabi-v7a/libgojni.so
lib/x86/libgojni.so
lib/x86_64/libgojni.so
```

### 3.3. Cài đặt và kiểm tra trên thiết bị thật (ADB Install)
```powershell
adb install -r app/build/outputs/apk/release/app-release.apk
```
Kết quả trả về `Success` và app mở được bình thường, không gặp thông báo lỗi cú pháp gói.
