#!/usr/bin/env node

/**
 * Live2D对话WebSocket测试服务器（增强版）
 * 支持platform标识符、Authorization认证和标准消息格式
 *
 * 使用方法：
 * 1. 安装Node.js
 * 2. 运行: npm install ws
 * 3. 运行: node websocket-test-server.js
 * 4. 在app中连接 ws://localhost:8080/chat
 */

const WebSocket = require('ws');
const http = require('http');
const url = require('url');

// 创建HTTP服务器以支持更好的连接处理
const server = http.createServer();

// 创建WebSocket服务器
const wss = new WebSocket.Server({
    server: server,
    path: '/chat',
    verifyClient: (info) => {
        // 验证platform头部
        const platform = info.req.headers['platform'];
        if (!platform) {
            console.log('❌ 连接被拒绝：缺少platform头部');
            return false;
        }

        // 验证Authorization头部（如果需要）
        const auth = info.req.headers['authorization'];
        if (auth) {
            if (!auth.startsWith('Bearer ')) {
                console.log('❌ 连接被拒绝：无效的Authorization格式');
                return false;
            }
            const token = auth.substring(7);
            // 这里可以添加token验证逻辑
            console.log(`🔐 收到认证令牌: ${token.substring(0, 10)}...`);
        }

        console.log(`✅ 连接验证通过 - Platform: ${platform}, Auth: ${auth ? '已提供' : '未提供'}`);
        return true;
    }
});

console.log('🚀 Live2D对话测试服务器启动中...');

// 预定义的标准消息回复
const messageTemplates = [
    {
        type: "text",
        content: "你好！我是Live2D助手，很高兴见到你！",
        hasMotion: false
    },
    {
        type: "text",
        content: "今天天气真不错呢~",
        hasMotion: true,
        motion: { group: "TapBody", index: 0, loop: false }
    },
    {
        type: "text",
        content: "我可以和你聊天，还能做各种动作哦！",
        hasMotion: true,
        motion: { group: "Idle", index: 1, loop: false }
    },
    {
        type: "emoji",
        content: "看这个可爱的表情！",
        // 这里应该是真实的base64图片数据，这里用示例
        emojiData: "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg=="
    },
    {
        type: "voice",
        content: "听听我的声音吧！",
        // 这里应该是真实的WAV base64数据，这里用示例
        voiceData: "UklGRjr4AQBXQVZFZm10IBAAAAABAAABAC44AABuOAAABAAAAAA="
    }
];

// 连接处理
wss.on('connection', (ws, req) => {
    const platform = req.headers['platform'];
    const auth = req.headers['authorization'];
    const clientIP = req.socket.remoteAddress;

    console.log(`🔗 新连接建立:`);
    console.log(`   Platform: ${platform}`);
    console.log(`   IP: ${clientIP}`);
    console.log(`   Auth: ${auth ? '已提供' : '未提供'}`);

    // 发送欢迎消息
    setTimeout(() => {
        sendStandardMessage(ws, {
            platform: "live2d_server",
            content: "连接成功！欢迎使用Live2D对话系统",
            type: "text"
        });
    }, 1000);

    // 消息处理
    ws.on('message', (data) => {
        try {
            const message = JSON.parse(data.toString());
            console.log(`📨 收到消息:`, JSON.stringify(message, null, 2));

            // 处理标准消息格式
            if (message.message_info && message.message_segment) {
                handleStandardMessage(ws, message);
            } else {
                // 处理旧格式消息（兼容性）
                handleLegacyMessage(ws, message);
            }

        } catch (error) {
            console.error('❌ 解析消息失败:', error.message);
            sendErrorMessage(ws, "消息格式错误");
        }
    });

    // 连接关闭处理
    ws.on('close', (code, reason) => {
        console.log(`🔌 连接断开: Platform=${platform}, Code=${code}, Reason=${reason}`);
    });

    // 错误处理
    ws.on('error', (error) => {
        console.error(`❌ WebSocket错误: Platform=${platform}:`, error.message);
    });
});

/**
 * 处理标准格式消息
 */
function handleStandardMessage(ws, message) {
    const messageInfo = message.message_info;
    const messageSegment = message.message_segment;

    console.log(`📝 处理标准消息:`);
    console.log(`   平台: ${messageInfo.platform}`);
    console.log(`   消息ID: ${messageInfo.message_id}`);
    console.log(`   段类型: ${messageSegment.type}`);

    // 根据消息段类型处理
    switch (messageSegment.type) {
        case 'text':
            handleTextMessage(ws, messageSegment.data, messageInfo);
            break;
        case 'emoji':
            handleEmojiMessage(ws, messageSegment.data, messageInfo);
            break;
        case 'voice':
            handleVoiceMessage(ws, messageSegment.data, messageInfo);
            break;
        case 'seglist':
            handleSeglistMessage(ws, messageSegment.data, messageInfo);
            break;
        default:
            console.log(`⚠️  未知消息段类型: ${messageSegment.type}`);
            sendStandardMessage(ws, {
                platform: "live2d_server",
                content: `收到未知类型消息: ${messageSegment.type}`,
                type: "text"
            });
    }
}

/**
 * 处理文本消息
 */
function handleTextMessage(ws, textContent, messageInfo) {
    console.log(`💬 处理文本消息: "${textContent}"`);

    // 模拟智能回复
    let response;
    const lowerText = textContent.toLowerCase();

    if (lowerText.includes('hello') || lowerText.includes('你好')) {
        response = messageTemplates[0];
    } else if (lowerText.includes('动作') || lowerText.includes('motion')) {
        response = messageTemplates[1];
    } else if (lowerText.includes('表情') || lowerText.includes('emoji')) {
        response = messageTemplates[3];
    } else if (lowerText.includes('声音') || lowerText.includes('语音') || lowerText.includes('voice')) {
        response = messageTemplates[4];
    } else {
        response = messageTemplates[Math.floor(Math.random() * 3)];
    }

    // 延迟回复模拟思考时间
    setTimeout(() => {
        if (response.type === "text") {
            sendStandardMessage(ws, {
                platform: "live2d_server",
                content: response.content,
                type: "text",
                motion: response.hasMotion ? response.motion : undefined
            });
        } else if (response.type === "emoji") {
            sendEmojiMessage(ws, response.content, response.emojiData);
        } else if (response.type === "voice") {
            sendVoiceMessage(ws, response.content, response.voiceData);
        }
    }, 500 + Math.random() * 1000);
}

/**
 * 处理表情消息
 */
function handleEmojiMessage(ws, emojiData, messageInfo) {
    console.log(`😀 收到表情消息，数据长度: ${emojiData.length}`);

    setTimeout(() => {
        sendStandardMessage(ws, {
            platform: "live2d_server",
            content: "收到了你的表情，真可爱！",
            type: "text"
        });
    }, 500);
}

/**
 * 处理语音消息
 */
function handleVoiceMessage(ws, voiceData, messageInfo) {
    console.log(`🎵 收到语音消息，数据长度: ${voiceData.length}`);

    setTimeout(() => {
        sendStandardMessage(ws, {
            platform: "live2d_server",
            content: "我听到了你的声音，谢谢分享！",
            type: "text"
        });
    }, 1000);
}

/**
 * 处理段列表消息
 */
function handleSeglistMessage(ws, segList, messageInfo) {
    console.log(`📋 收到段列表消息，包含 ${segList.length} 个段`);

    segList.forEach((seg, index) => {
        console.log(`   段[${index}]: ${seg.type} = ${typeof seg.data === 'string' ? seg.data.substring(0, 50) : '[复杂数据]'}`);
    });

    setTimeout(() => {
        sendStandardMessage(ws, {
            platform: "live2d_server",
            content: `收到了包含 ${segList.length} 个部分的复合消息！`,
            type: "text"
        });
    }, 800);
}

/**
 * 处理旧格式消息（兼容性）
 */
function handleLegacyMessage(ws, message) {
    console.log(`🔄 处理旧格式消息: ${message.type || 'unknown'}`);

    if (message.type === 'chat' && message.content) {
        handleTextMessage(ws, message.content, { platform: "unknown" });
    } else {
        sendStandardMessage(ws, {
            platform: "live2d_server",
            content: "收到了旧格式消息，建议升级到标准格式",
            type: "text"
        });
    }
}

/**
 * 发送标准格式消息
 */
function sendStandardMessage(ws, options) {
    const { platform, content, type, motion, messageId } = options;

    const message = {
        message_info: {
            platform: platform,
            message_id: messageId || generateMessageId(),
            time: Date.now() / 1000.0,
            additional_config: {}
        },
        message_segment: {
            type: type,
            data: content
        }
    };

    // 添加动作信息
    if (motion) {
        message.message_info.additional_config.motion = motion;
    }

    ws.send(JSON.stringify(message));
    console.log(`📤 发送消息: ${content}`);
}

/**
 * 发送表情消息
 */
function sendEmojiMessage(ws, content, emojiData) {
    const message = {
        message_info: {
            platform: "live2d_server",
            message_id: generateMessageId(),
            time: Date.now() / 1000.0,
            additional_config: {}
        },
        message_segment: {
            type: "emoji",
            data: emojiData
        }
    };

    ws.send(JSON.stringify(message));
    console.log(`📤 发送表情消息: ${content}`);
}

/**
 * 发送语音消息
 */
function sendVoiceMessage(ws, content, voiceData) {
    const message = {
        message_info: {
            platform: "live2d_server",
            message_id: generateMessageId(),
            time: Date.now() / 1000.0,
            additional_config: {}
        },
        message_segment: {
            type: "voice",
            data: voiceData
        }
    };

    ws.send(JSON.stringify(message));
    console.log(`📤 发送��音消息: ${content}`);
}

/**
 * 发送错误消息
 */
function sendErrorMessage(ws, error) {
    sendStandardMessage(ws, {
        platform: "live2d_server",
        content: `错误: ${error}`,
        type: "text"
    });
}

/**
 * 生成消息ID
 */
function generateMessageId() {
    return `srv_${Date.now()}_${Math.floor(Math.random() * 1000)}`;
}

// 启动服务器
server.listen(8080, () => {
    console.log('✅ Live2D对话测试服务器已启动');
    console.log('📍 WebSocket地址: ws://localhost:8080/chat');
    console.log('🔧 支持功能:');
    console.log('   - Platform标识符验证');
    console.log('   - Authorization认证（可选）');
    console.log('   - 标准消息格式 (text/emoji/voice/seglist)');
    console.log('   - 旧格式兼容');
    console.log('   - 智能文本回复');
    console.log('');
    console.log('💡 测试建议:');
    console.log('   1. 在应用中配置连接地址: ws://localhost:8080/chat');
    console.log('   2. 设置平台标识符: live2d_android');
    console.log('   3. 发送包含"你好"、"动作"、"表情"、"语音"的消息测试不同功能');
    console.log('');
    console.log('🔄 服务器正在监听连接...');
});

// 优雅关闭处理
process.on('SIGINT', () => {
    console.log('\n🛑 正在关闭服务器...');
    server.close(() => {
        console.log('✅ 服务器已关闭');
        process.exit(0);
    });
});
