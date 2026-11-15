# Changelog

本文件遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/) 格式。

## [v0.1.0] — 首个公开版本

### 功能（全部独立开关）

- **剪贴板增强**：历史条数 / 保存天数解除限制；敏感信息（OTP / 卡号 / 关键词 / 密码框）不入历史
- **Flag 覆写引擎**：DexKit 动态定位 Phenotype flag 读取器；17 组预设（AI 写作 / Rambler /
  多语言 / Emoji Kitchen / 禁遥测 / 圆角键帽 / 词学习…）+ 自定义 flag JSON
- **AMOLED 纯黑**：深色模式 surface 容器改纯黑
- **候选 emoji 提亮**：候选栏 emoji 100% 不透明
- **密码框完整键盘**：受限输入框显示全键盘（默认关）
- **九键半角标点**：数字页 `，。` → 半角、标点栏 → `+ − × ÷`（输出 + 键面同步）
- **键盘工具栏**：剪贴板保险库（文本+图片）/ 粘贴 / 全选 / 选择模式 / 光标 / 撤销（含摇一摇）/
  文本快捷 / 截图自动入库 / Auto Expand 接管
- **候选词译词**：12 组离线词典（zh↔en/ja/de/fr/es/ru）、构词回退、DP 分词组合、
  用户词表导入、注入横幅 —— 纯离线
- **双拼键帽提示**：6 方案、动态提示、主字母隐藏/字号、分号键、灵活 emoji 联想
- **Termux 兼容**：拼音正常输入 + 进出终端自动切换语言
- **RGB 灯效**：9 特效 / 键帽流光 / 滑行轨迹 / WPM 过热 / 底光 / root 恢复原厂主题

### 工程设施

- `compat-test/`：离线锚点扫描器（字符串池 + class_data 方法/字段级解析），
  已验证 Gboard 16.6.3 与 17.8.3-beta
- 兼容性哨兵 workflow：每周自动拉取新版 Gboard、刷新矩阵、锚点漂移自动开 issue
- JVM 单元测试：词典管线 / 语法修正 / 九键映射 / 敏感文本规则
- GitHub Actions 构建与 tag 自动发版；`update.json` 供 LSPosed 管理器更新检查

### 设计决策

- 100% 本地：不申请 INTERNET 权限，无任何云端引擎依赖；
  语音换源与在线翻译类功能刻意不整合
- libxposed 新 API 102（remote preferences 配置通道，`requestScope` 一键作用域）
