# Git 知识点 02：版本查看、标签与克隆到 IDEA

> 前置：[知识点 01：提交与推送](01-提交与推送.md)（`git add` / `git commit` / `git push`）
> 适用仓库：mini-mall。Git 根目录是 `mini-mall/`，代码在子目录 `v1/`，远端为 `https://github.com/3314117689/mini-mall.git`。
> 学习目标：能说清"当前开发版本"和"历史版本"的区别，并能把任意一个版本完整拉进 IDEA 独立运行。

## 0. 一句话结论

- **当前开发版本** = `main` 分支的最新提交（HEAD），不是某个 tag。
- **历史版本** = tag（如 `v1.0`）或存档分支（如 `stage-1`），固定不动。
- **拉进 IDEA** = 克隆到一个独立目录，再用 IDEA 单独打开；本项目 pom 在 `v1/` 子目录，需要手动识别为 Maven 项目。

## 1. 先建立四个位置的概念

| 位置 | 是什么 | 常用命令 |
|---|---|---|
| 工作区 | 你正在编辑的文件 | `git status` 看改动 |
| 暂存区 | 准备提交的改动 | `git add` 放入 |
| 本地仓库 | 本机的提交历史 | `git commit` 写入、`git log` 查看 |
| 远程仓库 | GitHub 上的副本 | `git push` 上传、`git pull`/`git fetch` 下载 |

一句话：`commit` 只到本地，`push` 才上 GitHub。所以"我提交了"和"GitHub 上能看见"是两件事。

## 2. 查看当前开发到哪了

在 `mini-mall/` 下执行：

```bash
git status -sb                 # 当前分支 + 有没有未提交改动
git log --oneline --graph --decorate --all -10
git branch -vv                 # 本地分支和它跟踪的远端分支
git tag -n                     # 所有版本标签和说明
```

每个命令回答的问题：

| 命令 | 回答的问题 |
|---|---|
| `git status -sb` | 我现在在哪个分支？有没有没提交的代码？本地比远端多几个提交？ |
| `git log --oneline --graph --decorate --all` | 提交历史长什么样？分支/tag 指向哪个提交？ |
| `git branch -vv` | 本地分支跟踪哪个远端分支？落后还是领先？ |
| `git tag -n` | 有哪些阶段快照？各自对应什么说明？ |

同一件事在 IDEA 和 GitHub 上的入口：

- IDEA：底部 **Git** → **Log**（提交图）、**Branches**（分支）、**Tags**（标签）
- GitHub：仓库首页的 **Commits**、**Tags**、**Releases**；对比两个版本用 `https://github.com/3314117689/mini-mall/compare/v1.0...main`

## 3. 分支和标签的区别

| | 分支 branch | 标签 tag |
|---|---|---|
| 会不会移动 | 会，每次提交向前移动 | 不会，永远指向创建时那个提交 |
| 用途 | 继续开发 | 标记"某一阶段的成品" |
| 本仓库例子 | `main`、`stage-1` | `v1.0` |

- `main`：当前开发主线，最新提交就是当前开发版本。
- `stage-1`：阶段 1 的存档分支，用于回看当时的完整代码。
- `v1.0`：阶段 1 交付快照标签。

判断"我现在开发到哪"只看 `main` 的最新提交；判断"某个阶段交付了什么"看 tag。

## 4. 把当前开发版本拉到 IDEA（单独项目）

### 前提：先让远端是最新的

```bash
git status -sb          # 有未提交改动就先 commit
git push origin main    # 本地领先远端时必须推送
```

没推送就克隆，拉到的只是远端最后一次推送的旧代码。

### 步骤

1. 打开 IDEA 欢迎页 → **Get from VCS**（已打开项目时用 File → New → Project from Version Control）
2. 填写：
   - URL：`https://github.com/3314117689/mini-mall.git`
   - Directory：独立目录，例如 `D:\projects\mini-mall-current`
   - 不要克隆到现有工程目录里面，否则两个项目会互相干扰
3. 克隆完成后根目录是 `mini-mall`，代码在 `v1/`
4. 如果 IDEA 没自动识别 Maven：右键 `v1/pom.xml` → **Add as Maven Project**
5. 设置 JDK 17：
   - File → Project Structure → Project SDK 选 17
   - Settings → Build, Execution, Deployment → Build Tools → Maven → Runner → JRE 选 17
6. Maven 面板点 **Reload All Maven Projects**，等待依赖下载完成
7. 如果访问 GitHub/依赖下载卡住：Settings → Appearance & Behavior → System Settings → HTTP Proxy，手动填 `127.0.0.1:7891`
8. 建运行配置：`UserServiceApplication`、`ProductServiceApplication`、`OrderServiceApplication`

## 5. 拉取某个历史版本

### 方式 A：worktree（推荐，只看不改）

在当前仓库里执行：

```bash
cd D:\bishe\workspace\gongju\eclipseworkspace\mini-mall
git worktree add D:\projects\mini-mall-v1 v1.0
```

再用 IDEA 打开 `D:\projects\mini-mall-v1`。特点：

- 不用重新克隆，不重复下载 Git 历史
- 不切换、不打断你当前工作区的代码
- 用完删除目录后执行 `git worktree prune` 清理记录

### 方式 B：克隆指定分支到新目录

```bash
git clone -b stage-1 https://github.com/3314117689/mini-mall.git D:\projects\mini-mall-stage1
```

只适合要看存档分支，且必须重新下载一份仓库。

### 方式 C：在当前仓库切到某个 tag（会改变当前工作区）

```bash
git switch -c review-v1.0 v1.0
```

基于 tag 新建一个分支再查看，避免直接处于 detached HEAD 状态。看完切回：

```bash
git switch main
```

## 6. 两个项目同时打开要注意

| 注意点 | 说明 |
|---|---|
| 端口冲突 | 两套服务同时启动会抢 8081/8082/8083，同一时间只启动一边 |
| 数据库共用 | 两边连同一个 MySQL，读写同一份数据 |
| `.idea` 目录 | 每个项目各自独立，且已被 .gitignore 忽略 |
| Maven 仓库 | `~/.m2` 共用，依赖不会重复下载 |

## 7. 常见坑

1. **克隆下来没有最新代码**：本地没 commit 或没 push，远端还是旧的。先 `git status -sb` 看有没有 `ahead`。
2. **IDEA 里没有 Maven 图标**：pom 在 `v1/` 子目录，右键 `v1/pom.xml` → Add as Maven Project。
3. **拉取失败/超时**：配置 IDEA 的 HTTP Proxy 为 `127.0.0.1:7891`。
4. **克隆到已有项目里**：会出现项目套项目，Maven 索引混乱，务必使用独立目录。
5. **直接 checkout tag 后改代码**：会处于 detached HEAD，提交容易丢。先基于 tag 建分支。

## 8. 自测（全部能答上才算过关）

1. 不看笔记，说出查看"当前分支 + 最新提交"的两条命令。
2. `main` 最新提交和 `v1.0` 有什么区别？
3. 本项目 pom 在子目录，IDEA 不识别 Maven 时怎么处理？
4. `git worktree` 和 `git clone` 各适合什么场景？
5. 两个项目同时启动会出什么问题？

## 9. 命令速查

| 目的 | 命令 |
|---|---|
| 看当前状态 | `git status -sb` |
| 看提交图 | `git log --oneline --graph --decorate --all -10` |
| 看分支跟踪关系 | `git branch -vv` |
| 看标签 | `git tag -n` |
| 推送当前分支 | `git push origin main` |
| 克隆指定分支 | `git clone -b stage-1 <url> <目录>` |
| 基于 tag 建分支 | `git switch -c review-v1.0 v1.0` |
| 拉历史版本到独立目录 | `git worktree add <目录> v1.0` |
| 清理 worktree 记录 | `git worktree prune` |
