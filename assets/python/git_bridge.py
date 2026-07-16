#!/usr/bin/env python3
"""
Git 命令桥接脚本 — 基于 dulwich (纯 Python Git 实现)
在 Android 内嵌 Python 环境中提供常用 git 命令支持。

用法: python git_bridge.py <git 子命令> [参数...]
例如: python git_bridge.py clone https://github.com/user/repo.git
      python git_bridge.py add -A
      python git_bridge.py commit -m "message"
      python git_bridge.py push origin main
"""

import sys
import os
import subprocess

# 确保 pip 安装的包在 sys.path 中
_site_dir = os.path.join(os.environ.get("PYTHONHOME", ""), "lib", "python3.14", "site-packages")
if _site_dir not in sys.path:
    sys.path.insert(0, _site_dir)


def ensure_dulwich():
    """确保 dulwich 已安装，未安装时自动 pip install"""
    try:
        import dulwich
        return True
    except ImportError:
        print("首次使用 git，正在安装 dulwich (纯 Python Git 库)...", file=sys.stderr)
        try:
            import pip
            sys.argv = ["pip", "install", "--no-cache-dir", "dulwich"]
            pip.main()
            # 重新尝试导入
            import dulwich
            print("dulwich 安装完成。", file=sys.stderr)
            return True
        except Exception as e:
            print(f"dulwich 安装失败: {e}", file=sys.stderr)
            print("请手动执行: pip install dulwich", file=sys.stderr)
            return False


def cmd_init(args):
    """git init [目录]"""
    from dulwich.repo import Repo
    target = args[0] if args else "."
    repo = Repo.init(target)
    print(f"已初始化空 Git 仓库: {os.path.abspath(target)}/.git/")
    return 0


def cmd_clone(args):
    """git clone <url> [目录]"""
    from dulwich import porcelain
    if not args:
        print("用法: git clone <url> [目录]", file=sys.stderr)
        return 1
    url = args[0]
    target = args[1] if len(args) > 1 else None
    print(f"正在克隆 {url} ...")
    porcelain.clone(url, target)
    dest = target or url.rstrip("/").split("/")[-1].replace(".git", "")
    print(f"克隆完成: {dest}")
    return 0


def cmd_add(args):
    """git add <文件...> / git add -A"""
    from dulwich import porcelain
    paths = []
    all_files = False
    for a in args:
        if a in ("-A", "--all", "."):
            all_files = True
        else:
            paths.append(a)
    if all_files:
        porcelain.add(".", paths=[])  # 空 paths 表示添加全部
        print("已暂存所有更改")
    elif paths:
        porcelain.add(".", paths=paths)
        print(f"已暂存: {', '.join(paths)}")
    else:
        print("没有指定文件", file=sys.stderr)
        return 1
    return 0


def cmd_commit(args):
    """git commit -m "message""""
    from dulwich import porcelain
    message = ""
    i = 0
    while i < len(args):
        if args[i] == "-m" and i + 1 < len(args):
            message = args[i + 1]
            i += 2
        elif args[i].startswith("-m"):
            message = args[i][2:]
            i += 1
        else:
            i += 1
    if not message:
        print("请使用 -m 指定提交信息", file=sys.stderr)
        return 1
    try:
        # 检查是否有暂存的更改
        status = porcelain.status(".")
        if not status.staged:
            print("没有暂存的更改，无需提交", file=sys.stderr)
            return 1
        porcelain.commit(".", message=message.encode())
        print(f'[{porcelain.active_branch(".")}] {message}')
        return 0
    except Exception as e:
        print(f"提交失败: {e}", file=sys.stderr)
        return 1


def cmd_status(args):
    """git status"""
    from dulwich import porcelain
    try:
        result = porcelain.status(".")
        branch = porcelain.active_branch(".") or "HEAD"
        print(f"位于分支 {branch}")
        staged = result.staged
        if staged[0]:  # added
            print("\n要提交的变更:")
            for f in staged[0]:
                print(f"  新文件:   {f.decode() if isinstance(f, bytes) else f}")
        if staged[1]:  # modified
            for f in staged[1]:
                print(f"  修改:     {f.decode() if isinstance(f, bytes) else f}")
        if staged[2]:  # deleted
            for f in staged[2]:
                print(f"  删除:     {f.decode() if isinstance(f, bytes) else f}")
        if not any(staged):
            print("nothing to commit, working tree clean")
        return 0
    except Exception as e:
        print(f"错误: {e}", file=sys.stderr)
        return 1


def cmd_log(args):
    """git log [--oneline] [-n 数量]"""
    from dulwich import porcelain
    try:
        max_entries = 20
        oneline = False
        i = 0
        while i < len(args):
            if args[i] == "--oneline":
                oneline = True
            elif args[i] == "-n" and i + 1 < len(args):
                max_entries = int(args[i + 1])
                i += 1
            i += 1

        repo = porcelain.open_repo(".")
        from dulwich.objects import Commit
        walker = repo.get_walker(max_entries=max_entries)
        for entry in walker:
            c = entry.commit
            sha = c.id.decode() if isinstance(c.id, bytes) else str(c.id)
            author = c.author.decode() if isinstance(c.author, bytes) else str(c.author)
            msg = c.message.decode() if isinstance(c.message, bytes) else str(c.message)
            msg = msg.strip()
            if oneline:
                print(f"{sha[:7]} {msg.splitlines()[0] if msg else ''}")
            else:
                print(f"commit {sha}")
                print(f"Author: {author}")
                print(f"    {msg}")
                print()
        return 0
    except Exception as e:
        print(f"错误: {e}", file=sys.stderr)
        return 1


def cmd_push(args):
    """git push [remote] [branch]"""
    from dulwich import porcelain
    remote = args[0] if args else "origin"
    branch = args[1] if len(args) > 1 else None
    try:
        print(f"推送到 {remote}...")
        porcelain.push(".", remote_location=remote, refspec=branch)
        print("推送完成")
        return 0
    except Exception as e:
        # dulwich push 可能需要凭据
        print(f"推送失败: {e}", file=sys.stderr)
        print("提示: 如果需要认证，请先配置 remote URL 包含凭据:", file=sys.stderr)
        print("  git remote set-url origin https://user:token@github.com/user/repo.git", file=sys.stderr)
        return 1


def cmd_pull(args):
    """git pull [remote]"""
    from dulwich import porcelain
    remote = args[0] if args else "origin"
    try:
        print(f"从 {remote} 拉取...")
        porcelain.pull(".", remote_location=remote)
        print("拉取完成")
        return 0
    except Exception as e:
        print(f"拉取失败: {e}", file=sys.stderr)
        return 1


def cmd_fetch(args):
    """git fetch [remote]"""
    from dulwich import porcelain
    remote = args[0] if args else "origin"
    try:
        porcelain.fetch(".", remote_location=remote)
        print("获取完成")
        return 0
    except Exception as e:
        print(f"获取失败: {e}", file=sys.stderr)
        return 1


def cmd_branch(args):
    """git branch [分支名] / git branch -a"""
    from dulwich import porcelain
    try:
        if args and not args[0].startswith("-"):
            # 创建新分支
            porcelain.branch_create(".", args[0])
            print(f"创建分支: {args[0]}")
            return 0
        # 列出分支
        list_all = "-a" in args or "--all" in args
        branches = porcelain.branch_list(".")
        active = porcelain.active_branch(".")
        for b in branches:
            name = b.decode() if isinstance(b, bytes) else str(b)
            prefix = "* " if name == active else "  "
            print(f"{prefix}{name}")
        return 0
    except Exception as e:
        print(f"错误: {e}", file=sys.stderr)
        return 1


def cmd_checkout(args):
    """git checkout <分支>"""
    from dulwich import porcelain
    if not args:
        print("用法: git checkout <分支>", file=sys.stderr)
        return 1
    try:
        porcelain.checkout_branch(".", args[0])
        print(f"切换到分支 '{args[0]}'")
        return 0
    except Exception as e:
        print(f"错误: {e}", file=sys.stderr)
        return 1


def cmd_remote(args):
    """git remote [-v] / git remote add <name> <url> / git remote set-url <name> <url>"""
    from dulwich.repo import Repo
    try:
        repo = Repo(".")
        config = repo.get_config()
        if not args or args[0] == "-v":
            # 列出 remote
            for section in config.sections():
                if section[0] == b"remote":
                    name = section[1].decode()
                    url = config.get((b"remote", section[1]), b"url").decode()
                    print(f"{name}\t{url}")
            return 0
        elif args[0] == "add" and len(args) >= 3:
            name = args[1].encode()
            url = args[2].encode()
            config.set((b"remote", name), b"url", url)
            config.set((b"remote", name), b"fetch", f"+refs/heads/*:refs/remotes/{args[1]}/*".encode())
            config.write_to_path()
            print(f"添加远程仓库: {args[1]} -> {args[2]}")
            return 0
        elif args[0] == "set-url" and len(args) >= 3:
            name = args[1].encode()
            url = args[2].encode()
            config.set((b"remote", name), b"url", url)
            config.write_to_path()
            print(f"设置 {args[1]} URL: {args[2]}")
            return 0
        elif args[0] == "remove" and len(args) >= 2:
            name = args[1].encode()
            del config[(b"remote", name)]
            config.write_to_path()
            print(f"删除远程仓库: {args[1]}")
            return 0
        else:
            print(f"不支持的 remote 子命令: {args}", file=sys.stderr)
            return 1
    except Exception as e:
        print(f"错误: {e}", file=sys.stderr)
        return 1


def cmd_config(args):
    """git config user.name "xxx" / git config user.email "xxx" / git config --list"""
    from dulwich.repo import Repo
    try:
        if "--list" in args or "-l" in args:
            repo = Repo(".")
            config = repo.get_config()
            for section in config.sections():
                for key, value in config.items(section):
                    s = ".".join(s.decode() for s in section)
                    k = key.decode()
                    v = value.decode()
                    print(f"{s}.{k}={v}")
            return 0
        if len(args) >= 3:
            # git config user.name "value"
            key_parts = args[0].split(".")
            section = tuple(p.encode() for p in key_parts[:-1])
            key = key_parts[-1].encode()
            value = args[2].encode()
            repo = Repo(".")
            config = repo.get_config()
            config.set(section, key, value)
            config.write_to_path()
            return 0
        if len(args) >= 1:
            # 读取: git config user.name
            key_parts = args[0].split(".")
            section = tuple(p.encode() for p in key_parts[:-1])
            key = key_parts[-1].encode()
            repo = Repo(".")
            config = repo.get_config()
            try:
                value = config.get(section, key)
                print(value.decode())
            except KeyError:
                pass
            return 0
        print("用法: git config <key> [value]", file=sys.stderr)
        return 1
    except Exception as e:
        print(f"错误: {e}", file=sys.stderr)
        return 1


def cmd_tag(args):
    """git tag [标签名] [-m "信息"]"""
    from dulwich import porcelain
    try:
        if not args:
            # 列出标签
            repo = porcelain.open_repo(".")
            for ref in repo.refs.keys():
                if ref.startswith(b"refs/tags/"):
                    print(ref[len(b"refs/tags/"):].decode())
            return 0
        tag_name = args[0]
        message = ""
        if "-m" in args:
            idx = args.index("-m")
            if idx + 1 < len(args):
                message = args[idx + 1]
        if message:
            porcelain.tag_create(".", tag_name, message=message.encode())
        else:
            porcelain.tag_create(".", tag_name)
        print(f"创建标签: {tag_name}")
        return 0
    except Exception as e:
        print(f"错误: {e}", file=sys.stderr)
        return 1


def cmd_diff(args):
    """git diff"""
    from dulwich import porcelain
    try:
        result = porcelain.diff(".")
        if result:
            print(result.decode() if isinstance(result, bytes) else result)
        else:
            print("没有差异")
        return 0
    except Exception as e:
        print(f"错误: {e}", file=sys.stderr)
        return 1


def cmd_rm(args):
    """git rm <文件...>"""
    from dulwich import porcelain
    if not args:
        print("用法: git rm <文件...>", file=sys.stderr)
        return 1
    try:
        porcelain.remove(".", paths=args)
        print(f"已移除: {', '.join(args)}")
        return 0
    except Exception as e:
        print(f"错误: {e}", file=sys.stderr)
        return 1


def cmd_version(args):
    """git version"""
    import dulwich
    print(f"git version (dulwich {dulwich.__version__}) — 内嵌 Python Git")
    return 0


# 子命令分发表
COMMANDS = {
    "init": cmd_init,
    "clone": cmd_clone,
    "add": cmd_add,
    "commit": cmd_commit,
    "status": cmd_status,
    "log": cmd_log,
    "push": cmd_push,
    "pull": cmd_pull,
    "fetch": cmd_fetch,
    "branch": cmd_branch,
    "checkout": cmd_checkout,
    "remote": cmd_remote,
    "config": cmd_config,
    "tag": cmd_tag,
    "diff": cmd_diff,
    "rm": cmd_rm,
    "version": cmd_version,
    "--version": cmd_version,
}


def main():
    if len(sys.argv) < 2:
        print("用法: git <子命令> [参数...]", file=sys.stderr)
        print("支持: " + ", ".join(COMMANDS.keys()), file=sys.stderr)
        return 1

    subcmd = sys.argv[1]
    args = sys.argv[2:]

    if subcmd not in COMMANDS:
        print(f"不支持的 git 子命令: {subcmd}", file=sys.stderr)
        print("支持: " + ", ".join(COMMANDS.keys()), file=sys.stderr)
        return 1

    # version 不需要 dulwich
    if subcmd in ("version", "--version"):
        return COMMANDS[subcmd](args)

    if not ensure_dulwich():
        return 1

    return COMMANDS[subcmd](args)


if __name__ == "__main__":
    sys.exit(main())
