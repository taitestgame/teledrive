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
go build -o telecloud-arm64 main.go
if [ $? -ne 0 ]; then
    echo "❌ Lỗi: Biên dịch thất bại."
    echo "👉 Hãy đảm bảo bạn đã cài đặt Go trên Termux bằng cách chạy lệnh: pkg install golang -y"
    exit 1
fi
chmod +x ./telecloud-arm64
echo "   [OK] Biên dịch thành công."

# 3. Khởi động lại server và tunnel
echo "-> Đang khởi động lại các dịch vụ..."
killall telecloud-arm64 cloudflared 2>/dev/null

sleep 1

# Chạy lại tất cả
bash run-termux.sh
