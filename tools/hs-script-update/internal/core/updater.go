package core

import (
	"fmt"
	"path/filepath"
	"strings"

	"club.xiaojiawei/hs-script-update/internal/config"
	"club.xiaojiawei/hs-script-update/internal/utils"
)

// ProgressCallback 进度回调接口
type ProgressCallback interface {
	SetStatus(status string)
	SetProgress(current, max int)
	AppendDetail(detail string)
	ShowError(message string)
	ShowSuccess(message string)
}

// Updater 更新器核心
type Updater struct {
	zipFilePath    string
	targetDir      string
	tempExtractDir string
	isPause        bool
	mainPid        int
	mainProgram    string
	progress       ProgressCallback
}

// NewUpdater 创建更新器实例
func NewUpdater(zipFilePath, targetDir string, isPause bool, mainPid int, mainProgram string) *Updater {
	return &Updater{
		zipFilePath:    zipFilePath,
		targetDir:      targetDir,
		tempExtractDir: filepath.Join(targetDir, "_temp_update"),
		isPause:        isPause,
		mainPid:        mainPid,
		mainProgram:    mainProgram,
	}
}

// SetProgressCallback 设置进度回调
func (u *Updater) SetProgressCallback(callback ProgressCallback) {
	u.progress = callback
}

// logStatus 记录状态（同时输出到控制台和GUI）
func (u *Updater) logStatus(status string) {
	fmt.Println(status)
	if u.progress != nil {
		u.progress.SetStatus(status)
		u.progress.AppendDetail(status)
	}
}

// logDetail 记录详细信息
func (u *Updater) logDetail(detail string) {
	fmt.Println(detail)
	if u.progress != nil {
		u.progress.AppendDetail(detail)
	}
}

// updateProgress 更新进度
func (u *Updater) updateProgress(current, max int) {
	if u.progress != nil {
		u.progress.SetProgress(current, max)
	}
}

// Update 执行更新
func (u *Updater) Update() error {
	u.logStatus("========================================")
	u.logStatus("开始更新程序")
	u.logStatus("========================================")
	u.logDetail(fmt.Sprintf("更新包: %s", u.zipFilePath))
	u.logDetail(fmt.Sprintf("目标目录: %s", u.targetDir))
	u.logDetail(fmt.Sprintf("暂停状态: %v", u.isPause))
	u.updateProgress(0, 100)

	// 0. 等待主程序退出
	if u.mainPid > 0 {
		u.logDetail(fmt.Sprintf("主程序 PID: %d", u.mainPid))
		u.logStatus("等待主程序退出...")
		u.updateProgress(5, 100)
		if err := utils.WaitForProcessExit(u.mainPid, 5); err != nil {
			errMsg := fmt.Sprintf("等待主程序退出失败: %v", err)
			if u.progress != nil {
				u.progress.ShowError(errMsg)
			}
			return fmt.Errorf("等待主程序退出失败: %w", err)
		}
	}

	// 1. 检查更新包是否存在
	u.logStatus("检查更新包...")
	u.updateProgress(10, 100)
	if !utils.Exists(u.zipFilePath) {
		errMsg := fmt.Sprintf("更新包不存在: %s", u.zipFilePath)
		if u.progress != nil {
			u.progress.ShowError(errMsg)
		}
		return fmt.Errorf("更新包不存在: %s", u.zipFilePath)
	}

	// 2. 检查目标目录是否存在
	u.updateProgress(15, 100)
	if !utils.Exists(u.targetDir) {
		errMsg := fmt.Sprintf("目标目录不存在: %s", u.targetDir)
		if u.progress != nil {
			u.progress.ShowError(errMsg)
		}
		return fmt.Errorf("目标目录不存在: %s", u.targetDir)
	}

	// 3. 清理临时目录
	u.updateProgress(20, 100)
	if utils.Exists(u.tempExtractDir) {
		u.logStatus("清理旧的临时目录...")
		if err := utils.Delete(u.tempExtractDir); err != nil {
			return fmt.Errorf("清理临时目录失败: %w", err)
		}
	}

	// 4. 创建临时目录并解压
	u.updateProgress(25, 100)
	if err := utils.CreateDirectory(u.tempExtractDir); err != nil {
		return fmt.Errorf("创建临时目录失败: %w", err)
	}

	u.logStatus("解压更新包...")
	u.updateProgress(30, 100)
	if err := utils.Unzip(u.zipFilePath, u.tempExtractDir); err != nil {
		u.cleanup()
		errMsg := fmt.Sprintf("解压失败: %v", err)
		if u.progress != nil {
			u.progress.ShowError(errMsg)
		}
		return fmt.Errorf("解压失败: %w", err)
	}

	// 5. 判断是 JVM 版还是 Native 版
	u.updateProgress(50, 100)
	isJvmVersion := utils.DetectJVMVersion(u.targetDir)
	if isJvmVersion {
		u.logDetail("检测到版本类型: JVM")
	} else {
		u.logDetail("检测到版本类型: Native")
	}

	// 6. 执行更新
	u.logStatus("执行更新...")
	u.updateProgress(60, 100)
	if err := u.performUpdate(isJvmVersion); err != nil {
		u.cleanup()
		if u.progress != nil {
			u.progress.ShowError(fmt.Sprintf("更新失败: %v", err))
		}
		return err
	}

	// 7. 清理临时目录
	u.logStatus("清理临时文件...")
	u.updateProgress(90, 100)
	if err := utils.Delete(u.tempExtractDir); err != nil {
		u.logDetail(fmt.Sprintf("警告: 清理临时目录失败: %v", err))
	}

	// 8. 删除更新包（如果在目标目录中）
	if strings.HasPrefix(u.zipFilePath, u.targetDir) {
		u.logDetail(fmt.Sprintf("删除更新包: %s", u.zipFilePath))
		if err := utils.Delete(u.zipFilePath); err != nil {
			u.logDetail(fmt.Sprintf("警告: 删除更新包失败: %v", err))
		}
	}

	u.logStatus("========================================")
	u.logStatus("更新完成！")
	u.logStatus("========================================")
	u.updateProgress(100, 100)

	// 9. 启动主程序（如果提供了路径）
	if u.mainProgram != "" {
		if err := utils.StartProgram(u.mainProgram, u.isPause); err != nil {
			u.logDetail(fmt.Sprintf("警告: 启动主程序失败: %v", err))
			successMsg := fmt.Sprintf("软件已成功更新！\n\n但启动主程序失败：%v\n\n请手动启动程序。", err)
			if u.progress != nil {
				u.progress.ShowSuccess(successMsg)
			} else {
				utils.ShowMessageBox(successMsg, "更新完成")
			}
		} else {
			u.logDetail(fmt.Sprintf("主程序已启动: %s", u.mainProgram))
			successMsg := "软件已成功更新！\n\n主程序已自动启动。"
			if u.progress != nil {
				u.progress.ShowSuccess(successMsg)
			} else {
				utils.ShowMessageBox(successMsg, "更新完成")
			}
		}
	} else {
		// 显示更新完成提示
		successMsg := "软件已成功更新！\n\n您现在可以重新启动程序。"
		if u.progress != nil {
			u.progress.ShowSuccess(successMsg)
		} else {
			utils.ShowMessageBox(successMsg, "更新完成")
		}
	}

	// 10. 处理自我更新
	if err := utils.HandleSelfUpdate(u.tempExtractDir, u.targetDir); err != nil {
		u.logDetail(fmt.Sprintf("警告: 更新器自更新失败: %v", err))
		u.logDetail(fmt.Sprintf("请手动替换 %s", config.UpdaterName))
	}

	return nil
}

// performUpdate 执行更新操作
func (u *Updater) performUpdate(isJvmVersion bool) error {
	extractedDir := utils.FindExtractedDirectory(u.tempExtractDir)

	if isJvmVersion {
		return u.updateJVMVersion(extractedDir)
	}
	return u.updateNativeVersion(extractedDir)
}

// updateJVMVersion 更新 JVM 版本
func (u *Updater) updateJVMVersion(extractedDir string) error {
	u.logStatus("更新 JVM 版本...")
	u.logDetail(fmt.Sprintf("保留目录: %s", strings.Join(config.JVMPreserveDirs, ", ")))
	u.logDetail(fmt.Sprintf("要更新的插件: %s", strings.Join(config.JVMUpdatePluginDirs, ", ")))

	// 复制文件，排除需要保留的目录和插件
	if err := utils.CopyDirectory(
		extractedDir,
		u.targetDir,
		config.JVMPreserveDirs,
		config.JVMUpdatePluginDirs,
	); err != nil {
		return fmt.Errorf("复制文件失败: %w", err)
	}

	u.logDetail("JVM 版本更新完成")
	return nil
}

// updateNativeVersion 更新 Native 版本
func (u *Updater) updateNativeVersion(extractedDir string) error {
	u.logStatus("更新 Native 版本...")
	u.logDetail(fmt.Sprintf("保留目录: %s", strings.Join(config.NativePreserveDirs, ", ")))

	// 复制文件，只排除 config 和 data
	if err := utils.CopyDirectory(
		extractedDir,
		u.targetDir,
		config.NativePreserveDirs,
		nil,
	); err != nil {
		return fmt.Errorf("复制文件失败: %w", err)
	}

	u.logDetail("Native 版本更新完成")
	return nil
}

// cleanup 清理临时文件
func (u *Updater) cleanup() {
	if utils.Exists(u.tempExtractDir) {
		if err := utils.Delete(u.tempExtractDir); err != nil {
			fmt.Printf("清理临时目录失败: %v\n", err)
		}
	}
}
