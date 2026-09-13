# 贡献指南

感谢你愿意为 dyparse 提交改进！在开始之前，请先阅读 [DISCLAIMER.md](DISCLAIMER.md) 并确认你的用途符合声明要求。

## 提交 Issue

- **Bug 报告**：请使用 Bug Report 模板，附上 Android 版本、机型、复现步骤与相关日志（`adb logcat`）。
- **功能建议**：请说明使用场景与预期行为，而不是只给一句"希望支持 X"。
- **安全问题**：请勿在公开 Issue 中披露可利用细节，先私下联系维护者。

⚠️ 请勿在 Issue / PR 中粘贴**任何真实 Cookie、token、服务器地址或密钥**。需要贴日志时请先脱敏。

## 提交 Pull Request

1. Fork 本仓库并从 `main` 拉出特性分支：`git checkout -b feat/your-feature`
2. 保持改动聚焦，一个 PR 只做一件事；避免混入无关的格式化改动。
3. 提交前请确保本地通过：
   ```bash
   ./gradlew test
   ./gradlew assembleDebug
   ```
4. 提交信息建议使用中文或英文的祈使句，说明**做了什么**与**为什么**。
5. 在 PR 描述中说明：改动目的、影响范围、验证方式（最好附截图 / 录屏）。

## 代码风格

- Kotlin 官方代码风格（`kotlin.code.style=official`），缩进 4 空格。
- UI 代码使用 Jetpack Compose，保持与现有 `ui/` 目录一致的组织方式。
- 新增逻辑请尽量补充单元测试，测试放在 `app/src/test/`。

## 禁止事项

- 提交任何真实凭据（Cookie / token / 密钥 / 签名证书）。
- 提交构建产物（APK / AAB / `build/` 目录）。
- 引入许可证与 GPL-3.0 不兼容的第三方代码。
- 提交用于绕过平台安全机制的新增攻击能力，或将其包装成商业服务。
