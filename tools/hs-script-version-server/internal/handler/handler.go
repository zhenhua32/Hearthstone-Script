package handler

import (
	"encoding/json"
	"fmt"
	"net/http"
	"path/filepath"

	"club.xiaojiawei/hs-script-version-server/internal/storage"
)

// Handler 把版本存储适配为项目客户端需要的 HTTP API。
// 它不持有缓存：每次查询都通过 Storage 读取当前元数据，因此替换 releases.json 后
// 无需重启服务即可生效。
type Handler struct {
	storage     storage.Storage
	releasesDir string
	domain      string
	userName    string
	projectName string
}

// NewHandler 注入存储实现和路由命名信息，便于使用内存存储测试 HTTP 层。
func NewHandler(storage storage.Storage, releasesDir, domain, userName, projectName string) *Handler {
	return &Handler{
		storage:     storage,
		releasesDir: releasesDir,
		domain:      domain,
		userName:    userName,
		projectName: projectName,
	}
}

// HandleLatestRelease 返回最新的正式版本，主动过滤 prerelease。
// GET /repos/{user}/{project}/releases/latest
func (h *Handler) HandleLatestRelease(w http.ResponseWriter, r *http.Request) {
	release, err := h.storage.GetLatestRelease(false)
	if err != nil {
		http.Error(w, err.Error(), http.StatusNotFound)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(release)
}

// HandleAllReleases 返回按存储层规则排序后的全部版本，包括预发布版本。
// GET /repos/{user}/{project}/releases
func (h *Handler) HandleAllReleases(w http.ResponseWriter, r *http.Request) {
	releases, err := h.storage.GetAllReleases()
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(releases)
}

// HandleDownload 从约定路径末尾提取文件名，并交给 net/http 处理范围请求和内容传输。
// 路径中的 tag 只用于保持 GitHub URL 兼容性，实际文件定位以 releasesDir/filename 为准。
// GET /{user}/{project}/releases/download/{tag}/{filename}
func (h *Handler) HandleDownload(w http.ResponseWriter, r *http.Request) {
	// 从 URL 路径解析参数
	// 路径格式: /{user}/{project}/releases/download/{tag}/{filename}
	parts := splitPath(r.URL.Path)

	if len(parts) < 6 {
		http.Error(w, "Invalid URL format", http.StatusBadRequest)
		return
	}

	filename := parts[len(parts)-1]

	// 构建文件路径
	filePath := filepath.Join(h.releasesDir, filename)

	// 检查文件是否存在
	http.ServeFile(w, r, filePath)
}

// HandleHealth 只表示 HTTP 进程可响应，不额外检查 releases 目录中是否存在发行包。
func (h *Handler) HandleHealth(w http.ResponseWriter, r *http.Request) {
	w.WriteHeader(http.StatusOK)
	fmt.Fprintf(w, "OK")
}

// splitPath 删除连续斜杠形成的空段，便于按逻辑路径段计数。
func splitPath(path string) []string {
	var parts []string
	for _, part := range splitBySlash(path) {
		if part != "" {
			parts = append(parts, part)
		}
	}
	return parts
}

func splitBySlash(s string) []string {
	var result []string
	var current string
	for _, c := range s {
		if c == '/' {
			result = append(result, current)
			current = ""
		} else {
			current += string(c)
		}
	}
	result = append(result, current)
	return result
}
