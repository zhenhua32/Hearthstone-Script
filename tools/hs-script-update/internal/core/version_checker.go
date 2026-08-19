package core

import (
	"encoding/json"
	"fmt"

	"club.xiaojiawei/hs-script-update/internal/model"
	"club.xiaojiawei/hs-script-update/internal/repository"
)

// VersionChecker 版本检查器
type VersionChecker struct {
	repo repository.Repository
}

// NewVersionChecker 创建版本检查器
func NewVersionChecker(repo repository.Repository) *VersionChecker {
	return &VersionChecker{repo: repo}
}

// GetLatestVersion 获取最新版本信息
func (vc *VersionChecker) GetLatestVersion(checkDev, isNative, interactive bool) (string, error) {
	fmt.Println("获取最新版本信息...")
	fmt.Printf("检查开发版: %v\n", checkDev)
	if isNative {
		fmt.Println("版本类型: Native")
	} else {
		fmt.Println("版本类型: JVM")
	}

	latestRelease, err := vc.repo.GetLatestRelease(checkDev)
	if err != nil {
		return "", fmt.Errorf("获取版本失败: %w", err)
	}

	if latestRelease == nil {
		return "", fmt.Errorf("未找到最新版本信息")
	}

	if interactive {
		// 交互模式：显示到控制台
		fmt.Println("\n========================================")
		fmt.Println("最新版本信息")
		fmt.Println("========================================")
		fmt.Printf("版本号: %s\n", latestRelease.TagName)
		if isNative {
			fmt.Println("版本类型: Native")
		} else {
			fmt.Println("版本类型: JVM")
		}
		if latestRelease.IsPreRelease {
			fmt.Println("预发布: 是")
		} else {
			fmt.Println("预发布: 否")
		}
		if latestRelease.Name != "" {
			fmt.Printf("名称: %s\n", latestRelease.Name)
		}
		if latestRelease.Body != "" {
			fmt.Println("\n更新日志:")
			fmt.Println(latestRelease.Body)
		}
		fmt.Printf("\n下载地址: %s\n", repository.GetReleaseDownloadURL(vc.repo, latestRelease, isNative))
		fmt.Printf("发布页面: %s\n", repository.GetReleasePageURL(vc.repo, latestRelease))
		fmt.Println("========================================\n")
		return "", nil
	}

	// 非交互模式：返回 JSON
	result := map[string]interface{}{
		"tagName":      latestRelease.TagName,
		"isPreRelease": latestRelease.IsPreRelease,
		"isNative":     isNative,
		"name":         latestRelease.Name,
		"body":         latestRelease.Body,
		"downloadUrl":  repository.GetReleaseDownloadURL(vc.repo, latestRelease, isNative),
		"pageUrl":      repository.GetReleasePageURL(vc.repo, latestRelease),
	}

	jsonBytes, err := json.Marshal(result)
	if err != nil {
		return "", fmt.Errorf("生成JSON失败: %w", err)
	}

	return string(jsonBytes), nil
}

// CheckVersion 检查版本更新
func (vc *VersionChecker) CheckVersion(currentVersion string, checkDev, isNative, interactive bool) (string, error) {
	fmt.Println("开始检查更新...")
	fmt.Printf("当前版本: %s\n", currentVersion)
	fmt.Printf("检查开发版: %v\n", checkDev)
	if isNative {
		fmt.Println("版本类型: Native")
	} else {
		fmt.Println("版本类型: JVM")
	}

	latestRelease, err := vc.repo.GetLatestRelease(checkDev)
	if err != nil {
		return "", fmt.Errorf("检查版本失败: %w", err)
	}

	if latestRelease == nil {
		return "", fmt.Errorf("未找到最新版本信息")
	}

	current := &model.Release{TagName: currentVersion}

	if interactive {
		// 交互模式：显示到控制台
		fmt.Println("\n========================================")
		fmt.Println("版本检查结果")
		fmt.Println("========================================")
		fmt.Printf("当前版本: %s\n", current.TagName)
		fmt.Printf("最新版本: %s\n", latestRelease.TagName)
		if isNative {
			fmt.Println("版本类型: Native")
		} else {
			fmt.Println("版本类型: JVM")
		}

		if latestRelease.CompareTo(current) > 0 {
			fmt.Println("状态: 有新版本可用")
			if latestRelease.Body != "" {
				fmt.Println("\n更新日志:")
				fmt.Println(latestRelease.Body)
			}
			fmt.Printf("\n下载地址: %s\n", repository.GetReleaseDownloadURL(vc.repo, latestRelease, isNative))
			fmt.Printf("发布页面: %s\n", repository.GetReleasePageURL(vc.repo, latestRelease))
		} else {
			fmt.Println("状态: 已是最新版本")
		}
		fmt.Println("========================================\n")
		return "", nil
	}

	// 非交互模式：返回 JSON
	if latestRelease.CompareTo(current) > 0 {
		result := map[string]interface{}{
			"hasUpdate":      true,
			"currentVersion": current.TagName,
			"latestVersion":  latestRelease.TagName,
			"isPreRelease":   latestRelease.IsPreRelease,
			"isNative":       isNative,
			"body":           latestRelease.Body,
			"downloadUrl":    repository.GetReleaseDownloadURL(vc.repo, latestRelease, isNative),
			"pageUrl":        repository.GetReleasePageURL(vc.repo, latestRelease),
		}
		jsonBytes, err := json.Marshal(result)
		if err != nil {
			return "", fmt.Errorf("生成JSON失败: %w", err)
		}
		return string(jsonBytes), nil
	}

	result := map[string]interface{}{
		"hasUpdate":      false,
		"currentVersion": current.TagName,
		"latestVersion":  latestRelease.TagName,
		"isNative":       isNative,
	}
	jsonBytes, err := json.Marshal(result)
	if err != nil {
		return "", fmt.Errorf("生成JSON失败: %w", err)
	}
	return string(jsonBytes), nil
}
