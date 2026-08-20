package main

import (
	"flag"
	"fmt"
	"log"
	"net/http"
	"os"
	"path/filepath"

	"club.xiaojiawei/hs-script-version-server/internal/handler"
	"club.xiaojiawei/hs-script-version-server/internal/storage"
)

const version = "1.0.0"

// main 启动一个只依赖本地 releases 目录的轻量版本服务。
//
// API 路径刻意兼容项目客户端使用的 GitHub Releases 形式：元数据由 FileStorage 提供，
// 更新包由下载路由直接返回。domain 只参与生成对外 URL 和启动日志，不决定监听网卡；
// 实际监听地址始终由 port 组成。
func main() {
	// 命令行参数
	var (
		port        = flag.String("port", "8080", "服务端口")
		releasesDir = flag.String("releases", "./releases", "releases 文件夹路径")
		domain      = flag.String("domain", "localhost:8080", "服务域名")
		userName    = flag.String("user", "xiaojiawei", "用户名")
		projectName = flag.String("project", "Hearthstone-Script", "项目名")
		showVersion = flag.Bool("version", false, "显示版本信息")
		showHelp    = flag.Bool("help", false, "显示帮助信息")
	)

	flag.Parse()

	if *showVersion {
		fmt.Printf("HS-Script Version Server v%s\n", version)
		return
	}

	if *showHelp {
		printHelp()
		return
	}

	// 确保 releases 目录存在
	absReleasesDir, err := filepath.Abs(*releasesDir)
	if err != nil {
		log.Fatalf("Invalid releases directory: %v", err)
	}

	if err := os.MkdirAll(absReleasesDir, 0755); err != nil {
		log.Fatalf("Failed to create releases directory: %v", err)
	}

	// 存储层负责 releases.json 与目录扫描之间的回退，不让 HTTP 层感知文件布局细节。
	store := storage.NewFileStorage(absReleasesDir)

	// 初始化处理器
	h := handler.NewHandler(store, absReleasesDir, *domain, *userName, *projectName)

	// GitHub 风格 API；user/project 同时构成路由命名空间，避免与其他项目冲突。
	http.HandleFunc("/repos/"+*userName+"/"+*projectName+"/releases/latest", h.HandleLatestRelease)
	http.HandleFunc("/repos/"+*userName+"/"+*projectName+"/releases", h.HandleAllReleases)

	// 下载路由直接从 releases 目录提供发行包，不经过 JSON 存储层。
	http.HandleFunc("/"+*userName+"/"+*projectName+"/releases/download/", h.HandleDownload)

	// 健康检查
	http.HandleFunc("/health", h.HandleHealth)

	// 静态路由主要用于部署检查；客户端正常更新使用上面的 releases/download 路由。
	http.Handle("/static/", http.StripPrefix("/static/", http.FileServer(http.Dir(absReleasesDir))))

	// 启动服务器
	addr := ":" + *port
	log.Printf("HS-Script Version Server v%s", version)
	log.Printf("Releases directory: %s", absReleasesDir)
	log.Printf("Server starting on http://%s", *domain)
	log.Printf("API endpoints:")
	log.Printf("  - Latest release: http://%s/repos/%s/%s/releases/latest", *domain, *userName, *projectName)
	log.Printf("  - All releases: http://%s/repos/%s/%s/releases", *domain, *userName, *projectName)
	log.Printf("  - Download: http://%s/%s/%s/releases/download/{tag}/{filename}", *domain, *userName, *projectName)
	log.Printf("  - Health: http://%s/health", *domain)

	if err := http.ListenAndServe(addr, nil); err != nil {
		log.Fatalf("Server failed to start: %v", err)
	}
}

func printHelp() {
	fmt.Printf(`HS-Script Version Server v%s

一个提供符合 GitHub API 格式的版本更新服务器

使用方法:
  hs-script-version-server [options]

选项:
  --port string         服务端口 (默认: 8080)
  --releases string     releases 文件夹路径 (默认: ./releases)
  --domain string       服务域名 (默认: localhost:8080)
  --user string         用户名 (默认: xiaojiawei)
  --project string      项目名 (默认: Hearthstone-Script)
  --version            显示版本信息
  --help               显示帮助信息

API 端点:
  获取版本:
    GET /repos/{user}/{project}/releases/latest
    GET /repos/{user}/{project}/releases

  下载:
    GET /{user}/{project}/releases/download/{tag}/{filename}

示例:
  # 使用默认设置启动
  hs-script-version-server

  # 指定端口和域名
  hs-script-version-server --port 9000 --domain example.com:9000

  # 指定 releases 目录
  hs-script-version-server --releases /var/www/releases

Releases 目录结构:
  releases/
    ├── releases.json          (版本元数据，可选)
    ├── hs-script_v4.13.0-GA.zip
    ├── hs-script_v4.12.0-GA.zip
    └── ...

releases.json 格式示例:
  [
    {
      "tag_name": "v4.13.0-GA",
      "name": "v4.13.0-GA",
      "body": "更新说明...",
      "prerelease": false,
      "created_at": "2025-01-01T00:00:00Z",
      "published_at": "2025-01-01T00:00:00Z"
    }
  ]

注意:
  - 如果没有 releases.json，服务器会自动扫描目录中的 .zip 文件
  - 文件命名格式: hs-script_{tag_name}.zip
  - 包含 -DEV, -BETA, -TEST 的版本会被标记为预发布版本
`, version)
}
