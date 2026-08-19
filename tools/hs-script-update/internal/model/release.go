package model

import (
	"fmt"
	"regexp"
	"strconv"
	"strings"
)

// Release 版本发布信息
type Release struct {
	TagName      string `json:"tag_name"`
	IsPreRelease bool   `json:"prerelease"`
	Name         string `json:"name,omitempty"`
	Body         string `json:"body,omitempty"`
}

// FileName 返回文件名
func (r *Release) FileName(isNative bool) string {
	if isNative {
		return fmt.Sprintf("hs-script-native_%s.zip", r.TagName)
	}
	return fmt.Sprintf("hs-script_%s.zip", r.TagName)
}

// CompareTo 比较版本大小
// 返回值: 1 表示 r > other, 0 表示相等, -1 表示 r < other
func (r *Release) CompareTo(other *Release) int {
	if other.TagName == "" {
		return 1
	}
	return CompareVersion(r.TagName, other.TagName)
}

// CompareVersion 比较版本号大小，支持v前缀
func CompareVersion(version1, version2 string) int {
	regex := regexp.MustCompile(`\d+(\.\d+)*`)
	match1 := regex.FindString(version1)
	match2 := regex.FindString(version2)

	if match1 == "" || match2 == "" {
		fmt.Printf("版本号有误，version1：%s，version2：%s\n", version1, version2)
		return 0
	}

	v1Parts := strings.Split(match1, ".")
	v2Parts := strings.Split(match2, ".")

	// 转换为整数切片
	v1 := make([]int, len(v1Parts))
	v2 := make([]int, len(v2Parts))

	for i, p := range v1Parts {
		v1[i], _ = strconv.Atoi(p)
	}
	for i, p := range v2Parts {
		v2[i], _ = strconv.Atoi(p)
	}

	// 比较版本号
	minLen := len(v1)
	if len(v2) < minLen {
		minLen = len(v2)
	}

	for i := 0; i < minLen; i++ {
		if v1[i] > v2[i] {
			return 1
		} else if v1[i] < v2[i] {
			return -1
		}
	}

	// 如果长度相同，比较版本类型 (GA > RC > BETA > ALPHA)
	if len(v1) == len(v2) {
		split1 := strings.Split(version1, "-")
		split2 := strings.Split(version2, "-")

		if len(split1) > 1 && len(split2) > 1 {
			order1 := getVersionTypeOrder(split1[1])
			order2 := getVersionTypeOrder(split2[1])
			if order1 > order2 {
				return 1
			} else if order1 < order2 {
				return -1
			}
			return 0
		}

		if len(split1) > len(split2) {
			return 1
		} else if len(split1) < len(split2) {
			return -1
		}
		return 0
	}

	// 比较长度
	if len(v1) > len(v2) {
		return 1
	} else if len(v1) < len(v2) {
		return -1
	}
	return 0
}

// getVersionTypeOrder 获取版本类型的优先级
func getVersionTypeOrder(versionType string) int {
	upper := strings.ToUpper(versionType)
	if strings.Contains(upper, "GA") {
		return 10
	} else if strings.Contains(upper, "PATCH") {
		return 8
	} else if strings.Contains(upper, "DEV") {
		return 4
	} else if strings.Contains(upper, "BETA") {
		return 2
	} else if strings.Contains(upper, "TEST") {
		return -1
	}
	return 0
}
