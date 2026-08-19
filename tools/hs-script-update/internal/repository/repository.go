package repository

import (
	"fmt"

	"club.xiaojiawei/hs-script-update/internal/config"
	"club.xiaojiawei/hs-script-update/internal/model"
)

// Repository 版本仓库接口
type Repository interface {
	// GetLatestRelease 获取最新版本信息
	GetLatestRelease(isPreview bool) (*model.Release, error)

	// GetLatestReleaseURL 获取最新版本的 API URL
	GetLatestReleaseURL(isPreview bool) string

	// GetDomain 获取域名
	GetDomain() string

	// GetUserName 获取用户名
	GetUserName() string
}

// GetReleaseDownloadURL 获取版本下载URL
func GetReleaseDownloadURL(repo Repository, release *model.Release, isNative bool) string {
	return fmt.Sprintf("https://%s/%s/%s/releases/download/%s/%s",
		repo.GetDomain(),
		repo.GetUserName(),
		config.ProjectName,
		release.TagName,
		release.FileName(isNative))
}

// GetReleasePageURL 获取版本发布页面URL
func GetReleasePageURL(repo Repository, release *model.Release) string {
	return fmt.Sprintf("https://%s/%s/%s/releases/tag/%s",
		repo.GetDomain(),
		repo.GetUserName(),
		config.ProjectName,
		release.TagName)
}
