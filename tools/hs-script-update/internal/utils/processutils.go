package utils

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strings"
	"syscall"
	"time"
	"unsafe"
)

// IsFileLocked 检测文件是否被占用
func IsFileLocked(filePath string) bool {
	// 如果文件不存在，则不存在占用问题
	if !Exists(filePath) {
		return false
	}

	// 尝试以独占方式打开文件
	file, err := os.OpenFile(filePath, os.O_RDWR|os.O_CREATE, 0666)
	if err != nil {
		// 如果打开失败，可能是被占用
		return true
	}
	file.Close()
	return false
}

// ProcessInfo 进程信息
type ProcessInfo struct {
	PID  string
	Name string
	Path string
}

// FindProcessesUsingFile 查找占用文件的进程
func FindProcessesUsingFile(filePath string) ([]ProcessInfo, error) {
	absPath, err := filepath.Abs(filePath)
	if err != nil {
		return nil, err
	}

	// 使用 handle.exe 或 openfiles（需要管理员权限）
	// 这里我们尝试使用 tasklist 和逻辑推断
	// 更可靠的方法是使用 Restart Manager API，但需要更多代码

	// 方法1: 尝试使用简单的方式 - 检查常见进程
	processes := []ProcessInfo{}

	// 获取文件名（无扩展名）
	fileName := filepath.Base(absPath)
	fileNameWithoutExt := strings.TrimSuffix(fileName, filepath.Ext(fileName))

	// 执行 tasklist 命令
	cmd := exec.Command("tasklist", "/FO", "CSV", "/NH", "/V")
	output, err := cmd.Output()
	if err != nil {
		return nil, fmt.Errorf("执行 tasklist 失败: %w", err)
	}

	// 解析输出
	lines := strings.Split(string(output), "\n")
	csvPattern := regexp.MustCompile(`"([^"]*)"`)

	for _, line := range lines {
		if strings.TrimSpace(line) == "" {
			continue
		}

		matches := csvPattern.FindAllStringSubmatch(line, -1)
		if len(matches) < 2 {
			continue
		}

		processName := matches[0][1]
		pid := matches[1][1]

		// 检查进程名是否与文件名相关
		if strings.Contains(strings.ToLower(processName), strings.ToLower(fileNameWithoutExt)) {
			processes = append(processes, ProcessInfo{
				PID:  pid,
				Name: processName,
				Path: "", // tasklist 不直接提供路径
			})
		}
	}

	return processes, nil
}

// KillProcess 杀死进程
func KillProcess(pid string) error {
	cmd := exec.Command("taskkill", "/F", "/PID", pid)
	output, err := cmd.CombinedOutput()
	if err != nil {
		return fmt.Errorf("杀死进程失败 (PID: %s): %w\n%s", pid, err, string(output))
	}
	fmt.Printf("成功杀死进程 (PID: %s)\n", pid)
	return nil
}

// Windows MessageBox 常量
const (
	MB_OK              = 0x00000000
	MB_YESNO           = 0x00000004
	MB_ICONQUESTION    = 0x00000020
	MB_ICONINFORMATION = 0x00000040
	MB_ICONERROR       = 0x00000010
	MB_SETFOREGROUND   = 0x00010000
	IDYES              = 6
	IDNO               = 7
	WM_CLOSE           = 0x0010
)

// ShowMessageBox 显示信息提示框
func ShowMessageBox(message, title string) {
	user32 := syscall.NewLazyDLL("user32.dll")
	messageBox := user32.NewProc("MessageBoxW")

	messagePtr, _ := syscall.UTF16PtrFromString(message)
	titlePtr, _ := syscall.UTF16PtrFromString(title)

	messageBox.Call(
		0, // hwnd
		uintptr(unsafe.Pointer(messagePtr)),
		uintptr(unsafe.Pointer(titlePtr)),
		uintptr(MB_OK|MB_ICONINFORMATION|MB_SETFOREGROUND),
	)
}

// ShowErrorBox 显示错误提示框
func ShowErrorBox(message, title string) {
	user32 := syscall.NewLazyDLL("user32.dll")
	messageBox := user32.NewProc("MessageBoxW")

	messagePtr, _ := syscall.UTF16PtrFromString(message)
	titlePtr, _ := syscall.UTF16PtrFromString(title)

	messageBox.Call(
		0, // hwnd
		uintptr(unsafe.Pointer(messagePtr)),
		uintptr(unsafe.Pointer(titlePtr)),
		uintptr(MB_OK|MB_ICONERROR|MB_SETFOREGROUND),
	)
}

// AskUserWithTimeout 询问用户，带超时（使用 MessageBox）
func AskUserWithTimeout(question string, timeoutMinutes int) bool {
	user32 := syscall.NewLazyDLL("user32.dll")
	messageBox := user32.NewProc("MessageBoxW")
	findWindow := user32.NewProc("FindWindowW")
	sendMessage := user32.NewProc("SendMessageW")

	// 构建提示文本
	message := fmt.Sprintf("%s\n\n如果 %d 分钟内没有回应，将默认执行操作。", question, timeoutMinutes)
	messagePtr, _ := syscall.UTF16PtrFromString(message)
	titlePtr, _ := syscall.UTF16PtrFromString("HS-Script 更新器")

	// 创建一个通道来接收用户选择
	responseChan := make(chan int, 1)

	// 在新的 goroutine 中显示 MessageBox
	go func() {
		ret, _, _ := messageBox.Call(
			0, // hwnd
			uintptr(unsafe.Pointer(messagePtr)),
			uintptr(unsafe.Pointer(titlePtr)),
			uintptr(MB_YESNO|MB_ICONQUESTION|MB_SETFOREGROUND),
		)
		responseChan <- int(ret)
	}()

	// 等待用户响应或超时
	timeout := time.Duration(timeoutMinutes) * time.Minute
	select {
	case response := <-responseChan:
		// 收到用户响应
		fmt.Printf("用户选择: %s\n", map[int]string{IDYES: "是", IDNO: "否"}[response])
		return response == IDYES
	case <-time.After(timeout):
		// 超时，关闭 MessageBox
		fmt.Printf("超时（%d 分钟），默认执行操作。\n", timeoutMinutes)

		// 查找 MessageBox 窗口并关闭
		titleSearchPtr, _ := syscall.UTF16PtrFromString("HS-Script 更新器")
		hwnd, _, _ := findWindow.Call(
			0, // lpClassName
			uintptr(unsafe.Pointer(titleSearchPtr)),
		)

		if hwnd != 0 {
			// 发送关闭消息
			sendMessage.Call(hwnd, WM_CLOSE, 0, 0)
		}

		return true // 超时默认执行
	}
}

// HandleLockedFile 处理被占用的文件
// 返回 true 表示已处理，可以继续复制；false 表示用户拒绝，跳过复制
func HandleLockedFile(filePath string) (bool, error) {
	fmt.Printf("\n警告: 文件被占用: %s\n", filePath)

	// 查找占用文件的进程
	processes, err := FindProcessesUsingFile(filePath)
	if err != nil {
		return false, fmt.Errorf("查找占用进程失败: %w", err)
	}

	if len(processes) == 0 {
		fmt.Println("未找到占用进程，但文件仍被占用。")
		fmt.Println("可能需要手动关闭相关程序后重试。")

		// 询问用户是否重试
		question := fmt.Sprintf("文件被占用:\n%s\n\n未找到占用进程，可能需要手动关闭相关程序。\n\n是否等待并重试？", filePath)
		if AskUserWithTimeout(question, 5) {
			// 等待一下，给用户时间关闭程序
			time.Sleep(2 * time.Second)
			return true, nil
		}
		return false, fmt.Errorf("文件仍被占用，跳过: %s", filePath)
	}

	// 构建占用进程信息
	processInfo := ""
	for i, proc := range processes {
		processInfo += fmt.Sprintf("%d. 进程名: %s, PID: %s\n", i+1, proc.Name, proc.PID)
		fmt.Printf("  %d. 进程名: %s, PID: %s\n", i+1, proc.Name, proc.PID)
	}

	// 询问用户是否杀死这些进程
	question := fmt.Sprintf("文件被占用:\n%s\n\n找到以下占用进程:\n%s\n是否杀死这些进程以继续更新？", filePath, processInfo)
	if AskUserWithTimeout(question, 5) {
		// 杀死所有占用进程
		for _, proc := range processes {
			if err := KillProcess(proc.PID); err != nil {
				fmt.Printf("警告: %v\n", err)
			}
		}

		// 等待一下，确保进程已完全退出
		time.Sleep(1 * time.Second)

		fmt.Println("进程已杀死，可以继续复制文件。")
		return true, nil
	}

	// 用户拒绝杀死进程
	fmt.Printf("用户拒绝杀死进程，跳过文件: %s\n", filePath)
	return false, nil
}

// IsUpdaterProcess 检查进程是否是更新器进程
func IsUpdaterProcess(processName string) bool {
	currentExe, err := os.Executable()
	if err != nil {
		return false
	}

	currentName := filepath.Base(currentExe)
	return strings.EqualFold(processName, currentName)
}

// IsProcessRunning 检查进程是否正在运行
func IsProcessRunning(pid int) bool {
	if pid <= 0 {
		return false
	}

	// 使用 tasklist 检查进程是否存在
	cmd := exec.Command("tasklist", "/FI", fmt.Sprintf("PID eq %d", pid), "/NH", "/FO", "CSV")
	output, err := cmd.Output()
	if err != nil {
		return false
	}

	// 如果输出包含 PID，说明进程存在
	return strings.Contains(string(output), fmt.Sprintf("\"%d\"", pid))
}

// WaitForProcessExit 等待指定进程退出，超时后强制杀死进程
func WaitForProcessExit(pid int, maxWaitSeconds int) error {
	if pid <= 0 {
		return nil // 无效 PID，跳过等待
	}

	fmt.Printf("等待主程序退出 (PID: %d)...\n", pid)

	checkInterval := 500 * time.Millisecond
	maxChecks := maxWaitSeconds * 2 // 每 500ms 检查一次

	for i := 0; i < maxChecks; i++ {
		if !IsProcessRunning(pid) {
			fmt.Println("主程序已退出")
			return nil
		}
		time.Sleep(checkInterval)

		// 每 2 秒显示一次等待信息
		if (i+1)%4 == 0 {
			fmt.Printf("仍在等待主程序退出... (%d/%d 秒)\n", (i+1)/2, maxWaitSeconds)
		}
	}

	// 超时后强制杀死进程
	fmt.Printf("\n等待超时，强制杀死主程序 (PID: %d)\n", pid)
	pidStr := fmt.Sprintf("%d", pid)
	if err := KillProcess(pidStr); err != nil {
		return fmt.Errorf("杀死主程序失败: %w", err)
	}

	// 等待一秒确保进程完全退出
	time.Sleep(1 * time.Second)
	return nil
}

// StartProgram 启动主程序
func StartProgram(programPath string, isPause bool) error {
	if programPath == "" {
		return fmt.Errorf("程序路径为空")
	}

	if !Exists(programPath) {
		return fmt.Errorf("程序不存在: %s", programPath)
	}

	fmt.Printf("启动主程序: %s\n", programPath)

	// 构建启动命令
	var cmd *exec.Cmd
	if isPause {
		// 如果主程序之前处于暂停状态，不传递 --pause 参数（让它恢复正常运行）
		cmd = exec.Command(programPath)
	} else {
		// 正常启动
		cmd = exec.Command(programPath)
	}

	// 启动程序（不等待其退出）
	if err := cmd.Start(); err != nil {
		return fmt.Errorf("启动程序失败: %w", err)
	}

	fmt.Printf("主程序已启动，PID: %d\n", cmd.Process.Pid)
	return nil
}
