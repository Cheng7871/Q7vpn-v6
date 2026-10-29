#!/usr/bin/env bash
# Q7VPN 网站一键部署（Ubuntu 20.04 / 22.04 / 24.04，root 执行）
# 用法：
#   cd web && sudo bash deploy_vps.sh                 # 用 80 端口，自动生成密钥
#   sudo DOMAIN=example.com bash deploy_vps.sh        # 指定域名（仍然走 http，https 见文末）
set -euo pipefail

APP_DIR=/opt/q7vpn
SERVICE=q7vpn
PORT=10000
ENV_FILE=/etc/q7vpn.env

[ "$(id -u)" = "0" ] || { echo "请用 root 执行：sudo bash deploy_vps.sh"; exit 1; }
[ -f app.py ] || { echo "请在 web/ 目录里执行本脚本"; exit 1; }

echo "[1/6] 安装系统依赖"
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y -qq python3 python3-venv python3-pip nginx curl >/dev/null

echo "[2/6] 复制程序到 $APP_DIR"
mkdir -p "$APP_DIR" /var/lib/q7vpn
# 保留已有数据库，不要把线上数据覆盖掉
tar --exclude='*.sqlite3*' --exclude='__pycache__' --exclude='.venv' --exclude='instance' \
    -cf - . | tar -xf - -C "$APP_DIR"

echo "[3/6] 安装 Python 依赖"
python3 -m venv "$APP_DIR/.venv"
"$APP_DIR/.venv/bin/pip" install -q --upgrade pip
"$APP_DIR/.venv/bin/pip" install -q -r "$APP_DIR/requirements.txt"

echo "[4/6] 写环境变量"
if [ ! -f "$ENV_FILE" ]; then
  cat > "$ENV_FILE" <<EOF
SECRET_KEY=$(python3 -c 'import secrets;print(secrets.token_hex(32))')
IP_HASH_SALT=$(python3 -c 'import secrets;print(secrets.token_hex(16))')
Q7VPN_DB_PATH=/var/lib/q7vpn/q7vpn.sqlite3
Q7VPN_TZ_OFFSET=8
# 已经配好 https 之后再改成 1
COOKIE_SECURE=0
PORT=$PORT
EOF
  chmod 600 "$ENV_FILE"
fi

echo "[5/6] 注册 systemd 服务"
cat > "/etc/systemd/system/$SERVICE.service" <<EOF
[Unit]
Description=Q7VPN web
After=network.target

[Service]
Type=simple
WorkingDirectory=$APP_DIR
EnvironmentFile=$ENV_FILE
ExecStart=$APP_DIR/.venv/bin/gunicorn -w 2 -b 127.0.0.1:$PORT --access-logfile - app:app
Restart=always
RestartSec=3
NoNewPrivileges=true
PrivateTmp=true

[Install]
WantedBy=multi-user.target
EOF
systemctl daemon-reload
systemctl enable --now "$SERVICE" >/dev/null

echo "[6/6] 配置 nginx 反代"
SERVER_NAME="${DOMAIN:-_}"
cat > "/etc/nginx/sites-available/$SERVICE" <<EOF
server {
    listen 80;
    server_name $SERVER_NAME;
    client_max_body_size 2m;

    location / {
        proxy_pass http://127.0.0.1:$PORT;
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$scheme;
    }
}
EOF
ln -sf "/etc/nginx/sites-available/$SERVICE" "/etc/nginx/sites-enabled/$SERVICE"
rm -f /etc/nginx/sites-enabled/default
nginx -t >/dev/null && systemctl reload nginx

sleep 2
if curl -fsS "http://127.0.0.1:$PORT/health" >/dev/null; then
  echo
  echo "部署完成。浏览器打开：http://<这台机器的IP>/"
  echo "服务管理：systemctl status $SERVICE  |  日志：journalctl -u $SERVICE -f"
  echo "数据库在 /var/lib/q7vpn/q7vpn.sqlite3，记得备份。"
  echo
  echo "要上 https：装好证书后把 $ENV_FILE 里的 COOKIE_SECURE 改成 1，再 systemctl restart $SERVICE"
else
  echo "健康检查没通过，看日志：journalctl -u $SERVICE -n 50 --no-pager"
  exit 1
fi
