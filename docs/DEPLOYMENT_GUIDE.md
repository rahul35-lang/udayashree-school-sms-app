# 🚀 Deployment Guide: 24/7 Server & Cloudflare Tunnel

## 1. Cloudflare Tunnel Configuration on Server
The 24/7 server is hosted at: `root@100.89.137.102`.

The tunnel config is located at `/etc/cloudflared/config.yml`:
```yaml
tunnel: b780baa3-b66e-489b-be22-36e8b9b740a4
credentials-file: /etc/cloudflared/b780baa3-b66e-489b-be22-36e8b9b740a4.json
protocol: http2

ingress:
  - hostname: sms.success0.com.np
    service: http://localhost:8080
  - hostname: school.success0.com.np
    service: http://localhost:5050
  - hostname: pariwarik.success0.com.np
    service: http://localhost:9000
  - service: http_status:404
```

To route the DNS record in Cloudflare:
```bash
cloudflared tunnel route dns b780baa3-b66e-489b-be22-36e8b9b740a4 sms.success0.com.np
systemctl restart cloudflared
```

---

## 2. Server Deployment as a 24/7 Systemd Service

Deploy the code to `/opt/school-sms-gateway/`:
```bash
mkdir -p /opt/school-sms-gateway
cp -r /path/to/server /opt/school-sms-gateway/
```

Create `/etc/systemd/system/school-sms-gateway.service`:
```ini
[Unit]
Description=School SMS and OTP Gateway REST Server
After=network.target cloudflared.service

[Service]
Type=simple
User=root
WorkingDirectory=/opt/school-sms-gateway/server
ExecStart=/usr/bin/python3 /opt/school-sms-gateway/server/server.py
Restart=always
RestartSec=5
Environment=GATEWAY_PORT=8080
Environment=GATEWAY_PORT_SECONDARY=8000
Environment=GATEWAY_SECRET_TOKEN=Udaya@momphone

[Install]
WantedBy=multi-user.target
```

Enable and start the service:
```bash
systemctl daemon-reload
systemctl enable school-sms-gateway
systemctl start school-sms-gateway
```

Verify service is running:
```bash
systemctl status school-sms-gateway
curl -s https://sms.success0.com.np/api/v1/status
```
