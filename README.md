# xiaohu_Prison — 监狱管理插件

**Paper 服务端插件（Minecraft 26.2 / Java 25）**，用来**处罚在游戏中破坏的玩家**：管理员把破坏者关进监狱世界的专属牢房，囚犯必须挖够指定数量的方块（默认黑曜石，`mine.breakable-material` 可配）才能自动获释。

功能：房间手动标记、黑曜石矿场自动补满、指令封锁、背包托管（入狱收走/释放归还，离线释放自动暂存）、PvP 进度抢夺、任务完成烟花庆祝、监狱世界虚空生成、重启/重进持久化、**监狱参观**（普通玩家 `/prison visit` 进监狱参观，禁破坏/放置/丢弃）。

## 构建

```sh
# Windows
gradlew.bat build

# Linux / macOS
./gradlew build
```

首次构建会下载 Gradle、JDK 25 toolchain 与依赖，需 5-15 分钟。

产物：`build/libs/xiaohu_Prison-1.0.0.jar`

## 安装

将 `build/libs/xiaohu_Prison-1.0.0.jar` 复制到 Paper 服务器的 `plugins/` 目录，**重启服务器**（勿用 `/reload`，会破坏插件状态）。

## 主要指令（需 `prison.admin` 权限，默认 OP）

| 指令 | 作用 |
|---|---|
| `/prison send <玩家> <数量>` | 关押在线玩家（数量 = 需挖的方块个数） |
| `/prison release <玩家>` | 释放玩家，传送回入狱前位置 |
| `/prison list` | 查看在狱玩家、房间号与任务进度 |
| `/prison pos <编号>` / `pos list` / `de pos <编号>` | 标记 / 查看 / 删除房间 |
| `/prison obsidian` / `obsidian list` / `obsidian del <编号>` | 生成 / 查看 / 删除黑曜石矿场 |
| `/prison tp` / `spawn yes` / `back` | 传送至监狱世界 / 设置监狱世界出生点 / 回主世界 |
| `/prison visit` / `visit leave` | **参观监狱 / 离开参观（普通玩家可用，默认所有玩家有权限）** |
| `/court`（别名 `/fy`、`/ct`） | **人民法院**：起诉/公诉/开庭/宣判/驳回/撤诉、律师招募、旁听、法庭位置与按钮标记（详见下方法院说明） |

完整帮助：游戏内 `/prison help`（管理员）；普通玩家无参数输入 `/prison` 可见参观用法。
法院模块帮助：游戏内无参数 `/court` 或 `/court help`（可点击，按权限显示）。

## 人民法院（`/court`）

玩家可用：`/court accuse <玩家> <理由>`（起诉）、`/court list` / `case <编号>` / `history [玩家]`（查询）、`/court lawyer` / `volunteer`（律师招募）、`/court watch` / `unwatch` / `leave`（旁听）。
法官（`court.judge` || `prison.admin` || OP）：`/court prosecute`（公诉）、`trial`（开庭）、`verdict`（宣判）、`dismiss`（驳回）、`pos`（标记法庭位置）、`btn`（标记法官桌按钮）。
配置：`plugins/xiaohu_Prison/court.yml`（独立文件，`default-sentence`/`max-sentence`/`auto-execute`/`courtroom-enabled`/`buttons-enabled` 等）；案件存 `cases.yml`，法庭位置存 `courtroom.yml`。

## 布局

- `src/main/java/com/xiaohu/prison/` — 插件源码（主类 `PrisonPlugin`）
- `src/main/resources/plugin.yml` — 插件元数据（命令、权限、api-version）
- `src/main/resources/config.yml` — 默认配置（监狱世界、出生点、矿场、挖掘疲劳）
- 运行时数据（`plugins/xiaohu_Prison/`）：
  - `prisoners.yml` — 在狱玩家（原位置、任务进度、背包/效果快照）
  - `cells.yml` — 手动标记的房间位置
  - `mines.yml` — 黑曜石矿场注册表
  - `pending-restore.yml` — 离线释放暂存（重进服时归还背包）
  - `visit.yml` — 监狱参观传送点（`/prison visit pos` 标记）
