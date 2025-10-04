# L2DChat Android

基于 Live2D Cubism SDK 与 Jetpack Compose 打造的移动端实时聊天示例。
应用将聊天服务返回的数据与 2D 模型驱动进行耦合，提供模型选取、消息对话、多媒体段处理、动态壁纸、小组件转发等完整体验骨架，便于二次开发成个人助手或虚拟主播客户端。
tcmofashi: 本项目纯纯的vibe coding，还请见谅

## ✨ 主要特性

- **Live2D 渲染集成**：内置 Cubism Framework，支持多模型加载、动作／表情播放与渲染生命周期管理。
- **Compose 聊天界面**：使用 Jetpack Compose 构建消息流、输入框、模型状态提示等 UI 组件，并自动记忆上次连接配置。
- **标准化 WebSocket 协议**：对接新的 `sender_info` / `receiver_info` 消息格式，可发送文本、表情、语音段，支持 Platform Header 与 Bearer Token。
- **桌面扩展能力**：提供动态壁纸 `Live2DWallpaperService` 以及桌面小组件 `Live2DChatWidgetProvider` 骨架，演示消息气泡同步、手势交互。
- **可扩展的配置存储**：将连接、用户、模型偏好写入 `SharedPreferences`，实现自动重连与多端共享。
- **本地测试服务器**：附带 Node.js WebSocket Mock (`websocket-test-server.js`)，方便快速模拟服务端行为。

## 📁 项目结构速览

```text
androidproj/
├── app/                    # 主应用模块
│   ├── src/main/java/com/l2dchat/…
│   │   ├── chat/           # WebSocket 客户端、消息协议
│   │   ├── live2d/         # 模型渲染封装、资源管理
│   │   ├── ui/             # Compose UI 与主题
│   │   └── wallpaper/      # 动态壁纸与桌面组件骨架
│   ├── assets/             # 空目录（README 占位），用于放置自备模型资源
│   ├── libs/               # 放置 Live2DCubismCore.aar（二进制需自行下载）
│   └── CubismSdkForJava-*/ # 下载后解压得到的 Cubism Framework 模块
├── webdriver-test-server.js# 本地 WebSocket 测试服务
├── WEBSOCKET_CONFIG_GUIDE.md
├── wallpaper_guide.md
└── signing.properties.example
```

## 🧰 环境要求

- Android Studio Koala (或更高版本) + Android Gradle Plugin 8.5+
- JDK 17（Gradle Toolchain 自动配置）
- Android SDK Platform 36、Build-Tools 36.x
- Node.js 18+（可选，用于运行测试 WebSocket 服务）

## 🚀 快速开始

1. **克隆仓库**

  ```bash
  git clone https://github.com/your-account/l2dchat-android.git
  cd l2dchat-android/androidproj
  ```

1. **导入 Android Studio**：选择 `Open an Existing Project` 指向 `androidproj` 目录。
1. **同步依赖**：首次打开会自动下载 Gradle Wrapper 与所需插件，保证联网即可。
1. **配置 Live2D SDK**：按照下节指引拷贝 `CubismSdkForJava`、`Live2DCubismCore.aar` 以及模型资源。
1. **运行 Debug**：选择 `app` 模块运行到真机或模拟器，若资源未就绪会显示占位提示。

> ⚠️ 本仓库不包含真实签名信息，Release 构建需要手动补全，见下文说明。

## 🧱 准备 Live2D Cubism SDK（必读）

仓库未附带任何 Live2D 官方 SDK、模型或 Shader，请在遵循授权条款的前提下自行下载并部署：

1. **下载 SDK**

  - 前往 [Live2D 官方下载页](https://www.live2d.com/en/download/cubism-sdk/) 获取 `CubismSdkForJava`（示例基于 `5-r.4.1`）。
  - 阅读并同意页面中的 *Live2D Open Software License*、*Live2D Proprietary Software License* 等协议。

1. **解压并放置 Framework 模块**

  ```bash
  # 假设下载后的压缩包位于 ~/Downloads
  unzip ~/Downloads/CubismSdkForJava-5-r.4.1.zip -d app/
  # 结果应生成 app/CubismSdkForJava-5-r.4.1/Framework/framework
  ```

  未按此路径放置会导致 Gradle 无法找到 `:framework` 子模块，可在 `settings.gradle.kts` 中调整路径以适配其他版本号。

1. **拷贝 Cubism Core (.aar)**

  ```bash
  cp app/CubismSdkForJava-5-r.4.1/Core/Release/Live2DCubismCore.aar app/libs/
  ```

  - 若使用 Debug 版 Core，请根据需要更换路径（例如 `Core/Debug/Live2DCubismCore.aar`）。
  - 请勿将 `.aar` 提交到仓库，`.gitignore` 已默认忽略。

1. **准备模型与 Shader**

  - 将模型文件夹（含 `.model3.json`、`.moc3`、`textures/`、`motions/` 等）直接放入 `app/src/main/assets/`。
  - 如需使用官方示例模型/Shader，请确认遵守 [Free Material License](https://www.live2d.com/eula/live2d-free-material-license-agreement_en.html) 并复制到该目录。
  - 仓库提供 `app/src/main/assets/README.md` 作为占位说明。

1. **验证资源是否就绪（可选）**

  ```bash
  ls app/libs/Live2DCubismCore*.aar
  ls app/CubismSdkForJava-5-r.4.1/Framework/framework/src
  ls app/src/main/assets
  ```

  如上述命令返回文件列表，即表示 SDK 结构已部署完成。

> ℹ️ Gradle 构建会在缺少 `signing.properties` 或 Cubism Core 时抛出异常，这是为了避免误发布不完整的 Release 包。

## 🔒 Release 签名配置

1. 参考 `signing.properties.example` 创建新的 `signing.properties`：

  ```properties
  storeFile=keystore/your-release.jks
  storePassword=***
  keyAlias=***
  keyPassword=***
  ```

1. 使用 `keystore/keygen.sh` 或 Android Studio Wizard 生成自己的 keystore，并保存到 `keystore/` 目录（该目录已被 `.gitignore` 屏蔽）。
1. 运行 Release 构建：

  ```bash
  ./gradlew assembleRelease
  ```

1. 若缺少配置，构建脚本会抛出明确的 `GradleException` 提示缺失字段。

## 🖼️ 动态壁纸与桌面小组件

- 参见 [`wallpaper_guide.md`](./wallpaper_guide.md) 了解当前骨架实现、手势交互、消息气泡展示及后续扩展建议。
- 测试步骤摘要：

  1. 安装 Debug 包后在系统壁纸设置中选择 “L2DChat”。
  2. 桌面长按添加 *L2DChat Widget*，点击按钮即可向壁纸广播消息。
  3. 提供了平移、缩放、双击复位等基础手势，可在此基础上接入 Live2D 渲染循环。

## 🧩 Live2D 资源与授权

- 仓库不再附带任何 Live2D 官方 SDK、模型或 Shader；请使用者按上节步骤自行下载并确认许可。
- 相关授权协议：
  - [Live2D Open Software License Agreement](https://www.live2d.com/eula/live2d-open-software-license-agreement_en.html)
  - [Live2D Proprietary Software License Agreement](https://www.live2d.com/eula/live2d-proprietary-software-license-agreement_en.html)
  - [Live2D Free Material License](https://www.live2d.com/eula/live2d-free-material-license-agreement_en.html)
- 将模型文件夹直接置于 `app/src/main/assets/` 下，应用会自动扫描并在 UI 中列出可选模型。
- 若发布到公开仓库或商用产品，请再次核对是否需要签署 Cubism SDK Release License（年营收 ≥ 1000 万日元的主体必须签署）。

## 🤝 贡献指南

1. Fork 项目并切出特性分支。
2. 保持代码与文档中不包含个人敏感信息（域名、IP、密钥等）。
3. 提交 PR 时描述修改动机、测试结果与兼容性影响。

## 📄 许可证

本项目采用 [MIT License](./LICENSE) 授权，允许在保留版权声明的前提下自由复制、修改与分发。
