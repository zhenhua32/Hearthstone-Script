package handler

import (
	"encoding/json"
	"fmt"
	"net/http"
	"path/filepath"

	"club.xiaojiawei/hs-script-version-server/internal/storage"
)

// Handler API 处理器
type Handler struct {
	storage     storage.Storage
	releasesDir string
	domain      string
	userName    string
	projectName string
}

// NewHandler 创建处理器实例
func NewHandler(storage storage.Storage, releasesDir, domain, userName, projectName string) *Handler {
	return &Handler{
		storage:     storage,
		releasesDir: releasesDir,
		domain:      domain,
		userName:    userName,
		projectName: projectName,
	}
}

// HandleLatestRelease 处理获取最新版本请求
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

// HandleAllReleases 处理获取所有版本请求
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

// HandleDownload 处理下载请求
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

// HandleHealth 健康检查
func (h *Handler) HandleHealth(w http.ResponseWriter, r *http.Request) {
	w.WriteHeader(http.StatusOK)
	fmt.Fprintf(w, "OK")
}

// splitPath 分割路径
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
