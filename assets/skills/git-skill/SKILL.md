# Git Skill for agent-toolbox

## Description
本技能用于 agent-toolbox 项目中的 Git 操作指南，涵盖常用命令、权限问题解决方案及最佳实践。

## When to use
- 用户需要克隆、回退、重置或管理 agent-toolbox 仓库时
- 遇到 Git 权限错误（如 dubious ownership）时
- 需要批量处理 Git 操作时
- 使用 dulwich 或内嵌 Git 二进制时

## Git 操作指南

### 基础命令
- `git clone <url> <target_dir>`：克隆仓库到指定目录
- `git reset --hard <commit>`：硬回退到指定 commit
- `git status`：查看当前状态
- `git log --oneline`：查看简洁提交历史

### 常见问题与解决方案

#### 1. 权限错误：dubious ownership
当执行 Git 命令时遇到 "fatal: detected dubious ownership in repository"，使用以下命令配置安全目录：
```bash
git config --global safe.directory /path/to/repo
```
或使用 `-c safe.directory=/path/to/repo` 临时绕过。

#### 2. 符号链接权限问题
在 Android 环境中，创建符号链接可能失败（Permission denied）。使用 `-c core.symlinks=false` 禁用符号链接：
```bash
git -C /path/to/repo -c core.symlinks=false reset --hard <commit>
```

#### 3. 段错误与 dulwich 回退
如果内嵌 Git 出现段错误（退出码 139），系统会自动回退到 dulwich 纯 Python 实现。此时某些 Git 子命令（如 `-C`）可能不支持，建议直接进入目录操作：
```bash
cd /path/to/repo
git reset --hard <commit>
```

#### 4. 克隆失败与清理
克隆前确保目标目录不存在或已删除：
```bash
rm -rf /sdcard/Download/agent-toolbox /sdcard/Download/agent-toolbox.git
git clone https://github.com/Aasdqwe1/agent-toolbox.git /sdcard/Download/agent-toolbox
```

### 常用工作流

#### 回退到指定 commit（完整流程）
1. 删除旧目录（如有）：`rm -rf /sdcard/Download/agent-toolbox`
2. 克隆仓库：`git clone https://github.com/Aasdqwe1/agent-toolbox.git /sdcard/Download/agent-toolbox`
3. 执行回退：`git -C /sdcard/Download/agent-toolbox -c core.symlinks=false reset --hard <commit>`

#### 更新到最新版本
```bash
cd /sdcard/Download/agent-toolbox
git pull origin main
```

#### 创建分支与切换
```bash
cd /sdcard/Download/agent-toolbox
git checkout -b new-feature
git branch
```

### 注意事项
- 路径优先使用 `/sdcard/Download/`，避免使用 `/storage/emulated/0/` 可能导致的路径解析问题
- 大量文件操作时建议使用 `-c core.symlinks=false` 避免符号链接权限错误
- 若 Git 操作连续失败 2 次，建议改用 Python 的 dulwich 库直接操作

### 示例

#### 克隆并回退到特定 commit
```bash
rm -rf /sdcard/Download/agent-toolbox
git clone https://github.com/Aasdqwe1/agent-toolbox.git /sdcard/Download/agent-toolbox
git -C /sdcard/Download/agent-toolbox -c core.symlinks=false reset --hard 74c3cdae15fd6882da075cc06fa2a46edbbba91f
```

#### 仅重置索引（不修改工作区）
```bash
git -C /sdcard/Download/agent-toolbox reset --mixed <commit>
```

## 技能工具
本技能不提供直接调用的工具，而是作为知识库供 AI 在执行 Git 相关任务时参考。
