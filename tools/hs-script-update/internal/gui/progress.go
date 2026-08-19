package gui

// ProgressCallback 进度回调接口
type ProgressCallback interface {
	// SetStatus 设置状态文本
	SetStatus(status string)

	// SetProgress 设置进度 (当前值, 最大值)
	SetProgress(current, max int)

	// AppendDetail 追加详细信息
	AppendDetail(detail string)

	// ShowError 显示错误
	ShowError(message string)

	// ShowSuccess 显示成功信息
	ShowSuccess(message string)
}

// ConsoleProgress 控制台进度输出（用于非GUI模式）
type ConsoleProgress struct{}

func NewConsoleProgress() *ConsoleProgress {
	return &ConsoleProgress{}
}

func (cp *ConsoleProgress) SetStatus(status string) {
	// 控制台模式不需要实现
}

func (cp *ConsoleProgress) SetProgress(current, max int) {
	// 控制台模式不需要实现
}

func (cp *ConsoleProgress) AppendDetail(detail string) {
	// 控制台模式不需要实现
}

func (cp *ConsoleProgress) ShowError(message string) {
	// 控制台模式不需要实现
}

func (cp *ConsoleProgress) ShowSuccess(message string) {
	// 控制台模式不需要实现
}
