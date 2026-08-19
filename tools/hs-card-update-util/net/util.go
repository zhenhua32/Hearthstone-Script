package net

import (
	"fmt"
	"io"
	"log"
	"net/http"
	"net/url"
)

func GetJsonIO(proxyAddress string, remoteUrl string) io.ReadCloser {
	var client *http.Client
	if proxyAddress == "" {
		client = &http.Client{
			Transport: &http.Transport{
				Proxy: http.ProxyFromEnvironment,
			},
		}
	} else {
		proxyURL, err := url.Parse("http://" + proxyAddress)
		if err != nil {
			log.Fatal(err)
		}
		client = &http.Client{
			Transport: &http.Transport{
				Proxy: http.ProxyURL(proxyURL), // 让 HTTP 客户端使用系统代理
			},
		}
	}

	req, err := http.NewRequest("GET", remoteUrl, nil)
	if err != nil {
		fmt.Println("创建请求失败:", err)
		return nil
	}

	// 添加 User-Agent 头
	req.Header.Set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")

	resp, err := client.Do(req)
	if err != nil {
		fmt.Println("请求失败:", err)
		return nil
	}
	return resp.Body
}
