package storage

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strings"

	"club.xiaojiawei/hs-script-version-server/internal/model"
)

// Storage 定义 HTTP 层需要的只读版本查询能力。
// FileStorage 之外的实现（例如测试内存存储）无需暴露底层文件布局。
type Storage interface {
	GetLatestRelease(prerelease bool) (*model.Release, error)
	GetAllReleases() (model.Releases, error)
}

// FileStorage 以 releases.json 为权威元数据；文件不存在时退化为扫描 zip 文件名。
// 该类型不缓存结果，适合由部署流程原子替换元数据文件后立即提供新版本。
type FileStorage struct {
	releasesDir string // releases 目录路径
}

// NewFileStorage 创建文件存储实例
func NewFileStorage(releasesDir string) *FileStorage {
	return &FileStorage{
		releasesDir: releasesDir,
	}
}

// GetLatestRelease 按 model.Releases 的排序规则返回最新匹配项。
// prerelease=false 时跳过开发、测试和 Beta 版本。
func (fs *FileStorage) GetLatestRelease(prerelease bool) (*model.Release, error) {
	releases, err := fs.GetAllReleases()
	if err != nil {
		return nil, err
	}

	if len(releases) == 0 {
		return nil, fmt.Errorf("no releases found")
	}

	// 排序（已按时间倒序）
	sort.Sort(releases)

	// 查找符合条件的最新版本
	for _, release := range releases {
		if prerelease || !release.Prerelease {
			return &release, nil
		}
	}

	return nil, fmt.Errorf("no matching release found")
}

// GetAllReleases 优先解析 releases.json；仅在文件不存在时扫描目录。
// 元数据文件存在但格式错误会直接报错，避免悄悄发布由文件名推断出的不完整信息。
func (fs *FileStorage) GetAllReleases() (model.Releases, error) {
	// 读取 releases.json 文件
	releasesFile := filepath.Join(fs.releasesDir, "releases.json")

	data, err := os.ReadFile(releasesFile)
	if err != nil {
		// 如果文件不存在，尝试扫描目录
		if os.IsNotExist(err) {
			return fs.scanReleasesFromDirectory()
		}
		return nil, fmt.Errorf("failed to read releases.json: %w", err)
	}

	var releases model.Releases
	if err := json.Unmarshal(data, &releases); err != nil {
		return nil, fmt.Errorf("failed to parse releases.json: %w", err)
	}

	// 排序
	sort.Sort(releases)

	return releases, nil
}

// scanReleasesFromDirectory 从 hs-script_{tag}.zip 文件名构造最小 Release 元数据。
// 创建和发布时间使用文件修改时间，预发布状态由 tag 中的约定后缀推断。
func (fs *FileStorage) scanReleasesFromDirectory() (model.Releases, error) {
	entries, err := os.ReadDir(fs.releasesDir)
	if err != nil {
		return nil, fmt.Errorf("failed to read releases directory: %w", err)
	}

	var releases model.Releases
	for _, entry := range entries {
		if entry.IsDir() {
			continue
		}

		// 解析文件名，格式：hs-script_v4.13.0-GA.zip
		name := entry.Name()
		if !strings.HasPrefix(name, "hs-script_") || !strings.HasSuffix(name, ".zip") {
			continue
		}

		// 提取版本号
		tagName := strings.TrimPrefix(name, "hs-script_")
		tagName = strings.TrimSuffix(tagName, ".zip")

		// 获取文件信息
		info, err := entry.Info()
		if err != nil {
			continue
		}

		// 判断是否是预发布版本
		prerelease := strings.Contains(tagName, "-DEV") ||
			strings.Contains(tagName, "-BETA") ||
			strings.Contains(tagName, "-TEST")

		releases = append(releases, model.Release{
			TagName:     tagName,
			Name:        tagName,
			Body:        "",
			Prerelease:  prerelease,
			PublishedAt: info.ModTime(),
			CreatedAt:   info.ModTime(),
		})
	}

	sort.Sort(releases)
	return releases, nil
}

// SaveReleases 以缩进 JSON 保存完整版本列表，主要供发布管理程序使用。
func (fs *FileStorage) SaveReleases(releases model.Releases) error {
	releasesFile := filepath.Join(fs.releasesDir, "releases.json")

	data, err := json.MarshalIndent(releases, "", "  ")
	if err != nil {
		return fmt.Errorf("failed to marshal releases: %w", err)
	}

	if err := os.WriteFile(releasesFile, data, 0644); err != nil {
		return fmt.Errorf("failed to write releases.json: %w", err)
	}

	return nil
}
