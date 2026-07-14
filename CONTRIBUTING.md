# 贡献指南

感谢你对 PlayerMoveLog 的关注！请遵循以下流程参与贡献。

---

## 贡献流程

```
1. Fork 仓库
     ↓
2. 在你的 Fork 中创建功能分支（不要直接在 main/master 上改）
     ↓
3. 在分支上开发和本地测试
     ↓
4. 确保编译通过：mvn clean package
     ↓
5. 写 Issue 描述你的改动
     ↓
6. 从你的功能分支发起 Pull Request 到本仓库的 master
     ↓
7. 等待 Review
```

## 详细步骤

### 1. Fork 仓库

点击 GitHub 页面右上角的 **Fork** 按钮，将仓库 Fork 到你的账号下。

### 2. 创建功能分支

```bash
git clone https://github.com/你的用户名/movelog.git
cd movelog
git checkout -b feature/你的功能名   # 例如：feature/add-death-log
```

**重要**：不要在 `master` 分支上直接开发！必须创建独立的功能分支。

### 3. 开发和测试

- 确保代码风格与现有代码一致
- 编译测试：`mvn clean package`
- 在本地 Paper 1.21.x 服务器上实际测试你的改动
- 确认控制台无报错、TPS 无异常

### 4. 提交代码

```bash
git add -A
git commit -m "feat: 简短描述你的改动"
git push origin feature/你的功能名
```

提交信息建议使用 [Conventional Commits](https://www.conventionalcommits.org/) 格式：
- `feat: xxx` — 新功能
- `fix: xxx` — 修复 Bug
- `docs: xxx` — 文档
- `refactor: xxx` — 重构

### 5. 创建 Issue

在发起 PR 之前，先创建一个 Issue 描述：
- 你要解决的问题是什么
- 你的方案是什么
- 可能的副作用

### 6. 发起 Pull Request

从你 Fork 的 **功能分支** 发起 PR 到本仓库的 `master` 分支。

**PR 不会被接受的情况：**
- 直接从你 Fork 的 `master`/`main` 分支发起（请使用功能分支）
- 未经测试
- 代码风格严重不符
- 没有对应的 Issue

## 核心规则总结

| 规则 | 说明 |
|------|------|
| 🔒 先 Fork | 不直接推送本仓库 |
| 🌿 开分支 | 在 Fork 中创建功能分支，不在 master 改 |
| ✅ 要测试 | `mvn clean package` 通过 + 服务器实测 |
| 📝 先 Issue | PR 前创建 Issue 说明来意 |
| 🔀 PR 来自功能分支 | 不接受来自 Fork 的 master/main 分支的 PR |

---

## 开发环境

| 组件 | 版本 |
|------|------|
| Java | 21 (Temurin OpenJDK) |
| Paper API | 1.21.1-R0.1-SNAPSHOT |
| Maven | 3.9+ |
| 编码 | UTF-8 |

## 代码规范

- 所有跨线程字段使用 `volatile` 或 `Atomic*`
- 所有文件 I/O 显式指定 `StandardCharsets.UTF_8`
- 格式化输出使用 `Locale.US`
- 热重载采用 validate-then-switch 模式
- 异常静默 catch + log，不抛向主线程
- 每个 `lock.lock()` 必须在 `finally` 中 `unlock()`
- `flush()` 和 `close()` 必须独立 try-catch

---

<p align="center">感谢你的贡献 ❤️</p>
