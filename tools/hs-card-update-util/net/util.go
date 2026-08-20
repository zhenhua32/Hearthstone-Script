package net

import (
	"fmt"
	"io"
	"log"
	"net/http"
	"net/url"
)

// GetJsonIO 创建 GET 请求并返回响应体。
//
// proxyAddress 非空时将其解释为 host:port，并强制使用 HTTP 代理；为空时遵循
// HTTP_PROXY/HTTPS_PROXY/NO_PROXY 等环境变量。返回值的关闭责任交给调用方。
// 请求构造或发送失败时返回 nil；该函数不缓存响应，也不会把响应体一次性读入内存。
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
				Proxy: http.ProxyURL(proxyURL),
			},
		}
	}

	req, err := http.NewRequest("GET", remoteUrl, nil)
	if err != nil {
		fmt.Println("创建请求失败:", err)
		return nil
	}

	// 部分下载站会拒绝没有 User-Agent 的默认 Go 客户端，因此模拟普通浏览器请求。
	req.Header.Set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")

	resp, err := client.Do(req)
	if err != nil {
		fmt.Println("请求失败:", err)
		return nil
	}
	// 不在这里 defer Close：调用方还需要在函数返回后持续解码响应体。
	return resp.Body
}
