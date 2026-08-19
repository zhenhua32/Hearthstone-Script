package config

// ProgramName 程序名称
const ProgramName = "hs-script"

// ProjectName 项目名称
const ProjectName = "Hearthstone-Script"

// UpdaterName 更新器名称
const UpdaterName = "update.exe"

// UpdaterBackupName 更新器备份名称
const UpdaterBackupName = "update.exe.bak"

// JVMPreserveDirs JVM版需要保留的目录
var JVMPreserveDirs = []string{"config", "data"}

// JVMUpdatePluginDirs JVM版需要更新的插件目录
var JVMUpdatePluginDirs = []string{
	"hs-script-base-card-plugin",
	"hs-script-base-strategy-plugin",
}

// NativePreserveDirs Native版需要保留的目录
var NativePreserveDirs = []string{"config", "data"}
