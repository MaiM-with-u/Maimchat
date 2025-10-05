# WebSocket连接配置与新消息协议指南

## 概述

你的Live2D对话应用现在支持完整的WebSocket连接功能，包括：

- Platform标识符支持
- Authorization认证
- 标准化消息格式（text/emoji/voice + seglist）
- 连接状态管理
- 用户友好的配置界面

并已全面切换到新的消息协议字段，仅使用 `sender_info` 与 `receiver_info`，不再兼容旧的 `user_info` / `group_info` 字段。

## 快速开始

### 1. 启动测试服务器

```bash
# 进入项目目录
cd D:\proj\androidproj

# 安装依赖（如果没有安装）
npm install ws

# 启动测试服务器
node websocket-test-server.js
```

服务器将在 `ws://localhost:8080/ws` 启动

### 2. 在应用中配置连接

#### 方法一：使用配置界面（推荐）

```kotlin
// 在你的Activity或Compose中使用
@Composable
fun MyScreen() {
    WebSocketConnectionExample()
}
```

#### 方法二：代码直接配置

```kotlin
val chatManager = ChatWebSocketManager()

// 基本连接（无认证）
val config = WebSocketConfig.default("ws://localhost:8080/ws")
chatManager.setConnectionConfig(config.platform, config.authToken)
chatManager.connect(config.url)

// 带认证的连接
val configWithAuth = WebSocketConfig.withAuth(
  url = "wss://your-server.com/ws",
    platform = "live2d_android", 
    authToken = "your_bearer_token"
)
chatManager.setConnectionConfig(configWithAuth.platform, configWithAuth.authToken)
chatManager.connect(configWithAuth.url, configWithAuth.platform, configWithAuth.authToken)
```

## 配置选项详解

### WebSocketConfig 参数

| 参数             | 类型    | 默认值        | 说明                                         |
| ---------------- | ------- | ------------- | -------------------------------------------- |
| `url`            | String  | 必填          | WebSocket服务器地址，必须以ws://或wss://开头 |
| `platform`       | String  | "live2d_chat" | 平台标识符，发送到服务器的headers中          |
| `authToken`      | String? | null          | Bearer token，用于身份认证                   |
| `enableAuth`     | Boolean | false         | 是否启用认证                                 |
| `connectTimeout` | Long    | 10000         | 连接超时时间（毫秒）                         |
| `readTimeout`    | Long    | 10000         | 读取超时时间（毫秒）                         |
| `writeTimeout`   | Long    | 10000         | 写入超时时间（毫秒）                         |

### 创建配置的便捷方法

```kotlin
// 1. 默认配置
val config1 = WebSocketConfig.default("ws://localhost:8080/ws")

// 2. 自定义平台
val config2 = WebSocketConfig.withPlatform("ws://localhost:8080/ws", "my_platform")

// 3. 带认证
val config3 = WebSocketConfig.withAuth("wss://api.example.com/ws", "live2d_android", "token123")

// 4. 完全自定义
val config4 = WebSocketConfig(
  url = "ws://localhost:8080/ws",
    platform = "live2d_android",
    authToken = "my_token",
    enableAuth = true,
    connectTimeout = 15000,
    readTimeout = 15000,
    writeTimeout = 15000
)
```

## 消息格式

### 新协议要点（必须遵循）

- 仅使用 `message_info.sender_info` 与 `message_info.receiver_info` 标注消息双方身份。
- 不再支持旧字段：`user_info`、`group_info`。服务器端需同步适配。
- 应用侧会将当前 Live2D 模型名写入 `receiver_info.user_id` 与 `receiver_info.user_nickname`。
- `additional_config` 中保留以下常用键：
  - `message_type`: "chat" | "motion" | "expression" | "interaction" | "command" | "system"
  - `from_user`: 是否来自用户（布尔值）

### 发送消息

你的应用会自动将文本消息转换为标准格式：

```kotlin
// 简单发送文本
chatManager.sendUserMessage("你好！")

// 自动转换为标准格式：
{
  "message_info": {
    "platform": "live2d_android",
    "message_id": "msg_1234567890_123",
    "time": 1234567890.123,
    "sender_info": {
      "platform": "live2d_android",
      "user_id": "u_xxx",
      "user_nickname": "你的昵称",
      "user_cardname": null
    },
    "receiver_info": {
      "platform": "live2d_android",
      "user_id": "当前模型名",
      "user_nickname": "当前模型名",
      "user_cardname": null
    },
    "additional_config": {
      "from_user": true,
      "message_type": "chat"
    }
  },
  "message_segment": {
    "type": "seglist",
    "data": [
      {"type": "text", "data": "你好！"}
    ]
  },
  "raw_message": "你好！"
}
```

### 接收消息

应用支持接收以下类型的消息：

#### 1. 文本消息

```json
{
  "message_info": {
    "platform": "live2d_server",
    "message_id": "srv_1234567890_456",
    "time": 1234567890.123,
    "sender_info": {
      "platform": "live2d_server",
      "user_id": "server",
      "user_nickname": "AI"
    },
    "receiver_info": {
      "platform": "live2d_android",
      "user_id": "当前模型名",
      "user_nickname": "当前模型名"
    },
    "additional_config": {"message_type": "chat", "from_user": false}
  },
  "message_segment": {
    "type": "seglist",
    "data": [
      {"type": "text", "data": "你好！我是Live2D助手"}
    ]
  }
}
```

#### 2. 表情消息（Base64图片）

```json
{
  "message_info": {
    "platform": "live2d_server",
    "message_id": "srv_1234567890_456",
    "time": 1234567890.123,
    "sender_info": {"platform": "live2d_server", "user_id": "server"},
    "receiver_info": {"platform": "live2d_android", "user_id": "当前模型名"},
    "additional_config": {"message_type": "emoji", "from_user": false}
  },
  "message_segment": {
    "type": "seglist",
    "data": [
      {"type": "emoji", "data": "iVBORw0KGgoAAAANSUhEUgAA..."}
    ]
  }
}
```

#### 3. 语音消息（Base64 WAV）

```json
{
  "message_info": {
    "platform": "live2d_server",
    "message_id": "srv_1234567890_456", 
    "time": 1234567890.123,
    "sender_info": {"platform": "live2d_server", "user_id": "server"},
    "receiver_info": {"platform": "live2d_android", "user_id": "当前模型名"},
    "additional_config": {"message_type": "voice", "from_user": false}
  },
  "message_segment": {
    "type": "seglist",
    "data": [
      {"type": "voice", "data": "UklGRjr4AQBXQVZFZm10..."}
    ]
  }
}
```

#### 4. 复合消息（段列表）

```json
{
  "message_info": {
    "platform": "live2d_server",
    "message_id": "srv_1234567890_456",
    "time": 1234567890.123,
    "sender_info": {"platform": "live2d_server", "user_id": "server"},
    "receiver_info": {"platform": "live2d_android", "user_id": "当前模型名"},
    "additional_config": {"message_type": "chat", "from_user": false}
  },
  "message_segment": {
    "type": "seglist",
    "data": [
      {"type": "text", "data": "看这个表情"},
      {"type": "emoji", "data": "iVBORw0KGgoAAAANSUhEUgAA..."},
      {"type": "text", "data": "还有这个语音"},
      {"type": "voice", "data": "UklGRjr4AQBXQVZFZm10..."}
    ]
  }
}
```

## 连接状态监听

```kotlin
// 监听连接状态
val connectionState by chatManager.connectionState.collectAsState()

when (connectionState) {
    ChatWebSocketManager.ConnectionState.DISCONNECTED -> {
        // 未连接
    }
    ChatWebSocketManager.ConnectionState.CONNECTING -> {
        // 连接中
    }
    ChatWebSocketManager.ConnectionState.CONNECTED -> {
        // 已连接，可以发送消息
    }
    ChatWebSocketManager.ConnectionState.ERROR -> {
        // 连接错误
    }
}

// 监听消息事件
LaunchedEffect(Unit) {
    chatManager.getMessageEvents().collect { event ->
        when (event) {
            is Live2DChatMessageHandler.MessageEvent.ChatReceived -> {
                // 收到文本消息
            }
            is Live2DChatMessageHandler.MessageEvent.VoiceReceived -> {
                // 收到语音消息，自动播放
            }
            is Live2DChatMessageHandler.MessageEvent.EmojiReceived -> {
                // 收到表情消息，自动显示
            }
        }
    }
}
```

## 错误处理

### 常见错误及解决方案

1. **连接被拒绝**
   - 检查URL格式是否正确（必须以ws://或wss://开头）
   - 确认服务器是否运行
   - 检查platform标识符是否设置

2. **认证失败**
   - 检查authToken格式（应为Bearer token）
   - 确认token是否有效
   - 检查服务器是否要求认证

3. **消息发送失败**
   - 确认连接状态为CONNECTED
   - 检查网络连接
   - 查看日志中的错误信息

### 调试技巧

```kotlin
// 启用详细日志
android.util.Log.d("WebSocket", "连接状态: ${chatManager.getConnectionStateDescription()}")

// 监听标准消息流
val standardMessages by chatManager.standardMessages.collectAsState()
Log.d("WebSocket", "标准消息数量: ${standardMessages.size}")
```

## 部署到生产环境

### 服务器要求

1. **WebSocket服务器**必须支持：

- `platform` header 验证
- `Authorization: Bearer {token}` 认证（可选）
- 标准消息格式处理（仅 `sender_info` / `receiver_info`；不再使用 `user_info` / `group_info`）

1. **HTTPS/WSS**：生产环境建议使用wss://

1. **CORS设置**：如果需要Web客户端支持

### 配置示例

```kotlin
// 生产环境配置
val productionConfig = WebSocketConfig(
  url = "wss://api.yourdomain.com/live2d/ws",
    platform = "live2d_android",
    authToken = getAuthTokenFromSecureStorage(),
    enableAuth = true,
    connectTimeout = 20000,
    readTimeout = 30000,
    writeTimeout = 15000
)
```

## 最佳实践

1. **连接管理**
   - 应用启动时不要自动连接，让用户主动配置
   - 网络变化时实现重连逻辑
   - 应用进入后台时断开连接以节省资源

2. **消息处理**
   - 大的base64数据（语音/图片）应该分块处理
   - 实现消息队列防止消息丢失
   - 添加消息重发机制

3. **用户体验**
   - 提供清晰的连接状态指示
   - 实现离线消息缓存
   - 添加连接超时提示

4. **安全性**
   - 不要在代码中硬编码token
   - 使用安全存储保存认证信息
   - 生产环境必须使用wss://

## 测试

### 本地测试

1. 启动测试服务器：`node websocket-test-server.js`
2. 在应用中配置：`ws://localhost:8080/ws`
3. 发送包含"你好"、"动作"、"表情"、"语音"的消息测试不同功能

### 集成测试

```kotlin
class WebSocketTest {
    @Test
    fun testConnection() {
  val config = WebSocketConfig.default("ws://localhost:8080/ws")
        val validation = config.validate()
        assertTrue(validation.isSuccess)
    }
    
    @Test
    fun testMessageSending() {
        // 测试消息发送逻辑
    }
}
```

现在你已经有了完整的WebSocket连接配置系统！你可以：

1. 使用 `WebSocketConnectionExample` 组件作为主界面
2. 启动测试服务器进行本地测试
3. 根据需要自定义连接配置
4. 集成到你的Live2D应用中

附注：应用在模型切换或初始化成功后，会自动将当前模型名注入到 `receiver_info`；若在模型加载完成前发送消息，`receiver_info` 可能为空，建议等待模型加载完毕或在服务器端做空值兼容。
