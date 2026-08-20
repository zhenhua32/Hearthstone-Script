# VS Code 本地开发与策略插件操作指南

本文档记录在当前仓库中使用 VS Code 开发、构建、调试和部署策略插件的常用操作。项目以 Windows 为主要运行环境；普通源码编译可以不启动游戏，涉及日志、窗口、鼠标、注入和真实出牌的联调需要 Windows、Battle.net 与炉石传说。

## 1. 当前工作区配置

仓库根目录的本地 `.vscode/` 已包含以下配置：

| 文件 | 用途 |
|---|---|
| `.vscode/extensions.json` | 推荐 JetBrains Kotlin、Java Extension Pack、Maven 和 PowerShell 扩展 |
| `.vscode/settings.json` | 固定 JDK 25、导入根 Maven 工程、配置终端环境和 Kotlin 格式化器 |
| `.vscode/tasks.json` | 全量编译、测试、策略模板打包和部署任务 |
| `.vscode/launch.json` | Kotlin 源码启动和 JDWP 5005 附加调试 |

当前配置使用的 JDK 路径是：

~~~text
C:\Program Files\Java\jdk-25.0.4.1
~~~

如果在其他电脑开发，需要同步修改 `settings.json`、`tasks.json` 和 `launch.json` 中的 JDK 路径。根工程声明 Java 25，策略 SDK 和策略模板的目标字节码为 Java 21；在当前单仓库内统一使用 JDK 25 最省事。

`.vscode/` 和运行时 `plugin/` 目录被根 `.gitignore` 忽略，默认只作为本机配置，不会进入提交。

## 2. 必要与推荐软件

### 2.1 策略开发必需

1. Windows 11，真实运行和窗口自动化使用。
2. JDK 25。
3. Visual Studio Code。
4. JetBrains 官方 Kotlin 扩展：`jetbrains.kotlin-server`。
5. Java Extension Pack：`vscjava.vscode-java-pack`，其中包含 Java 调试器、测试和 Maven 支持。
6. 首次构建所需的 Maven Central/JitPack 网络访问。

项目自带 `mvnw.cmd`，不需要单独安装 Maven；Kotlin 编译器、JavaFX、JNA、SQLite JDBC 等依赖由 Maven 管理。

### 2.2 真实运行时建议

1. 安装并配置 Battle.net 与炉石传说。
2. 以管理员身份启动 VS Code 或最终程序，尤其是驱动、注入和低层鼠标模式。
3. 使用 JVM 版调试插件；Native 版不支持常规外部插件。
4. DLL 加载失败时安装 x64 和 x86 两个版本的 Visual C++ Redistributable。
5. 检查 Windows Defender 或其他安全软件是否隔离了 DLL/EXE。

### 2.3 非策略开发才需要

| 工作内容 | 额外工具 |
|---|---|
| `tools/hs-card-update-util`、`tools/hs-script-version-server` | Go 1.24.5 或更新兼容版本 |
| `tools/hs-script-update` | Go 1.23.1 或更新兼容版本 |
| Native Image 打包 | GraalVM、MSVC/Visual Studio Build Tools 等 Native Image 工具链 |
| 编辑 WeightHandler 数据库 | SQLite 可视化工具可选；运行本身不需要 SQLite Server |

## 3. 第一次打开仓库

1. 用 VS Code 打开仓库根目录，而不是单独打开某个子模块。
2. 执行 `Developer: Reload Window`，让新安装的 Kotlin 扩展生效。
3. 首次启动 JetBrains Kotlin 扩展时，按个人偏好选择地区和诊断数据选项。
4. 等待右下角 Maven/Kotlin 项目导入和索引结束。
5. 打开 VS Code 集成终端，确认：

~~~powershell
java -version
$env:JAVA_HOME
~~~

应看到 Java 25 和 `C:\Program Files\Java\jdk-25.0.4.1`。工作区外的普通终端可能仍指向系统 Java 8，不影响已配置的 VS Code 任务。

如果 Kotlin 长时间没有补全，可执行：

~~~text
IntelliJ: Reload Workspace
IntelliJ: Clear Caches and Restart Language Server
Developer: Reload Window
~~~

不要同时启用旧的 `mathiasfrohlich.kotlin` 或 `fwcd.kotlin`，否则可能出现重复诊断、跳转冲突和双语言服务。

## 4. VS Code 常用任务

按 `Ctrl+Shift+P`，执行 `Tasks: Run Task` 可以看到下列任务。

| 任务 | 作用 | 常用入口 |
|---|---|---|
| `Maven: compile all (JDK 25)` | 编译根 Maven 聚合工程，跳过测试 | `Ctrl+Shift+B` |
| `Maven: test all (JDK 25)` | 执行根工程测试 | `Tasks: Run Test Task` |
| `Strategy: package template` | 构建策略模板及其上游模块 | 任务面板 |
| `Strategy: deploy template to plugin` | 先打包，再复制 JAR 到根 `plugin/` | 任务面板 |
| `Dev: prepare source debug` | 全量编译并部署策略模板 | 由 F5 自动调用 |

对应的命令行操作如下：

~~~powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-25.0.4.1'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"

# 全量编译
.\mvnw.cmd -DskipTests compile

# 全量测试
.\mvnw.cmd test

# 打包策略模板及其依赖模块
.\mvnw.cmd -pl hs-strategy-plugin-template -am -DskipTests package
~~~

策略模板产物位于：

~~~text
hs-strategy-plugin-template\target\hs-strategy-plugin-template.jar
~~~

部署任务会将它复制到：

~~~text
plugin\hs-strategy-plugin-template.jar
~~~

## 5. 启动和调试

### 5.1 从源码启动

按 `F5`，选择：

~~~text
Hearthstone-Script: source debug (paused)
~~~

该配置会：

1. 使用 JDK 25。
2. 以仓库根目录作为 `user.dir`，确保 `config/`、`plugin/`、`data/`、日志和资源路径一致。
3. 执行 `Dev: prepare source debug`，编译工程并部署策略模板。
4. 运行入口 `club.xiaojiawei.hsscript.MainKt`。
5. 传入 `--pause=true`，只打开程序，不自动启动 Battle.net/炉石。

需要测试真实策略时，再在界面中检查游戏路径、运行模式和策略选择，然后手动解除暂停。真实鼠标、驱动和注入调试建议以管理员身份运行 VS Code。

### 5.2 附加到已运行 JVM

发行包中的 `debug-hs-script.bat` 使用 JDWP 5005 端口启动程序。启动后按 `F5`，选择：

~~~text
Hearthstone-Script: attach JDWP 5005
~~~

如果端口被占用，可同时修改启动参数和 `.vscode/launch.json` 中的端口。

### 5.3 日志位置

| 日志 | 默认位置 |
|---|---|
| 软件日志 | 仓库/软件运行根目录下的 `log/` |
| 游戏 Power 日志 | 炉石安装目录下的 `Logs/Power.log` |
| 游戏界面日志 | `Logs/LoadingScreen.log` |
| 卡组日志 | `Logs/Decks.log` |

策略没有被调用时，先确认日志监听是否启动、脚本是否暂停、是否处于工作时间、当前模式是否为 Gameplay，以及是否轮到我方。

## 6. 新建策略的推荐方式

### 6.1 外部插件方式（推荐）

复制 `hs-strategy-plugin-template` 为新的根级模块，或直接先在模板中验证逻辑。至少需要修改：

1. `TemplatePlugin.kt`：插件名称、作者、版本、插件 ID、主页和 SDK 版本。
2. `TemplateStrategyDeck.kt`：策略名称、运行模式、套牌代码、策略 ID 和行为。
3. `pom.xml`：`artifactId`、版本和生成类包路径。
4. 两个 `META-INF/services` 文件中的全限定类名。

如果创建新的根级模块，还要把模块加入根 `pom.xml` 的 `<modules>`。如果模块放到更深的目录，需要检查父 POM 的 `<relativePath>`。

### 6.2 内置策略方式

如果策略只服务于当前主仓库，可以把实现放到：

~~~text
hs-script-base-strategy-plugin/src/main/kotlin/club/xiaojiawei/hsscriptbasestrategy/strategy/
~~~

然后把完整类名追加到：

~~~text
hs-script-base-strategy-plugin/src/main/resources/META-INF/services/
club.xiaojiawei.hsscriptstrategysdk.DeckStrategy
~~~

这种方式复用 `HsBaseStrategyPlugin` 元数据，不需要再创建新的 `StrategyPlugin`。

## 7. DeckStrategy 实现检查表

统一接口位于 `hs-script-strategy-sdk/.../DeckStrategy.kt`。

| 方法/属性 | 开发约定 |
|---|---|
| `name()` | UI 显示名称，不能为空 |
| `id()` | 策略稳定唯一 ID；发布后不要随意修改 |
| `getRunMode()` | 至少返回一个受支持模式 |
| `deckCode()` | 可为空；不为空时会显示/输出套牌代码 |
| `reset()` | 每局开始调用，清理所有局内缓存，并调用 `super.reset()` |
| `executeChangeCard(cards)` | 从集合删除表示“换掉”；保留在集合表示“不换” |
| `executeOutCard()` | 我方回合入口，返回后主程序负责结束回合 |
| `executeDiscoverChooseCard()` | 返回从 0 开始的候选下标 |
| `execChooseTimeLine()` | 默认保持，调用 `rewind()` 才回溯 |
| `needSurrender` | 设为 `true` 后应尽快返回，由执行器消费投降请求 |

编码时还应遵守：

1. `WAR`、手牌、战场和卡牌属性会被日志线程实时修改；遍历前使用 `toList()`。
2. 每次真实动作前重新检查 `WAR.isMyTurn`、玩家有效性、费用和区域容量。
3. 优先调用 `card.action.power()`、`attack()`、`attackHero()`，不要在策略中散落屏幕坐标。
4. 不要在 `executeOutCard()` 内创建永久循环；它是“一次我方回合”的回调。
5. 有目标战吼、法术、发现、随机效果或复杂区域变化通常需要对应的 CardAction/卡牌信息支持。
6. MCTS 依赖动作的模拟函数；模拟不完整时，搜索结果只是在错误模型中最优。

## 8. SPI 注册

外部策略插件必须同时存在插件元数据和策略实现两个 SPI 文件。

`src/main/resources/META-INF/services/club.xiaojiawei.hsscriptstrategysdk.StrategyPlugin`：

~~~text
com.example.mystrategy.MyPlugin
~~~

`src/main/resources/META-INF/services/club.xiaojiawei.hsscriptstrategysdk.DeckStrategy`：

~~~text
com.example.mystrategy.MyDeckStrategy
~~~

一个插件 JAR 可以提供多个策略，在 DeckStrategy 服务文件中每行写一个完整类名。实现类需要可由 Java ServiceLoader 使用无参构造创建。

插件 ID 与策略 ID 是两个不同的稳定标识：

- 插件 ID 用于版本去重、启用/禁用和 CardAction 作用域。
- 策略 ID 用于保存用户选择和策略对象判等。

相同插件 ID 的多个候选只加载最高版本。更新 JAR 后应重启程序，因为当前 ClassLoader 不会在运行中真正卸载旧插件类。

## 9. 插件没有显示时的排查顺序

1. 确认运行的是 JVM 版，不是 Native 版。
2. 确认 JAR 在程序当前工作目录的 `plugin/` 中。
3. 用 `jar tf plugin\xxx.jar` 检查两个 `META-INF/services` 文件是否存在。
4. 检查 SPI 文件中的类名是否与 package 完全一致。
5. 检查 `Plugin.id()`、`DeckStrategy.id()`、`name()` 是否非空。
6. 检查 `getRunMode()` 是否返回非空数组，并支持界面当前选择的模式。
7. 检查插件是否在设置中被禁用。
8. 检查是否存在同插件 ID 的更高版本 JAR。
9. 查看软件 `log/` 中的 `加载SPI错误`、`加载插件错误` 或版本跳过日志。
10. 替换 JAR 后完整重启程序。

检查模板 JAR 的示例：

~~~powershell
& 'C:\Program Files\Java\jdk-25.0.4.1\bin\jar.exe' `
  tf .\plugin\hs-strategy-plugin-template.jar |
  Select-String 'META-INF/services|TemplatePlugin|TemplateStrategyDeck'
~~~

## 10. 常见构建问题

### 10.1 出现虚拟线程或 `Thread.ofVirtual` 未解析

实际使用了 Java 8/17，而不是 JDK 25。检查 VS Code 的 `JAVA_HOME`、`java.jdt.ls.java.home`、`intellij.jdkForSymbolResolution` 和 Maven 任务环境。

### 10.2 Maven 无法下载依赖

确认 Maven Central/JitPack 网络访问和代理设置。第一次下载完成后，依赖会进入用户 Maven 缓存；不要频繁删除 `.m2/repository`。

### 10.3 Kotlin 有红线但 Maven 编译成功

通常是语言服务器尚未导入完成或缓存过期。依次执行 `IntelliJ: Reload Workspace`、`IntelliJ: Clear Caches and Restart Language Server` 和 `Developer: Reload Window`。

### 10.4 DLL 注入或本地库加载失败

确认管理员权限、Visual C++ x64/x86 运行库、安全软件隔离状态，以及运行目录是否正确。路径类使用 `System.getProperty("user.dir")` 计算 `plugin/`、`config/`、`data/` 和 `lib/`，因此不要随意把 cwd 指向 `hs-script-app` 子目录。

### 10.5 只修改策略却每次构建很慢

日常使用 `Strategy: package template`。只有修改 SDK 或应用层后才执行全量编译/测试。Maven 已编译模块会增量复用，排查奇怪缓存问题时再使用 `clean package`。

## 11. 推荐开发循环

~~~text
修改策略
  → Ctrl+Shift+B 或 Strategy: package template
  → Strategy: deploy template to plugin
  → F5 以 paused 模式启动
  → 在插件设置确认插件和策略
  → 配置模式/卡组/游戏路径
  → 设置断点并手动解除暂停
  → 查看软件日志与 Power.log
  → 修正规则后重启验证
~~~

第一次开发建议先实现简单的低费换牌、普通随从出牌和攻击逻辑，确认完整事件链工作后，再加入有目标战吼、法术、权重组合或 MCTS。
