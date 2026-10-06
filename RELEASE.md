# QUY TRÌNH PHÁT HÀNH (RELEASE PROCESS) — HOANQSON PROXY

Tài liệu này chuẩn hóa toàn bộ các bước từ cập nhật phiên bản, đóng gói, kiểm thử cho đến khi tạo Release trên GitHub cho ứng dụng HoanqSon Proxy.

---

## 1. Lưu ý quan trọng về Chữ ký số (Signing Status)

> [!WARNING]
> **CURRENT RELEASE SIGNING: Debug keystore / Temporary signing**
>
> Phiên bản Release hiện tại đang được cấu hình ký tạm thời bằng `debug.keystore` (mặc định của Android SDK) trong `build.gradle` để phục vụ việc cài đặt và kiểm thử trực tiếp trên thiết bị của bạn mà không cần tạo keystore riêng:
> ```groovy
> buildTypes {
>     release {
>         signingConfig signingConfigs.debug
>         ...
>     }
> }
> ```
> **TUYỆT ĐỐI KHÔNG COI ĐÂY LÀ PRODUCTION SIGNING CHO GOOGLE PLAY STORE.** 
> Nếu sau này bạn muốn upload lên Google Play Store, bạn cần tự tạo một file `.jks` production riêng trên máy tính cá nhân của mình và tuyệt đối không commit file `.jks` đó lên GitHub.

---

## 2. Quy trình Release từng bước (Step-by-Step Release Workflow)

### Bước 1: Nâng phiên bản trong `app/build.gradle`
Khi chuẩn bị ra mắt bản mới:
1. Mở file `app/build.gradle`.
2. Tăng `versionCode` (ví dụ: `3` ➔ `4`).
3. Cập nhật `versionName` (ví dụ: `"1.1.0"` ➔ `"1.2.0"`).

### Bước 2: Biên dịch sạch bản Release
Chạy lệnh clean và assemble:
```powershell
.\gradlew.bat clean assembleRelease --no-daemon
```
Quá trình build sẽ kích hoạt tác vụ R8:
- `minifyReleaseWithR8`
- `shrinkReleaseRes`
- `packageRelease`

### Bước 3: Kiểm tra tính hợp lệ của APK
1. Kiểm tra kích thước file tại `app/build/outputs/apk/release/app-release.apk` (thường khoảng 28MB).
2. Kiểm tra tính toàn vẹn file nén và danh sách native libraries:
```powershell
python -c "import zipfile; z = zipfile.ZipFile(r'app/build/outputs/apk/release/app-release.apk'); print('Files:', len(z.namelist())); print('Libs:', [f for f in z.namelist() if f.startswith('lib/')])"
```

### Bước 4: Kiểm thử chức năng trên thiết bị thực tế
Cài đặt APK vào điện thoại và thực hiện kiểm tra nhanh 8 luồng chính:
- [ ] Mở ứng dụng, hiển thị giao diện Liquid Glass nền mây.
- [ ] Nhập key và nhấn **Kiểm tra Key** ➔ Hiển thị **Key hợp lệ**.
- [ ] Nhấn **KẾT NỐI** ➔ Cấp quyền VPN, hiển thị chấm xanh **Đã kết nối** và IP mới.
- [ ] Kiểm tra đo đạc Ping, Download, Upload thực tế.
- [ ] Nhấn **XOAY PROXY** ➔ Nhận IP mới thành công.
- [ ] Kiểm tra tính năng **Tự động xoay** theo chu kỳ cài đặt.
- [ ] Nhấn **NGẮT KẾT NỐI** ➔ Giải phóng VPN an toàn.
- [ ] Kiểm tra Supabase Dashboard ➔ Record của key xuất hiện trong bảng `api_key_logs`.

### Bước 5: Đóng gói Git Tag và tạo GitHub Release
1. Commit toàn bộ thay đổi:
```bash
git add .
git commit -m "chore: release HoanqSon Proxy v1.1.0"
```
2. Tạo Git Tag:
```bash
git tag -a v1.1.0 -m "Release HoanqSon Proxy v1.1.0"
```
3. Đẩy lên GitHub:
```bash
git push origin main --tags
```
4. Trên giao diện GitHub: Vào mục **Releases** ➔ **Draft a new release** ➔ Chọn tag `v1.1.0` ➔ Đính kèm file `app-release.apk` và nhấn **Publish release**.
