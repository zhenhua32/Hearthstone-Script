package repository

import (
	"encoding/json"
	"fmt"

	"club.xiaojiawei/hs-script-update/internal/config"
	"club.xiaojiawei/hs-script-update/internal/model"
	"club.xiaojiawei/hs-script-update/internal/utils"
)

// GitHubRepository GitHub 仓库
type GitHubRepository struct{}

// NewGitHubRepository 创建 GitHub 仓库实例
func NewGitHubRepository() *GitHubRepository {
	return &GitHubRepository{}
}

// GetLatestRelease 获取最新版本信息
func (g *GitHubRepository) GetLatestRelease(isPreview bool) (*model.Release, error) {
	url := g.GetLatestReleaseURL(isPreview)
	response, err := utils.Get(url)
	if err != nil {
		return nil, fmt.Errorf("获取最新版本失败: %w", err)
	}

	if isPreview {
		// 预览版：获取所有 releases，取第一个（最新的，可能是预发布）
		var releases []model.Release
		if err := json.Unmarshal([]byte(response), &releases); err != nil {
			return nil, fmt.Errorf("解析版本列表失败: %w", err)
		}
		if len(releases) == 0 {
			return nil, fmt.Errorf("未找到任何版本")
		}
		return &releases[0], nil
	} else {
		// 正式版：直接返回 latest（GitHub 的 latest 是最新的非预发布版本）
		var release model.Release
		if err := json.Unmarshal([]byte(response), &release); err != nil {
			return nil, fmt.Errorf("解析版本信息失败: %w", err)
		}
		return &release, nil
	}
}

// GetLatestReleaseURL 获取最新版本的 API URL
func (g *GitHubRepository) GetLatestReleaseURL(isPreview bool) string {
	if isPreview {
		// 预览版：获取所有 releases
		return fmt.Sprintf("https://api.%s/repos/%s/%s/releases",
			g.GetDomain(), g.GetUserName(), config.ProjectName)
	}
	// 正式版：获取 latest（GitHub 的 latest 默认是非预发布版本）
	return fmt.Sprintf("https://api.%s/repos/%s/%s/releases/latest",
		g.GetDomain(), g.GetUserName(), config.ProjectName)
}

// GetDomain 获取域名
func (g *GitHubRepository) GetDomain() string {
	return "github.com"
}

// GetUserName 获取用户名
func (g *GitHubRepository) GetUserName() string {
	return "xjw580" // 根据实际 GitHub 用户名修改
}
