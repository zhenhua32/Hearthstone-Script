package progress

import (
	"fmt"
	"strings"
	"time"
)

// PrintProgressBar 在当前终端行绘制一个固定宽度的确定性进度条。
// 调用方必须保证 total 大于 0，并自行决定何时输出换行。
func PrintProgressBar(current, total int) {
	// 计算进度百分比
	percentage := float64(current) / float64(total) * 100

	// 构造进度条
	progress := fmt.Sprintf("[%-50s] %.2f%%", strings.Repeat("#", int(percentage/2)), percentage)

	// 使用 \r 回退光标并覆盖当前行
	fmt.Print("\r" + progress)
}

// 加载动画是供命令行主流程使用的轻量全局状态，只支持单个生产者和单个显示协程。
// 它不是通用并发组件；同一进程中不要同时启动多个 PrintLoadingBar。
var runningPrintLoadingBar bool = false

var printLoadingBarTip = ""

// PrintLoadingBar 持续覆盖当前终端行，直到 CancelPrintLoadingBar 发出停止信号。
// 应在独立 goroutine 中调用，否则会阻塞实际的数据处理流程。
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

// CancelPrintLoadingBar 请求结束加载动画；动画协程会在下一次循环检查时退出。
func CancelPrintLoadingBar() {
	runningPrintLoadingBar = false
}

// SetPrintLoadingBarTip 更新加载动画右侧的进度说明。
func SetPrintLoadingBarTip(tip string) {
	printLoadingBarTip = tip
}
