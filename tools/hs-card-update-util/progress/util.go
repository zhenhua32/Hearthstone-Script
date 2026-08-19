package progress

import (
	"fmt"
	"strings"
	"time"
)

func PrintProgressBar(current, total int) {
	// 计算进度百分比
	percentage := float64(current) / float64(total) * 100

	// 构造进度条
	progress := fmt.Sprintf("[%-50s] %.2f%%", strings.Repeat("#", int(percentage/2)), percentage)

	// 使用 \r 回退光标并覆盖当前行
	fmt.Print("\r" + progress)
}

var runningPrintLoadingBar bool = false

var printLoadingBarTip = ""

func PrintLoadingBar() {
	runningPrintLoadingBar = true
	// 定义进度条的显示符号
	loadingSymbols := []string{"|", "/", "-", "\\"}
	//loadingSymbols := []string{"◜", "◝", "◞", "◟"}
	//loadingSymbols := []string{"⏳", "⌛"}

	// 无限循环模拟加载
	for runningPrintLoadingBar {
		for _, symbol := range loadingSymbols {
			// 使用 \r 回退光标并显示进度条
			fmt.Print("\r⏳ " + symbol + " " + printLoadingBarTip)
			time.Sleep(20 * time.Millisecond) // 模拟处理延时
		}
	}
}

func CancelPrintLoadingBar() {
	runningPrintLoadingBar = false
}

func SetPrintLoadingBarTip(tip string) {
	printLoadingBarTip = tip
}
