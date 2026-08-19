# HS-Script Version Server

一个提供符合 GitHub API 格式的版本更新服务器，用于 HS-Script 软件的版本管理和更新。

## 功能特性

- ✅ 兼容 GitHub API 格式
- ✅ 支持预发布版本管理
- ✅ 自动文件扫描（无需手动维护版本列表）
- ✅ 静态文件服务
- ✅ 健康检查接口
- ✅ 跨平台支持

## 快速开始

### 编译

```bash
# Windows
build.bat

# Linux/Mac
go build -ldflags="-s -w" -o hs-script-version-server cmd/server/main.go
```

### 运行

```bash
# 使用默认设置
./hs-script-version-server

# 指定端口和域名
./hs-script-version-server --port 9000 --domain example.com:9000

# 指定 releases 目录
./hs-script-version-server --releases /var/www/releases
```

## API 接口

### GitHub 风格 API

```bash
# 获取最新版本（非预发布）
GET /repos/{user}/{project}/releases/latest

# 获取所有版本
GET /repos/{user}/{project}/releases
```

### 下载文件

```bash
# 下载指定版本
GET /{user}/{project}/releases/download/{tag}/{filename}
```

示例：
```bash
GET /xiaojiawei/Hearthstone-Script/releases/download/v4.13.0-GA/hs-script_v4.13.0-GA.zip
```

### 健康检查

```bash
GET /health
```

## 目录结构

```
releases/
├── releases.json                # 版本元数据（可选）
├── hs-script_v4.13.0-GA.zip    # 发布文件
├── hs-script_v4.12.0-GA.zip
└── ...
```

## releases.json 格式

如果提供 `releases.json` 文件，服务器会使用它作为版本信息来源。文件格式：

```json
[
  {
    "tag_name": "v4.13.0-GA",
    "name": "v4.13.0-GA 正式版",
    "body": "## 更新内容\n\n- 新增功能 A\n- 修复 bug B\n- 优化性能 C",
    "prerelease": false,
    "created_at": "2025-01-01T00:00:00Z",
    "published_at": "2025-01-01T00:00:00Z"
  },
  {
    "tag_name": "v4.12.0-GA",
    "name": "v4.12.0-GA 正式版",
    "body": "更新说明...",
    "prerelease": false,
    "created_at": "2024-12-01T00:00:00Z",
    "published_at": "2024-12-01T00:00:00Z"
  }
]
```

## 自动文件扫描

如果没有 `releases.json` 文件，服务器会自动扫描 `releases` 目录：

1. 扫描所有 `.zip` 文件
2. 从文件名提取版本号（格式：`hs-script_{tag_name}.zip`）
3. 自动判断是否为预发布版本：
   - 包含 `-DEV` 的为开发版
   - 包含 `-BETA` 的为测试版
   - 包含 `-TEST` 的为候选版本
   - 其他为正式版本

## 命令行参数

| 参数 | 说明 | 默认值 |
|------|------|--------|
| `--port` | 服务端口 | `8080` |
| `--releases` | releases 文件夹路径 | `./releases` |
| `--domain` | 服务域名 | `localhost:8080` |
| `--user` | 用户名 | `xiaojiawei` |
| `--project` | 项目名 | `Hearthstone-Script` |
| `--version` | 显示版本信息 | - |
| `--help` | 显示帮助信息 | - |

## 部署建议

### Nginx 反向代理

```nginx
server {
    listen 80;
    server_name version.example.com;

    location / {
        proxy_pass http://localhost:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    }
}
```

### Systemd 服务

创建 `/etc/systemd/system/hs-script-version-server.service`：

```ini
[Unit]
Description=HS-Script Version Server
After=network.target

[Service]
Type=simple
User=www-data
WorkingDirectory=/opt/hs-script-version-server
ExecStart=/opt/hs-script-version-server/hs-script-version-server \
    --port 8080 \
    --releases /var/www/releases \
    --domain version.example.com
Restart=on-failure

[Install]
WantedBy=multi-user.target
```

启动服务：
```bash
sudo systemctl daemon-reload
sudo systemctl enable hs-script-version-server
sudo systemctl start hs-script-version-server
```

### Docker 部署

创建 `Dockerfile`：

```dockerfile
FROM golang:1.21-alpine AS builder
WORKDIR /app
COPY . .
RUN go build -ldflags="-s -w" -o hs-script-version-server cmd/server/main.go

FROM alpine:latest
RUN apk --no-cache add ca-certificates
WORKDIR /root/
COPY --from=builder /app/hs-script-version-server .
EXPOSE 8080
CMD ["./hs-script-version-server", "--releases", "/releases"]
```

运行：
```bash
docker build -t hs-script-version-server .
docker run -d -p 8080:8080 -v /path/to/releases:/releases hs-script-version-server
```

## 协议

本项目遵循 **[GPL3.0开源协议](LICENSE)** 及 **[禁止商用附加协议](LICENSE1)**
