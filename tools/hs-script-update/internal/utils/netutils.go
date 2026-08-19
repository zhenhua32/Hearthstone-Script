package utils

import (
	"crypto/tls"
	"fmt"
	"io"
	"net/http"
	"time"
)

var client *http.Client

func init() {
	// 创建HTTP客户端，禁用SSL验证
	client = &http.Client{
		Timeout: 30 * time.Second,
		Transport: &http.Transport{
			TLSClientConfig: &tls.Config{InsecureSkipVerify: true},
		},
	}
}

// Get 发送GET请求
func Get(url string) (string, error) {
	req, err := http.NewRequest("GET", url, nil)
	if err != nil {
		return "", fmt.Errorf("创建请求失败: %w", err)
	}

	// 添加 User-Agent，GitHub API 要求
	req.Header.Set("User-Agent", "hs-script-updater/1.0")

	resp, err := client.Do(req)
	if err != nil {
		return "", fmt.Errorf("HTTP GET 请求失败: %s, %w", url, err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return "", fmt.Errorf("HTTP请求失败，状态码: %d", resp.StatusCode)
	}

	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return "", fmt.Errorf("读取响应失败: %w", err)
	}

	return string(body), nil
}
