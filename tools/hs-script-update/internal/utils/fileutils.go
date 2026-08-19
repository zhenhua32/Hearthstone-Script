package utils

import (
	"archive/zip"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"syscall"
	"unsafe"
)

// Exists 检查文件或目录是否存在
func Exists(path string) bool {
	_, err := os.Stat(path)
	return err == nil || os.IsExist(err)
}

// IsDirectory 判断是否为目录
func IsDirectory(path string) bool {
	info, err := os.Stat(path)
	if err != nil {
		return false
	}
	return info.IsDir()
}

// CreateDirectory 创建目录
func CreateDirectory(path string) error {
	return os.MkdirAll(path, 0755)
}

// Delete 删除文件或目录
func Delete(path string) error {
	return os.RemoveAll(path)
}

// ListDirectory 列出目录中的所有文件和子目录
func ListDirectory(path string) ([]string, error) {
	entries, err := os.ReadDir(path)
	if err != nil {
		return nil, err
	}

	var names []string
	for _, entry := range entries {
		names = append(names, entry.Name())
	}
	return names, nil
}

// CopyFile 复制文件
func CopyFile(src, dst string) error {
	// 检查目标文件是否是当前正在运行的进程
	if isCurrentProcess(dst) {
		fmt.Printf("跳过更新器文件: %s (正在运行中)\n", dst)
		return nil
	}

	sourceFile, err := os.Open(src)
	if err != nil {
		return err
	}
	defer sourceFile.Close()

	// 创建目标目录
	destDir := filepath.Dir(dst)
	if err := CreateDirectory(destDir); err != nil {
		return err
	}

	// 尝试创建目标文件
	destFile, err := os.Create(dst)
	if err != nil {
		// 检查是否是文件被占用的错误
		if isFileInUseError(err) && Exists(dst) {
			fmt.Printf("检测到文件被占用: %s\n", dst)
			// 处理文件占用
			canProceed, handleErr := HandleLockedFile(dst)
			if handleErr != nil {
				return fmt.Errorf("处理文件占用失败: %w", handleErr)
			}
			if !canProceed {
				// 用户选择跳过此文件
				fmt.Printf("跳过文件: %s\n", dst)
				return nil
			}

			// 重试创建文件
			destFile, err = os.Create(dst)
			if err != nil {
				return fmt.Errorf("处理占用后仍无法创建文件: %w", err)
			}
		} else {
			return err
		}
	}
	defer destFile.Close()

	_, err = io.Copy(destFile, sourceFile)
	return err
}

// isFileInUseError 检查错误是否是文件被占用的错误
func isFileInUseError(err error) bool {
	if err == nil {
		return false
	}
	// Windows: "The process cannot access the file because it is being used by another process."
	// 检查错误信息中是否包含相关关键字
	errMsg := strings.ToLower(err.Error())
	return strings.Contains(errMsg, "being used by another process") ||
		strings.Contains(errMsg, "used by another process") ||
		strings.Contains(errMsg, "access is denied")
}

// isCurrentProcess 检查文件是否是当前正在运行的进程
func isCurrentProcess(filePath string) bool {
	// 获取当前可执行文件路径
	currentExe, err := os.Executable()
	if err != nil {
		return false
	}

	// 解析符号链接
	currentExe, err = filepath.EvalSymlinks(currentExe)
	if err != nil {
		return false
	}

	// 解析目标文件路径
	targetPath, err := filepath.Abs(filePath)
	if err != nil {
		return false
	}

	// 比较路径
	return filepath.Clean(currentExe) == filepath.Clean(targetPath)
}

// CopyDirectory 递归复制目录
func CopyDirectory(src, dst string, excludeDirs, includePluginDirs []string) error {
	if !Exists(dst) {
		if err := CreateDirectory(dst); err != nil {
			return err
		}
	}

	entries, err := ListDirectory(src)
	if err != nil {
		return err
	}

	for _, entry := range entries {
		srcPath := filepath.Join(src, entry)
		dstPath := filepath.Join(dst, entry)

		if IsDirectory(srcPath) {
			// 检查是否需要排除
			if contains(excludeDirs, entry) {
				fmt.Printf("跳过目录: %s\n", srcPath)
				continue
			}

			// 特殊处理 plugin 目录
			if entry == "plugin" && len(includePluginDirs) > 0 {
				if err := copyPluginDirectory(srcPath, dstPath, includePluginDirs); err != nil {
					return err
				}
			} else {
				if err := CopyDirectory(srcPath, dstPath, excludeDirs, includePluginDirs); err != nil {
					return err
				}
			}
		} else {
			fmt.Printf("复制文件: %s -> %s\n", srcPath, dstPath)
			if err := CopyFile(srcPath, dstPath); err != nil {
				return err
			}
		}
	}

	return nil
}

// copyPluginDirectory 复制 plugin 目录，要更新的插件
func copyPluginDirectory(src, dst string, includePluginDirs []string) error {
	if !Exists(dst) {
		if err := CreateDirectory(dst); err != nil {
			return err
		}
	}

	entries, err := ListDirectory(src)
	if err != nil {
		return err
	}

	for _, entry := range entries {
		if contains(includePluginDirs, entry) {
			fmt.Printf("需要更新的插件: %s\n", filepath.Join(src, entry))
			srcPath := filepath.Join(src, entry)
			dstPath := filepath.Join(dst, entry)

			if IsDirectory(srcPath) {
				if err := CopyDirectory(srcPath, dstPath, nil, nil); err != nil {
					return err
				}
			} else {
				fmt.Printf("复制文件: %s -> %s\n", srcPath, dstPath)
				if err := CopyFile(srcPath, dstPath); err != nil {
					return err
				}
			}
		}
	}

	return nil
}

// Unzip 解压 ZIP 文件到指定目录
func Unzip(zipFilePath, destDir string) error {
	fmt.Printf("开始解压: %s -> %s\n", zipFilePath, destDir)

	r, err := zip.OpenReader(zipFilePath)
	if err != nil {
		return err
	}
	defer r.Close()

	for _, f := range r.File {
		// 构造目标路径
		fpath := filepath.Join(destDir, f.Name)

		// 检查路径是否安全（防止 zip slip 攻击）
		if !strings.HasPrefix(fpath, filepath.Clean(destDir)+string(os.PathSeparator)) {
			return fmt.Errorf("非法的文件路径: %s", fpath)
		}

		if f.FileInfo().IsDir() {
			// 创建目录
			if err := CreateDirectory(fpath); err != nil {
				return err
			}
		} else {
			// 创建文件的父目录
			if err := CreateDirectory(filepath.Dir(fpath)); err != nil {
				return err
			}

			// 创建文件
			outFile, err := os.OpenFile(fpath, os.O_WRONLY|os.O_CREATE|os.O_TRUNC, f.Mode())
			if err != nil {
				return err
			}

			rc, err := f.Open()
			if err != nil {
				outFile.Close()
				return err
			}

			_, err = io.Copy(outFile, rc)
			outFile.Close()
			rc.Close()

			if err != nil {
				return err
			}
		}
	}

	fmt.Println("解压完成")
	return nil
}

// FindFile 递归查找文件
func FindFile(dir, fileName string) (string, error) {
	entries, err := ListDirectory(dir)
	if err != nil {
		return "", err
	}

	for _, entry := range entries {
		itemPath := filepath.Join(dir, entry)

		if entry == fileName {
			return itemPath, nil
		}

		if IsDirectory(itemPath) {
			found, err := FindFile(itemPath, fileName)
			if err == nil && found != "" {
				return found, nil
			}
		}
	}

	return "", nil
}

// DetectJVMVersion 检测是否为 JVM 版本
func DetectJVMVersion(targetDir string) bool {
	libDir := filepath.Join(targetDir, "lib")
	if !Exists(libDir) || !IsDirectory(libDir) {
		return false
	}

	files, err := ListDirectory(libDir)
	if err != nil {
		return false
	}

	for _, file := range files {
		if strings.HasSuffix(file, ".jar") {
			return true
		}
	}
	return false
}

// FindExtractedDirectory 查找解压后的实际目录
func FindExtractedDirectory(tempExtractDir string) string {
	items, err := ListDirectory(tempExtractDir)
	if err != nil {
		return tempExtractDir
	}

	// 如果只有一个目录，则返回该目录
	if len(items) == 1 {
		singleItem := filepath.Join(tempExtractDir, items[0])
		if IsDirectory(singleItem) {
			return singleItem
		}
	}

	// 否则返回临时解压目录本身
	return tempExtractDir
}

// getShortPath 获取 Windows 短路径（8.3格式）
func getShortPath(longPath string) string {
	// 转换为绝对路径
	absPath, err := filepath.Abs(longPath)
	if err != nil {
		return longPath
	}

	// 调用 Windows API 获取短路径
	kernel32 := syscall.NewLazyDLL("kernel32.dll")
	getShortPathName := kernel32.NewProc("GetShortPathNameW")

	// 转换为 UTF-16
	longPathUTF16, err := syscall.UTF16PtrFromString(absPath)
	if err != nil {
		return longPath
	}

	// 第一次调用获取所需缓冲区大小
	bufSize, _, _ := getShortPathName.Call(
		uintptr(unsafe.Pointer(longPathUTF16)),
		0,
		0,
	)

	if bufSize == 0 {
		return longPath
	}

	// 分配缓冲区
	shortPathBuf := make([]uint16, bufSize)

	// 第二次调用获取短路径
	ret, _, _ := getShortPathName.Call(
		uintptr(unsafe.Pointer(longPathUTF16)),
		uintptr(unsafe.Pointer(&shortPathBuf[0])),
		uintptr(bufSize),
	)

	if ret == 0 {
		return longPath
	}

	// 转换回字符串
	return syscall.UTF16ToString(shortPathBuf)
}

// HandleSelfUpdate 处理更新器自我更新
func HandleSelfUpdate(tempExtractDir, targetDir string) error {
	// 获取当前可执行文件的名称
	currentExe, err := os.Executable()
	if err != nil {
		return nil // 无法获取当前进程，跳过自更新
	}

	// 解析符号链接
	currentExe, err = filepath.EvalSymlinks(currentExe)
	if err != nil {
		return nil
	}

	// 提取文件名
	updaterName := filepath.Base(currentExe)

	// 在解压的文件中查找同名的更新器
	newUpdaterPath, err := FindFile(tempExtractDir, updaterName)
	if err != nil || newUpdaterPath == "" {
		return nil // 没有找到更新器，跳过
	}

	fmt.Println("\n检测到更新器本身需要更新...")
	currentUpdaterPath := filepath.Join(targetDir, updaterName)
	backupUpdaterPath := currentUpdaterPath + ".bak"

	// 1. 删除旧的备份（如果存在）
	if Exists(backupUpdaterPath) {
		if err := Delete(backupUpdaterPath); err != nil {
			return fmt.Errorf("删除旧备份失败: %w", err)
		}
	}

	// 2. 将新的 updater 复制为备份文件
	fmt.Printf("复制新版本更新器到: %s\n", backupUpdaterPath)
	// 直接使用底层复制，因为 CopyFile 会跳过当前进程
	if err := copyFileDirect(newUpdaterPath, backupUpdaterPath); err != nil {
		return fmt.Errorf("复制新更新器失败: %w", err)
	}

	fmt.Println("更新器将在退出后自动更新")
	fmt.Println("请等待程序完全退出...")

	shortCurrentPath := getShortPath(currentUpdaterPath)
	shortBackupPath := getShortPath(backupUpdaterPath)

	cmdStr := fmt.Sprintf("ping -n 3 127.0.0.1 > nul && del /f /q %s && ren %s %s",
		shortCurrentPath, shortBackupPath, updaterName)

	cmd := exec.Command("cmd", "/c", cmdStr)
	if err := cmd.Start(); err != nil {
		return fmt.Errorf("启动更新命令失败: %w", err)
	}

	return nil
}

// copyFileDirect 直接复制文件（不检查是否是当前进程）
func copyFileDirect(src, dst string) error {
	sourceFile, err := os.Open(src)
	if err != nil {
		return err
	}
	defer sourceFile.Close()

	// 创建目标目录
	destDir := filepath.Dir(dst)
	if err := CreateDirectory(destDir); err != nil {
		return err
	}

	destFile, err := os.Create(dst)
	if err != nil {
		return err
	}
	defer destFile.Close()

	_, err = io.Copy(destFile, sourceFile)
	return err
}

// contains 检查切片中是否包含指定元素
func contains(slice []string, item string) bool {
	for _, s := range slice {
		if strings.HasPrefix(item, s) {
			return true
		}
	}
	return false
}
