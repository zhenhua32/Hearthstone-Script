package repository

import (
	"encoding/json"
	"fmt"

	"club.xiaojiawei/hs-script-update/internal/config"
	"club.xiaojiawei/hs-script-update/internal/model"
	"club.xiaojiawei/hs-script-update/internal/utils"
)

// GiteeRepository Gitee 仓库
type GiteeRepository struct{}

// NewGiteeRepository 创建 Gitee 仓库实例
func NewGiteeRepository() *GiteeRepository {
	return &GiteeRepository{}
}

// GetLatestRelease 获取最新版本信息
func (g *GiteeRepository) GetLatestRelease(isPreview bool) (*model.Release, error) {
	url := g.GetLatestReleaseURL(isPreview)
	response, err := utils.Get(url)
	if err != nil {
		return nil, fmt.Errorf("获取最新版本失败: %w", err)
	}

	if isPreview {
		// 预览版：直接返回 latest（包括预发布版本）
		var release model.Release
		if err := json.Unmarshal([]byte(response), &release); err != nil {
			return nil, fmt.Errorf("解析版本信息失败: %w", err)
		}
		return &release, nil
	} else {
		// 正式版：获取所有版本，过滤掉预发布版本，返回最新的
		var releases []model.Release
		if err := json.Unmarshal([]byte(response), &releases); err != nil {
			return nil, fmt.Errorf("解析版本列表失败: %w", err)
		}

		// 倒序遍历，找到最新的非预发布版本
		var latestRelease *model.Release
		for i := len(releases) - 1; i >= 0; i-- {
			release := releases[i]
			if !release.IsPreRelease {
				if latestRelease == nil || release.CompareTo(latestRelease) > 0 {
					latestRelease = &release
				}
			}
		}

		if latestRelease == nil {
			return nil, fmt.Errorf("未找到正式版本")
		}

		return latestRelease, nil
	}
}

// GetLatestReleaseURL 获取最新版本的 API URL
func (g *GiteeRepository) GetLatestReleaseURL(isPreview bool) string {
	if isPreview {
		// 预览版：获取 latest（包括预发布版本）
		return fmt.Sprintf("https://%s/api/v5/repos/%s/%s/releases/latest",
			g.GetDomain(), g.GetUserName(), config.ProjectName)
	}
	// 正式版：获取所有 releases，自己过滤
	return fmt.Sprintf("https://%s/api/v5/repos/%s/%s/releases",
		g.GetDomain(), g.GetUserName(), config.ProjectName)
}

// GetDomain 获取域名
func (g *GiteeRepository) GetDomain() string {
	return "gitee.com"
}

// GetUserName 获取用户名
func (g *GiteeRepository) GetUserName() string {
	return "zergqueen"
}
