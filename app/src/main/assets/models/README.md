# Live2D模型目录

此目录用于存放Live2D模型文件。请将您的Live2D模型按以下结构组织：

```
models/
├── 模型名称1/
│   ├── 模型名称1.model3.json     # 主模型文件
│   ├── 模型名称1.moc3            # 模型数据
│   ├── 模型名称1.physics3.json   # 物理配置（可选）
│   ├── 模型名称1.pose3.json      # 姿势配置（可选）
│   ├── textures/                 # 纹理目录
│   │   ├── texture_00.png
│   │   └── ...
│   └── motions/                  # 动作目录（可选）
│       ├── motion1.motion3.json
│       └── ...
├── 模型名称2/
│   └── ...
└── README.md                     # 此说明文件
```

## 示例模型放置

如果您有现有的Live2D模型（如示例中的Haru、Hiyori等），请将它们移动到此目录：

1. 将 `assets/Haru/` 移动到 `assets/models/Haru/`
2. 将 `assets/Hiyori/` 移动到 `assets/models/Hiyori/`
3. 依此类推...

## 注意事项

- 每个模型必须有自己的独立文件夹
- 主模型文件名应该以 `.model3.json` 结尾
- 确保所有引用的纹理和动作文件都在正确的相对路径下
- 应用会自动扫描此目录下的所有模型文件夹
