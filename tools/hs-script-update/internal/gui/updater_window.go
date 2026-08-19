package gui

import (
	"fmt"
	"sync"
	"time"

	"github.com/lxn/walk"
	. "github.com/lxn/walk/declarative"
)

// UpdaterWindow GUI更新窗口
type UpdaterWindow struct {
	mainWindow      *walk.MainWindow
	progressBar     *walk.ProgressBar
	statusLabel     *walk.Label
	countdownLabel  *walk.Label
	detailText      *walk.TextEdit
	mu              sync.Mutex
	maxProgress     int
	currentProgress int
}

// NewUpdaterWindow 创建更新窗口
func NewUpdaterWindow() *UpdaterWindow {
	return &UpdaterWindow{
		maxProgress: 100,
	}
}

// Show 显示窗口
func (uw *UpdaterWindow) Show() error {
	uw.mu.Lock()
	defer uw.mu.Unlock()

	err := MainWindow{
		AssignTo: &uw.mainWindow,
		Title:    "HS-Script 更新器",
		MinSize:  Size{Width: 600, Height: 400},
		Size:     Size{Width: 700, Height: 500},
		Layout:   VBox{},
		Children: []Widget{
			Label{
				AssignTo: &uw.statusLabel,
				Text:     "准备更新...",
				Font:     Font{PointSize: 10, Bold: true},
			},
			Label{
				AssignTo: &uw.countdownLabel,
				Text:     "",
				Font:     Font{PointSize: 9},
				Visible:  false,
			},
			ProgressBar{
				AssignTo: &uw.progressBar,
				MinValue: 0,
				MaxValue: 100,
				Value:    0,
			},
			HSpacer{},
			Label{
				Text: "详细信息:",
				Font: Font{PointSize: 9},
			},
			TextEdit{
				AssignTo: &uw.detailText,
				ReadOnly: true,
				VScroll:  true,
				Font:     Font{Family: "Consolas", PointSize: 9},
			},
		},
	}.Create()

	if err != nil {
		return fmt.Errorf("创建窗口失败: %w", err)
	}

	// 显示窗口
	uw.mainWindow.Show()

	return nil
}

// Run 运行窗口消息循环
func (uw *UpdaterWindow) Run() {
	if uw.mainWindow != nil {
		uw.mainWindow.Run()
	}
}

// SetStatus 设置状态文本
func (uw *UpdaterWindow) SetStatus(status string) {
	uw.mu.Lock()
	defer uw.mu.Unlock()

	if uw.statusLabel != nil {
		uw.statusLabel.Synchronize(func() {
			uw.statusLabel.SetText(status)
		})
	}
}

// SetProgress 设置进度
func (uw *UpdaterWindow) SetProgress(current, max int) {
	uw.mu.Lock()
	defer uw.mu.Unlock()

	uw.currentProgress = current
	uw.maxProgress = max

	if uw.progressBar != nil {
		uw.progressBar.Synchronize(func() {
			uw.progressBar.SetRange(0, max)
			uw.progressBar.SetValue(current)
		})
	}
}

// AppendDetail 追加详细信息
func (uw *UpdaterWindow) AppendDetail(detail string) {
	uw.mu.Lock()
	defer uw.mu.Unlock()

	if uw.detailText != nil {
		uw.detailText.Synchronize(func() {
			text := uw.detailText.Text()
			uw.detailText.SetText(text + detail + "\r\n")
			// 滚动到底部
			uw.detailText.SetTextSelection(len(uw.detailText.Text()), len(uw.detailText.Text()))
		})
	}
}

// ShowError 显示错误对话框
func (uw *UpdaterWindow) ShowError(message string) {
	if uw.mainWindow != nil {
		uw.mainWindow.Synchronize(func() {
			walk.MsgBox(uw.mainWindow, "错误", message, walk.MsgBoxIconError)
		})
	} else {
		walk.MsgBox(nil, "错误", message, walk.MsgBoxIconError)
	}
}

// ShowSuccess 显示成功对话框并在10秒后自动关闭窗口
func (uw *UpdaterWindow) ShowSuccess(message string) {
	if uw.mainWindow != nil {
		uw.mainWindow.Synchronize(func() {
			walk.MsgBox(uw.mainWindow, "成功", message, walk.MsgBoxIconInformation)
		})
	} else {
		walk.MsgBox(nil, "成功", message, walk.MsgBoxIconInformation)
	}

	// 10秒后自动关闭窗口
	uw.CloseAfterDelay(10)
}

// Close 关闭窗口
func (uw *UpdaterWindow) Close() {
	uw.mu.Lock()
	defer uw.mu.Unlock()

	if uw.mainWindow != nil {
		uw.mainWindow.Synchronize(func() {
			uw.mainWindow.Close()
		})
	}
}

// IsVisible 窗口是否可见
func (uw *UpdaterWindow) IsVisible() bool {
	uw.mu.Lock()
	defer uw.mu.Unlock()

	return uw.mainWindow != nil && uw.mainWindow.Visible()
}

// CloseAfterDelay 延迟关闭窗口（显示倒计时）
func (uw *UpdaterWindow) CloseAfterDelay(seconds int) {
	if uw.mainWindow == nil {
		return
	}

	go func() {
		// 倒计时
		for i := seconds; i > 0; i-- {
			remaining := i // 捕获当前值

			// 更新 UI（非阻塞方式）
			if uw.mainWindow != nil && uw.countdownLabel != nil {
				uw.mainWindow.Synchronize(func() {
					uw.countdownLabel.SetVisible(true)
					uw.countdownLabel.SetText(fmt.Sprintf("窗口将在 %d 秒后自动关闭...", remaining))
				})
			}

			time.Sleep(1 * time.Second)
		}

		// 倒计时结束，关闭窗口
		if uw.mainWindow != nil {
			uw.mainWindow.Synchronize(func() {
				uw.mainWindow.Close()
			})
		}
	}()
}
