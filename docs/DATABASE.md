# TÀI LIỆU CƠ SỞ DỮ LIỆU (DATABASE SPECIFICATION) — HOANQSON PROXY

Tài liệu này ghi lại chi tiết cấu trúc bảng, các trường dữ liệu và cơ chế bảo mật của cơ sở dữ liệu nội bộ SQLite và cơ sở dữ liệu đám mây Supabase Cloud.

---

## 1. Cơ sở dữ liệu SQLite cục bộ (Local SQLite)

- **Tên cơ sở dữ liệu**: `hoanqson_proxy.db`
- **Lớp quản trị**: `AppDatabaseHelper` kế thừa `SQLiteOpenHelper`.
- **Phiên bản hiện tại**: `1`
- **Vị trí trên thiết bị**: `/data/data/app.hoanqson.proxy/databases/hoanqson_proxy.db`

### 1.1. Bảng `proxy_keys` (Quản lý các Proxy Key đã nhập)
Lưu trữ danh sách các key người dùng đã kiểm tra và lưu trên máy:

| Tên cột | Kiểu dữ liệu | Ràng buộc | Mục đích |
| :--- | :--- | :--- | :--- |
| `id` | `INTEGER` | `PRIMARY KEY AUTOINCREMENT` | Mã định danh bản ghi |
| `key` | `TEXT` | `UNIQUE NOT NULL` | Chuỗi token/key người dùng nhập |
| `status` | `TEXT` | `NOT NULL` | Trạng thái (`valid` hoặc `invalid`) |
| `current_proxy` | `TEXT` | Nullable | Chuỗi proxy gần nhất tương ứng với key |
| `last_checked_at` | `INTEGER` | `NOT NULL` | Timestamp (ms) lần kiểm tra gần nhất |
| `created_at` | `INTEGER` | `NOT NULL` | Timestamp (ms) lúc tạo bản ghi |
| `last_error` | `TEXT` | Nullable | Lưu lại thông báo lỗi nếu có |

### 1.2. Bảng `rotation_history` (Nhật ký xoay Proxy)
Lưu vết 100 lần xoay proxy gần nhất của người dùng:

| Tên cột | Kiểu dữ liệu | Ràng buộc | Mục đích |
| :--- | :--- | :--- | :--- |
| `id` | `INTEGER` | `PRIMARY KEY AUTOINCREMENT` | Mã định danh bản ghi |
| `old_proxy` | `TEXT` | `NOT NULL` | Chuỗi proxy trước khi xoay |
| `new_proxy` | `TEXT` | `NOT NULL` | Chuỗi proxy mới sau khi xoay |
| `rotated_at` | `INTEGER` | `NOT NULL` | Timestamp (ms) thời điểm hoàn tất xoay |

---

## 2. Cơ sở dữ liệu Đám mây Supabase (Supabase Cloud PostgreSQL)

- **Project URL**: `https://plefigzicrulzndyrbxc.supabase.co`
- **Schema**: `public`
- **Bảng**: `api_key_logs`

### 2.1. Cấu trúc bảng `public.api_key_logs`

| Tên cột | Kiểu dữ liệu | Ràng buộc | Mục đích |
| :--- | :--- | :--- | :--- |
| `id` | `bigint` / `uuid` | `PRIMARY KEY` | Mã định danh duy nhất |
| `api_key` | `text` | `UNIQUE NOT NULL` | Token proxy hợp lệ được gửi lên |
| `entered_at` | `timestamp with time zone` | `DEFAULT now()` | Thời điểm key được ghi nhận lên cloud (UTC) |

### 2.2. Ràng buộc toàn vẹn & Chống trùng lặp (Duplicate Protection)
Bảng có ràng buộc UNIQUE trên cột `api_key`:
```sql
ALTER TABLE public.api_key_logs 
ADD CONSTRAINT api_key_logs_api_key_unique UNIQUE (api_key);
```
- Ngăn chặn hoàn toàn việc tạo 2 bản ghi cho cùng 1 key, bảo vệ database ngay cả khi có 2 request gửi đồng thời (race condition).
- Khi người dùng xóa key trên Supabase Dashboard và nhập lại trên app, lệnh `INSERT` hoạt động lại bình thường và ghi nhận bản ghi mới.

### 2.3. Chính sách phân quyền Row Level Security (RLS)
- **Role `anon` (Ứng dụng Android)**:
  - Cho phép: `INSERT` (thêm key mới).
  - Nghiêm cấm: `SELECT` (đọc dữ liệu), `UPDATE` (sửa dữ liệu), `DELETE` (xóa dữ liệu).
- **Role `authenticated` / `service_role` (Quản trị viên trên Dashboard)**:
  - Toàn quyền xem, lọc và xuất dữ liệu danh sách key trên giao diện Supabase Dashboard.
