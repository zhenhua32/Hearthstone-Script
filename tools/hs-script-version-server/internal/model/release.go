package model

import (
	"time"
)

// Release 发布版本信息
type Release struct {
	TagName    string    `json:"tag_name"`    // 版本标签，如 v4.13.0-GA
	Name       string    `json:"name"`        // 发布名称
	Body       string    `json:"body"`        // 发布说明
	Prerelease bool      `json:"prerelease"`  // 是否是预发布版本
	CreatedAt  time.Time `json:"created_at"`  // 创建时间
	PublishedAt time.Time `json:"published_at"` // 发布时间
}

// Releases 发布版本列表
type Releases []Release

// Len 实现 sort.Interface
func (r Releases) Len() int {
	return len(r)
}

// Less 实现 sort.Interface，按发布时间倒序
func (r Releases) Less(i, j int) bool {
	return r[i].PublishedAt.After(r[j].PublishedAt)
}

// Swap 实现 sort.Interface
func (r Releases) Swap(i, j int) {
	r[i], r[j] = r[j], r[i]
}
