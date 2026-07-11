#!/bin/bash

# Khóa chế độ ngủ của Android để tránh treo mạng khi tải file lớn
if command -v termux-wake-lock &>/dev/null; then
    termux-wake-lock
fi

# Thư mục hiện tại của script
cd "$(dirname "$0")"

echo "============================================="
echo "   KÍCH HOẠT TELECLOUD TRÊN TERMUX"
echo "============================================="

# 1. Khởi động server backend
echo "-> Khởi động Telecloud Backend..."
chmod +x ./telecloud-arm64
nohup ./telecloud-arm64 > app.log 2>&1 &
echo "   [OK] Server đang chạy ẩn dưới nền (Log: app.log)"

sleep 2

# 2. Khởi động Cloudflare Tunnel
echo "-> Khởi động Cloudflare Tunnel..."
CLOUDFLARED_BIN="$HOME/cloudflared"
if [ ! -f "$CLOUDFLARED_BIN" ]; then
    CLOUDFLARED_BIN="cloudflared"
fi

nohup $CLOUDFLARED_BIN tunnel --config config.yml run > tunnel.log 2>&1 &
echo "   [OK] Tunnel đang chạy ẩn dưới nền (Log: tunnel.log)"
echo "============================================="
echo "Tất cả dịch vụ đã được khởi chạy thành công!"
echo "Nhấn Ctrl+C để thoát chế độ giám sát. Server vẫn sẽ chạy ngầm."
echo "Để kiểm tra log, chạy lệnh: tail -f app.log hoặc tail -f tunnel.log"
echo "============================================="

# Theo dõi tiến trình log
tail -n 20 -f app.log
