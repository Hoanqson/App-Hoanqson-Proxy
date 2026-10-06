# KIẾN TRÚC HỆ THỐNG CHI TIẾT (SYSTEM ARCHITECTURE) — HOANQSON PROXY

Tài liệu này phân tích chi tiết thiết kế kiến trúc kỹ thuật của HoanqSon Proxy, bao gồm các sơ đồ luồng dữ liệu (Mermaid) và nguyên lý hoạt động của từng hệ thống con.

---

## 1. Sơ đồ luồng dữ liệu tổng quan

```mermaid
flowchart TD
    subgraph Client ["Thiết bị Android (Android Device)"]
        UI["MainActivity (Giao diện Liquid Glass)"]
        SKS["SecureKeyStorage (Android Keystore)"]
        DB["AppDatabaseHelper (SQLite Local)"]
        VPN["KeyProxyVpnService (VpnService)"]
        T2S["Tun2SocksManager (Go Engine JNI)"]
        TUN["Virtual Interface (tun0 - 10.0.0.2)"]
    end

    subgraph HomeProxy_Cloud ["HomeProxy v3 Server"]
        HP_API["https://app.homeproxy.vn/api/v3/users/rotatev2"]
        HP_PROXY["Proxy Server (IP:Port:Auth)"]
    end

    subgraph Supabase_Cloud ["Supabase Cloud Platform"]
        SB_REST["PostgREST API (/rest/v1/api_key_logs)"]
        SB_DB["PostgreSQL Table: public.api_key_logs"]
    end

    UI -->|1. Lưu & Kiểm tra Token| KPC[KeyProxyClient]
    KPC -->|2. GET ?token=...&checkOnly=true| HP_API
    HP_API -->|3. Trả về ProxyConfig| KPC
    KPC -->|4. Key Hợp lệ| UI
    
    UI -->|5. Lưu Token| SKS
    UI -->|6. Ghi danh sách| DB
    UI -->|7. Gửi POST (Anon Role)| SB_REST
    SB_REST -->|8. ON CONFLICT DO NOTHING| SB_DB

    UI -->|9. Bật CONNECT| VPN
    VPN -->|10. Tạo TUN fd| TUN
    VPN -->|11. Truyền TUN fd & ProxyConfig| T2S
    T2S -->|12. Định tuyến qua Proxy| HP_PROXY
```

---

## 2. Kiến trúc các hệ thống con

### 2.1. Tầng Giao diện (UI Architecture)
- Được triển khai trong `MainActivity.kt` bằng Jetpack ViewBinding với layout XML.
- Thiết kế theo phong cách Glassmorphism / Liquid Glass với hình nền bầu trời căng tràn màn hình (`ImageView` với `scaleType="centerCrop"`).
- Quản lý trạng thái thông qua Kotlin Coroutines và State:
  - `Disconnected`: Chờ kết nối, nút KẾT NỐI màu xanh biển.
  - `Connecting`: Đang thiết lập tunnel và kiểm tra IP, nút hiển thị vòng xoay.
  - `Connected`: Mạng proxy hoạt động, nút chuyển sang NGẮT KẾT NỐI (màu đỏ) và nút XOAY PROXY sáng lên.
  - `Rotating`: Đang đổi IP mới, nút xoay tạm khóa để chống bấm liên tục (race-condition).

### 2.2. Tầng Mạng & Tun2Socks (VPN Architecture)
- **Android VpnService**: `KeyProxyVpnService` kế thừa `android.net.VpnService`, đăng ký với hệ điều hành quyền `BIND_VPN_SERVICE` và foreground type `specialUse (vpn)`.
- **Cơ chế hoạt động**:
  1. `VpnService.Builder` khởi tạo một interface mạng ảo `tun0` tại IP `10.0.0.2/24` và DNS `1.1.1.1`.
  2. Hệ điều hành chuyển giao File Descriptor (`tunFd`) cho ứng dụng.
  3. `Tun2SocksManager` nhận `tunFd` và chuyển sang thư viện Go `libgojni.so` qua cầu nối JNI (`engine.Key` và `engine.Engine.start()`).
  4. Lõi Go netstack phân tích toàn bộ gói tin IP mức layer-3 và chuyển tiếp thành các luồng TCP/UDP kết nối tới máy chủ Proxy.

### 2.3. Tầng Xác thực & Kiểm tra Key (Key Validation Flow)
- Giao tiếp với API nhà mạng qua `KeyProxyClient`:
  - Luôn sử dụng query `checkOnly=true` khi chỉ xác thực hoặc lấy proxy hiện tại. Cờ này bảo vệ tài khoản người dùng không bị trừ số lần đổi IP ngoài ý muốn.
  - Khi người dùng chủ động nhấn **XOAY PROXY**, app gửi request không có cờ `checkOnly` để nhận IP mới.

### 2.4. Tầng Đồng bộ Đám mây (Supabase Sync Flow)
- Sử dụng trực tiếp PostgREST endpoint: `https://plefigzicrulzndyrbxc.supabase.co/rest/v1/api_key_logs`.
- Xác thực bằng `apikey` và `Authorization: Bearer <sb_publishable_key>`.
- Client `anon` chỉ có quyền `INSERT`. Khi gửi key:
  - Nếu key chưa có: Trả về `201 Created` ➔ thành công.
  - Nếu key đã có: Vi phạm ràng buộc `UNIQUE (api_key)` ở PostgreSQL, trả về `409 Conflict` ➔ app coi như đã đồng bộ an toàn mà không gây lỗi giao diện.

### 2.5. Tầng Lưu trữ Nội bộ (Local Storage Layer)
- **Token & Cài đặt bảo mật**: Lưu trong `SecureKeyStorage` sử dụng `EncryptedSharedPreferences` (AES-256 GCM) với khóa mã hóa bảo vệ bởi phần cứng Android Keystore.
- **Danh sách Key & Lịch sử**: Lưu trong SQLite cục bộ (`AppDatabaseHelper`) với 2 bảng `proxy_keys` và `rotation_history`.
