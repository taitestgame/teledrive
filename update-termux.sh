#!/bin/bash

# Khóa chế độ ngủ của Android để tránh treo mạng khi tải/biên dịch file lớn
if command -v termux-wake-lock &>/dev/null; then
    termux-wake-lock
fi

# Thư mục hiện tại của script
cd "$(dirname "$0")"

echo "============================================="
echo "   ĐANG CẬP NHẬT TELECLOUD TỪ GITHUB"
echo "============================================="

# 1. Kéo code mới về qua Git
echo "-> Kéo mã nguồn mới nhất..."
git pull
if [ $? -ne 0 ]; then
    echo "❌ Lỗi: Không thể kéo code mới từ Git. Vui lòng kiểm tra kết nối."
    exit 1
fi

# 2. Biên dịch mã nguồn trên điện thoại
echo "-> Đang biên dịch mã nguồn trực tiếp trên điện thoại..."
# Cài đặt golang trên Termux nếu chưa có: pkg install golang -y
go build -o telecloud main.go
if [ $? -ne 0 ]; then
    echo "❌ Lỗi: Biên dịch thất bại."
    echo "👉 Hãy đảm bảo bạn đã cài đặt Go trên Termux bằng cách chạy lệnh: pkg install golang -y"
    exit 1
fi
chmod +x ./telecloud
echo "   [OK] Biên dịch thành công."

# Sao chép đè vào thư mục chạy chính của Menu quản lý (~/telecloud-go) nếu tồn tại
if [ -d "$HOME/telecloud-go" ]; then
    echo "-> Phát hiện thư mục quản lý chính, đang cập nhật tệp chạy vào ~/telecloud-go..."
    cp ./telecloud "$HOME/telecloud-go/telecloud"
    chmod +x "$HOME/telecloud-go/telecloud"
fi

# 3. Dọn dẹp tiến trình cũ và thông báo
killall telecloud-arm64 telecloud cloudflared 2>/dev/null || true
echo "============================================="
echo "✅ BIÊN DỊCH VÀ CẬP NHẬT THÀNH CÔNG!"
echo "============================================="
echo "👉 Phiên bản đã được cập nhật thành công thành tệp 'telecloud'."
echo "👉 Bây giờ bạn có thể dùng MENU QUẢN LÝ để điều khiển."
echo "👉 Hãy chạy lệnh sau để mở TELECLOUD MANAGER MENU:"
echo "   bash auto-setup.sh"
echo "   Và chọn:"
echo "   - Mục 2: Khởi động ứng dụng"
echo "   - Hoặc Mục 4: Khởi động lại ứng dụng"
echo "============================================="
