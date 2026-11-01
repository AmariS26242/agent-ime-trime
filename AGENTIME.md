# Agent 输入法（Trime 基座）

本仓库是 [osfans/trime](https://github.com/osfans/trime) 的私有分支，在它之上加了「AI 助手」能力。
上游基线：`develop` @ `7c55dd1`（2026-11-01）。

配套仓库：

| 用途 | 地址 |
| --- | --- |
| 后端代理 + 全部文档/验收记录 | <https://github.com/AmariS26242/agent-ime> |
| 本仓库（输入法源码，Trime 基座） | <https://github.com/AmariS26242/agent-ime-trime> |

## 相对上游的改动

| 提交 | 内容 |
| --- | --- |
| `5dfd094` | AI 入口键与面板（提问态/回答态）、面板内打字经 rime 拼音选字、插入/改写宿主输入框、撤销（按快照还原原文）、敏感输入域禁用 AI（不读不写） |
| `1be1402` | 接入真实 DeepSeek：后端 SSE 流式、逐字渲染、取消（断连即中止上游）、超时与错误文案；启动时 InputView 延迟创建兜底（`themeScope` 未就绪时轮询 300ms 补建） |
| `1850256` | 面板内可配置后端地址/令牌（`AgentConfig`）、默认存储模式改 `APP_STORAGE`（真机首次安装即可用） |
| `d056ce8` | 面板设置态键盘输入原样 ASCII 直通（URL/令牌不被拼音吃掉）、面板背景改为不透明（修「黑框」观感） |

关键文件：

```
app/src/main/java/com/osfans/trime/ime/agent/AgentPanel.kt     # 面板 UI 与状态机
app/src/main/java/com/osfans/trime/ime/agent/AgentClient.kt    # SSE 流式客户端
app/src/main/java/com/osfans/trime/ime/agent/AgentConfig.kt    # 后端地址/令牌持久化
app/src/main/java/com/osfans/trime/ime/core/InputView.kt       # 面板挂载、提交/插入/撤销编排
app/src/main/java/com/osfans/trime/ime/core/TrimeInputMethodService.kt  # 敏感域判定、写入宿主、面板文字改道
app/src/main/java/com/osfans/trime/ime/keyboard/CommonKeyboardActionListener.kt  # AI 键命令、面板内按键路由
app/src/main/assets/shared/trime.yaml                          # preset_keys 的 AGENT 键、qwerty 底排
app/src/main/java/com/osfans/trime/ui/dev/AgentTestActivity.kt # 测试宿主（四个输入框：普通/改写/无建议/密码）
```

## 构建

本机实测可用的工具链（Windows）：

- JDK 17（`java -version` = 17.0.21）
- Android SDK：`platforms;android-35`、`build-tools;35.0.0`
- NDK `27.0.12077973`、CMake `3.31.6`（上游 CI 用 NDK 30 / CMake 4.1.2；这两项都能用环境变量覆盖，见下）
- Gradle 由 wrapper 自动下载（9.8.1）

```powershell
# 覆盖 native 工具链版本（不需要改源码）
$env:NDK_VERSION="27.0.12077973"
$env:CMAKE_VERSION="3.31.6"
$env:BUILD_ABI="x86_64"        # 或 arm64-v8a；splits 每个 ABI 单独出包
$env:JAVA_TOOL_OPTIONS="-Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=7897 -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7897"

.\gradlew.bat --no-daemon --console=plain assembleDebug
# 产物：app\build\outputs\apk\debug\com.osfans.trime-<git describe>-<abi>-debug.apk
```

## Windows 上必做的两个补丁（否则构建/中文都不可用）

1. **librime 插件的软链接**：`app/src/main/jni/cmake/Rime.cmake:9-14` 用 `file(CREATE_LINK ... SYMBOLIC)` 把 `jni/librime-{lua,octagram,predict}` 链进 `librime/plugins/`。Windows 无开发者模式时创建软链接失败，CMake 只留空目录 → 报
   `CMake Error at librime/plugins/CMakeLists.txt:47 (add_subdirectory): ... does not contain a CMakeLists.txt file`。
   修法（无需管理员）：

   ```powershell
   $jni="app\src\main\jni"
   foreach ($p in "librime-lua","librime-octagram","librime-predict") {
     Remove-Item "$jni\librime\plugins\$p" -Recurse -Force -ErrorAction SilentlyContinue
     robocopy "$jni\$p" "$jni\librime\plugins\$p" /E | Out-Null
   }
   robocopy "$jni\librime-lua-deps" "$jni\librime\plugins\librime-lua\thirdparty" /E | Out-Null
   ```

2. **assets 里的 rime 数据**：`app/src/main/assets/shared/` 下 14 个文件在上游是符号链接（指向 `app/data/rime/`）。用 `core.symlinks=false` clone 后它们变成几十字节的指针文本，打出的 APK 无法 deploy：

   ```powershell
   Copy-Item app\data\rime\* app\src\main\assets\shared\ -Force -Recurse
   ```

   验证：`assets/shared/default.yaml` 应约 1.4 KB（不是 60 字节），`essay.txt` 约 6.3 MB；安装后 `files/rime/build/` 会出现 `luna_pinyin.prism.bin` 等文件，键盘空格键显示「朙月拼音」。

## 后端

在另一端仓库启动（默认监听 8787）：

```powershell
cd path\to\agent-ime\project\backend
powershell -ExecutionPolicy Bypass -File .\run-backend.ps1 -Lan   # -Lan 监听 0.0.0.0
```

手机面板右上「设置」里填后端地址与令牌；模拟器可用 `adb reverse tcp:8787 tcp:8787` + `http://127.0.0.1:8787/v1/chat/stream`。

## 已知限制

见配套仓库 `project/docs/Agent输入法-Trime基座-验收-2026-11-01.md` §8。要点：arm64 真机包已产出但尚未真机回归；撤销按快照长度还原、期间手改文本可能不精确；未做九宫格；后端为共享令牌 + 单用户额度；令牌明文存 prefs。
