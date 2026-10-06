# TÀI LIỆU API (API SPECIFICATION) — HOANQSON PROXY

Tài liệu này ghi lại chi tiết các API endpoint và giao thức truyền thông mạng được sử dụng trong dự án HoanqSon Proxy.

---

## 1. HomeProxy v3 API (Nhà cung cấp Proxy)

- **Base URL**: `https://app.homeproxy.vn/api/v3/users/rotatev2`
- **Giao thức**: HTTPS (TLS 1.2 / TLS 1.3)
- **Phương thức HTTP**: `GET`
- **Header gửi kèm**:
  - `User-Agent`: `Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/120.0.0.0`
  - `Cache-Control`: `no-cache`

### 1.1. Endpoint Kiểm tra Key / Lấy Proxy hiện tại (Check Current Proxy)
- **Đường dẫn**:
  ```
  GET https://app.homeproxy.vn/api/v3/users/rotatev2?token={token}&checkOnly=true
  ```
- **Ý nghĩa tham số `checkOnly=true`**:
  > [!IMPORTANT]
  > Cờ `checkOnly=true` là **BẮT BUỘC** khi kiểm tra tính hợp lệ của key hoặc lấy proxy đang chạy. Khi có cờ này, máy chủ HomeProxy sẽ KHÔNG thực hiện đổi IP và KHÔNG làm tiêu hao lượt đổi IP của tài khoản người dùng.
- **Phản hồi khi Key hợp lệ (200 OK)**:
  ```json
  {
    "status": "success",
    "message": "Đã kết nối Proxy hiện tại!",
    "proxy": "14.237.225.229:39757:anthony_7czk:password_abc",
    "timeRemaining": 0
  }
  ```
- **Phản hồi khi Key không hợp lệ (401 Unauthorized / Error JSON)**:
  ```json
  {
    "status": "error",
    "error": {
      "code": "unauthorized",
      "message": "invalid or expired token"
    }
  }
  ```

### 1.2. Endpoint Xoay Proxy (Rotate Proxy)
- **Đường dẫn**:
  ```
  GET https://app.homeproxy.vn/api/v3/users/rotatev2?token={token}
  ```
- **Phản hồi khi xoay thành công (200 OK)**:
  ```json
  {
    "status": "success",
    "message": "Xoay thành công!",
    "proxy": "118.69.182.10:39888:anthony_7czk:password_abc",
    "timeRemaining": 120
  }
  ```
- **Phản hồi khi đang trong thời gian chờ (Cooldown / Rate Limited)**:
  ```json
  {
    "status": "error",
    "message": "Proxy đang trong thời gian chờ",
    "timeRemaining": 45,
    "proxy": "14.237.225.229:39757:anthony_7czk:password_abc"
  }
  ```
  *(Khi gặp lỗi này, ứng dụng sẽ tiếp tục duy trì proxy cũ trong trường `proxy`, không làm rớt mạng).*

---

## 2. Supabase Cloud REST API (Lưu nhật ký Key)

- **Base URL**: `https://plefigzicrulzndyrbxc.supabase.co/rest/v1`
- **Xác thực**:
  - `apikey`: `<SUPABASE_PUBLISHABLE_KEY>`
  - `Authorization`: `Bearer <SUPABASE_PUBLISHABLE_KEY>`
- **Content-Type**: `application/json`

### 2.1. Endpoint Gửi Key hợp lệ (Submit Valid Key)
- **Đường dẫn**:
  ```
  POST https://plefigzicrulzndyrbxc.supabase.co/rest/v1/api_key_logs
  ```
- **Header bổ sung**:
  - `Prefer`: `return=minimal`
- **Request Body**:
  ```json
  {
    "api_key": "YOUR_VALID_TOKEN"
  }
  ```
- **Các mã phản hồi**:
  - `201 Created`: Key chưa tồn tại trên database, được chèn mới thành công kèm cột `entered_at = now()`.
  - `409 Conflict`: Key đã tồn tại trong database (vi phạm ràng buộc `UNIQUE`), Supabase từ chối chèn trùng lặp. App xử lý thành công an toàn.

---

## 3. Các API Kiểm tra IP công khai (Public IP Verification)

Ứng dụng gọi các dịch vụ tra cứu IP công khai độc lập để xác minh proxy hoạt động:
1. `https://api.ipify.org?format=json` ➔ Trả về `{ "ip": "14.237.225.229" }`
2. `https://api.myip.com` ➔ Trả về `{ "ip": "14.237.225.229", "country": "Vietnam" }`
