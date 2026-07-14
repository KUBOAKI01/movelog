# PlayerMoveLog — 玩家移动轨迹定时记录插件

> 轻量级 Paper 1.21.x 插件，每 1 分钟异步记录所有在线玩家的坐标与手持物品，零阻塞主线程。

---

## 目录

1. [快速安装](#快速安装)
2. [功能概览](#功能概览)
3. [日志格式说明](#日志格式说明)
4. [命令参考](#命令参考)
5. [配置参考](#配置参考)
6. [示例解析代码](#示例解析代码)
7. [性能设计](#性能设计)
8. [性能测试建议](#性能测试建议)
9. [常见问题](#常见问题)

---

## 快速安装

### 要求

| 组件 | 版本要求 |
|------|---------|
| 服务端 | Paper 1.21.x（或兼容的 Fork，如 Purpur、Pufferfish） |
| Java | 21 或更高 |

### 安装步骤

1. 将 `PlayerMoveLog-1.0.0.jar` 复制到服务器的 `plugins/` 目录。
2. 重启服务器（或执行 `plugman load PlayerMoveLog` 等热加载命令）。
3. 安装完成，无需任何额外配置——即插即用。

插件启动后将自动在 **服务器根目录** 下创建 `movelog/` 文件夹（与 `world` 目录同级），日志文件按时间块自动生成（默认每 4 小时切分），例如：

```
服务器根目录/
├── world/
├── world_nether/
├── world_the_end/
├── plugins/
│   └── PlayerMoveLog-1.0.0.jar
└── movelog/            ← 自动创建
    ├── 2026-07-14-00.log    ← 00:00 ~ 03:59
    ├── 2026-07-14-04.log    ← 04:00 ~ 07:59
    ├── 2026-07-14-08.log    ← 08:00 ~ 11:59
    └── ...
```

---

## 功能概览

| 特性 | 说明 |
|------|------|
| 🕐 定时记录 | 默认每 60 秒自动记录一次所有在线玩家数据 |
| 📝 标准格式 | `北京时间 \| 玩家名 \| 世界:X:Y:Z \| 手持物品`，坐标精确到两位小数 |
| 📁 按时段切分 | 默认每 4 小时一个 `.log` 文件，可配置为 1/2/3/4/6/8/12/24 小时，UTF-8 编码 |
| ⚡ 零阻塞主线程 | 所有 I/O 在后台低优先级线程中执行，线程属性仅首次设置 |
| 🛡️ TPS 保护 | TPS 低于阈值（默认 18）自动跳过；NaN/Infinity 配置值自动拦截 |
| 📦 缓冲批量写入 | 8 KiB BufferedWriter + AtomicBoolean 防重入，>100 玩家分批写入 |
| 🔄 安全热重载 | 全部配置项支持热重载，validate-then-switch 策略，失败保留旧实例 |
| 🎮 简单命令 | `/movelog on/off/reload/status`，Tab 补全 |
| 🛑 参数校验 | 非法配置值（0/负数/NaN/非法时区）自动拦截或重置 |
| 👤 真人过滤 | `getAddress() == null` 跳过 NPC/假玩家 |
| 🧹 资源保护 | flush 与 close 独立 try-catch 防止 fd 泄漏；路径穿越自动检测回退 |
| 💨 内存优化 | `Entity.getX/Y/Z()` 替代 `getLocation()` 消除对象分配；`StringBuilder` + `DecimalFormat` 替代 `String.format` |

---

## 日志格式说明

### 输出格式

每条日志独占一行，字段之间以 ` | `（竖线 + 空格）分隔：

```
北京时间 | 玩家名称 | 世界名称:X坐标:Y坐标:Z坐标 | 手持物品
```

### 字段详解

| 序号 | 字段 | 格式 | 示例 | 说明 |
|------|------|------|------|------|
| 1 | 北京时间 | `yyyy-MM-dd HH:mm:ss` | `2026-07-13 14:30:05` | UTC+8 时区，精确到秒 |
| 2 | 玩家名称 | 字符串 | `Steve` | 玩家当前的游戏内名称 |
| 3 | 位置 | `世界:X:Y:Z` | `world:120.50:64.00:-45.20` | X/Y/Z 保留两位小数 |
| 4 | 手持物品 | 物品 ID 或 `空` | `minecraft:diamond_sword` | 玩家主手物品的标准命名空间 ID |

> **编码说明**：日志文件使用 **UTF-8** 编码写入，确保中文 `空` 字及玩家名称在任何操作系统上都正确显示。解析时请使用 `encoding="utf-8"`。

### 完整示例

```text
2026-07-13 14:30:05 | Steve | world:120.50:64.00:-45.20 | minecraft:diamond_sword
2026-07-13 14:30:05 | Alex | world_nether:15.00:32.00:-100.50 | 空
2026-07-13 14:30:05 | Notch | world_the_end:0.00:60.00:0.00 | minecraft:ender_pearl
2026-07-13 14:31:05 | Steve | world:130.25:70.00:-50.80 | minecraft:stone_pickaxe
2026-07-13 14:31:05 | Alex | world_nether:18.50:32.00:-98.00 | minecraft:golden_sword
```

---

## 命令参考

所有命令对应权限节点 `movelog.admin`（默认仅 OP 可用）。

| 命令 | 说明 |
|------|------|
| `/movelog status` | 查看当前记录状态、TPS、在线玩家数、输出路径 |
| `/movelog on` | 开启记录（恢复定时采集） |
| `/movelog off` | 暂停记录（保留缓冲数据不丢失） |
| `/movelog reload` | 重新加载配置。采用「先验证后切换」策略：配置有误时中止操作并提示错误，旧记录器不受影响继续运行 |

---

## 配置参考

配置文件路径：`plugins/PlayerMoveLog/config.yml`

```yaml
# 是否启用记录（默认开启）
enabled: true

# 记录间隔（单位：游戏刻，1200 = 60秒 = 1分钟）
record-interval-ticks: 1200

# TPS 阈值 — 当服务器 1 分钟平均 TPS 低于此值时自动跳过本次记录
# 设为 0 可禁用 TPS 检测
tps-threshold: 18.0

# 日志写入缓冲区大小（字节）
buffer-size: 8192

# 大型服务器分批写入 — 玩家数超过此阈值时启用分批写入
batch-threshold: 100

# 每批写入的玩家记录数
batch-size: 50

# 时区（日志时间戳时区）
timezone: Asia/Shanghai

# 日期格式（用于日志文件名）
date-format: yyyy-MM-dd

# 时间格式（用于日志行内时间戳）
time-format: yyyy-MM-dd HH:mm:ss

# 输出目录名（位于服务器根目录下，与 world 同级）
output-dir: movelog
```

**配置热重载**：修改 `config.yml` 后执行 `/movelog reload` 即可生效，无需重启服务器。**所有配置项**（时区、日期格式、缓冲区大小、分批参数、输出目录等）均支持热重载。

**参数校验**：
- `record-interval-ticks` 必须 ≥ 1，否则自动重置为 1200
- `buffer-size`、`batch-size`、`batch-threshold` 必须 ≥ 1，否则 `/movelog reload` 会拒绝加载并提示错误
- `timezone` 必须为有效时区 ID（如 `Asia/Shanghai`），否则 reload 会拒绝加载
- `date-format` / `time-format` 必须为合法日期格式，否则 reload 会拒绝加载

---

## 示例解析代码

### Python 解析示例

```python
"""解析 PlayerMoveLog 生成的日志文件"""

from datetime import datetime
from pathlib import Path
from typing import NamedTuple


class MoveRecord(NamedTuple):
    """单条移动记录"""
    time: datetime
    player_name: str
    world: str
    x: float
    y: float
    z: float
    item: str  # 物品 ID，空手则为 "空"


def parse_log_line(line: str) -> MoveRecord | None:
    """
    解析一行日志，返回 MoveRecord 或 None（解析失败时）。
    
    日志格式：时间 | 玩家名 | 世界:X:Y:Z | 物品
    示例：  2026-07-13 14:30:05 | Steve | world:120.50:64.00:-45.20 | minecraft:diamond_sword
    """
    line = line.strip()
    if not line:
        return None
    
    parts = [p.strip() for p in line.split("|")]
    if len(parts) != 4:
        return None
    
    time_str, player_name, position_str, item = parts
    
    # 解析时间
    try:
        record_time = datetime.strptime(time_str, "%Y-%m-%d %H:%M:%S")
    except ValueError:
        return None
    
    # 解析位置：世界:X:Y:Z
    pos_parts = position_str.split(":")
    if len(pos_parts) != 4:
        return None
    
    world, x_str, y_str, z_str = pos_parts
    try:
        x, y, z = float(x_str), float(y_str), float(z_str)
    except ValueError:
        return None
    
    return MoveRecord(record_time, player_name, world, x, y, z, item)


def parse_log_file(file_path: str | Path) -> list[MoveRecord]:
    """解析整个日志文件，返回所有有效记录。"""
    records = []
    with open(file_path, "r", encoding="utf-8") as f:
        for line in f:
            record = parse_log_line(line)
            if record:
                records.append(record)
    return records


def calculate_player_travel(player_name: str, records: list[MoveRecord]) -> float:
    """
    计算某玩家在记录期间的总移动距离（欧几里得距离累加）。
    仅统计相同世界内的移动，跨世界跳跃不计入。
    """
    player_records = [r for r in records if r.player_name == player_name]
    total = 0.0
    for i in range(1, len(player_records)):
        prev, curr = player_records[i - 1], player_records[i]
        if prev.world == curr.world:
            dx = curr.x - prev.x
            dy = curr.y - prev.y
            dz = curr.z - prev.z
            total += (dx * dx + dy * dy + dz * dz) ** 0.5
    return total


# ── 使用示例 ──
if __name__ == "__main__":
    records = parse_log_file("movelog/2026-07-13.log")
    print(f"共解析 {len(records)} 条记录")
    
    # 统计各玩家活跃度
    from collections import Counter
    player_counts = Counter(r.player_name for r in records)
    for name, count in player_counts.most_common(10):
        distance = calculate_player_travel(name, records)
        print(f"  {name}: {count} 次记录, 累计移动 {distance:.1f} 米")
    
    # 统计各世界在线时长分布
    world_counts = Counter(r.world for r in records)
    print("\n各世界活跃度:")
    for world, count in world_counts.most_common():
        print(f"  {world}: {count} 次 ({count / len(records) * 100:.1f}%)")
```

### Java 解析示例

```java
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 一条玩家移动记录。
 */
public record MoveRecord(
    LocalDateTime time,
    String playerName,
    String world,
    double x,
    double y,
    double z,
    String item
) {

    private static final DateTimeFormatter FORMATTER =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 从日志行解析一条记录。
     *
     * @param line 一行日志，如 "2026-07-13 14:30:05 | Steve | world:120.50:64.00:-45.20 | minecraft:diamond_sword"
     * @return 解析结果，失败返回 empty
     */
    public static Optional<MoveRecord> parse(String line) {
        if (line == null || line.isBlank()) return Optional.empty();

        String[] parts = line.split("\\s*\\|\\s*");
        if (parts.length != 4) return Optional.empty();

        try {
            LocalDateTime time = LocalDateTime.parse(parts[0], FORMATTER);
            String playerName = parts[1];

            String[] posParts = parts[2].split(":");
            if (posParts.length != 4) return Optional.empty();

            String world = posParts[0];
            double x = Double.parseDouble(posParts[1]);
            double y = Double.parseDouble(posParts[2]);
            double z = Double.parseDouble(posParts[3]);
            String item = parts[3];

            return Optional.of(new MoveRecord(time, playerName, world, x, y, z, item));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * 解析整个日志文件（使用 UTF-8 编码）。
     */
    public static List<MoveRecord> parseFile(Path filePath) throws IOException {
        List<MoveRecord> records = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new FileReader(filePath.toFile(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                parse(line).ifPresent(records::add);
            }
        }
        return records;
    }
}
```

---

## 性能设计

### 零阻塞架构

```
主线程 (Server Thread)
  │
  ├─ 处理游戏逻辑 (tick, 实体, 方块, 玩家动作...)
  │
  └─ 不参与任何日志 I/O ──────────────────────────┐
                                                    │
后台线程 (PlayerMoveLog-Worker, MIN_PRIORITY)       │
  │                                                 │
  ├─ 1. 检查 TPS ───────── 低于阈值? → 跳过 ────────┤
  ├─ 2. 遍历在线玩家                                  │
  ├─ 3. 格式化日志行（纯内存操作）                      │
  ├─ 4. BufferedWriter 批量写入 (8 KiB buffer)        │
  └─ 5. 跨天自动滚动文件                               │
```

### 关键优化点

| 优化项 | 实现方式 |
|--------|---------|
| 异步调度 | `BukkitRunnable.runTaskTimerAsynchronously()` — Paper 异步调度器 |
| 防重入 | `AtomicBoolean.compareAndSet` — 原子 check-then-set，消除 TOCTOU 窗口 |
| 低优先级 | `Thread.MIN_PRIORITY` — 仅首次执行时设置，避免每轮系统调用 |
| 缓冲写入 | `BufferedWriter` (8 KiB) + UTF-8 — 合并多次写入为一次 I/O，跨平台不乱码 |
| 分批写入 | 玩家 > 100 时分 50 人/批写入 — 避免大数据量单次 I/O |
| 零分配坐标 | `Entity.getX/Y/Z()` 替代 `getLocation()` — 消除每玩家一个 Location 对象 |
| 轻量格式化 | `StringBuilder` + `DecimalFormat` 替代 `String.format` — 消除每玩家一个 Formatter 对象 |
| TPS 保护 | 低于阈值时跳过；NaN/Infinity 自动拦截 |
| 异常保护 | 全部 catch + log + 损坏 writer 自动清理重建；flush/close 独立 try-catch 防 fd 泄漏 |
| 线程命名 | `PlayerMoveLog-Worker` — 方便 profiler 定位，仅首次设置 |
| 按时段滚动 | 自动关闭过期时段文件句柄，防止 fd 泄漏 |
| 安全关闭 | shutdown 标志（volatile）+ writerLock + 独立 flush→close |
| 路径穿越防护 | blockKey 中检测 `..` `/` `\`，命中自动回退到安全键名 |

---

## 性能测试建议

### 测试前准备

1. **建立基线**：在安装插件前，让服务器正常运行 30 分钟，使用 Spark 或 Timings 记录 TPS 均值。
2. **安装插件**：按快速安装步骤部署。
3. **对比测试**：让插件运行 30 分钟以上，再次记录 TPS。
4. **查看报告**：对比安装前后 TPS 数据。

### 测试指标

```text
测试环境：
  服务器核心: Paper 1.21.11
  在线玩家:   <填实际数字>
  物理内存:   <填实际数字>
  CPU:        <填实际数字>

安装前 (30 分钟平均):
  MSPT:  <填安装前的 ms/tick>
  TPS:   <填安装前的 TPS>

安装后 (30 分钟平均):
  MSPT:  <填安装后的 ms/tick>
  TPS:   <填安装后的 TPS>

差异:
  MSPT 变化: <差值> ms
  TPS 变化:  <差值>
  CPU 增量:  <可忽略 / N%>

磁盘 I/O：
  每小时产生数据量估计：
    在线玩家数 × 60 条/小时 × 约 80 字节/条 = 约 ___ KB/小时
  
  例如 20 人在线：20 × 60 × 80 ≈ 96 KB/小时，几乎无影响。
```

### 推荐测试工具

- **[Spark](https://spark.lucko.me/)** — `/spark profiler` 查看 CPU 火焰图，确认 `PlayerMoveLog-Worker` 线程占用可忽略。
- **[Timings](https://timings.spigotmc.org/)** — `/timings report` 生成完整的服务器性能报告。
- **系统自带** — Windows 任务管理器 / Linux `htop` 观察磁盘 I/O。

### 压力测试

```bash
# 模拟大量玩家在线（使用脚本或假人插件）
# 观察 movelog 目录下的文件增长和服务器 TPS 变化
# 重点验证：
#   1. >100 玩家时，分批写入是否正常
#   2. TPS 低于 18 时，是否自动跳过
#   3. 跨天时，文件是否正确滚动
```

---

## 常见问题

### Q: 日志文件会无限增长吗？

A: 插件按时段分文件存储（默认每 4 小时一个文件），每个文件大小约为 `在线玩家数 × 60 × 4 × 约80字节`。例如 20 人在线每 4 小时约产生 384 KB，一天共 6 个文件约 2.3 MB。建议配合服务器日志清理脚本（如 logrotate）定期归档或删除旧文件。

### Q: 如何改为按天分文件？

A: 将 `config.yml` 中的 `rotation-hours` 设为 `24`，然后 `/movelog reload`。设为 `4`（默认）则每 4 小时一个文件。

### Q: 可以修改记录间隔吗？

A: 可以。在 `config.yml` 中修改 `record-interval-ticks`（1 秒 = 20 ticks），然后 `/movelog reload` 即可。

### Q: 如何完全禁用 TPS 保护？

A: 在 `config.yml` 中将 `tps-threshold` 设为 `0`。

### Q: 插件兼容 Spigot / CraftBukkit 吗？

A: 插件使用了 Paper API（`getTPS()` 等），仅保证在 Paper 1.21.x 及其兼容 Fork（Purpur、Pufferfish 等）上正常运行。

### Q: 如何验证插件正在工作？

A: 执行 `/movelog status` 查看状态，或检查 `movelog/` 目录下是否有当天的 `.log` 文件生成。

### Q: `/movelog reload` 失败了怎么办？

A: 插件采用「先验证后切换」策略。如果配置文件有误（如非法时区、日期格式），reload 会中止操作并显示具体错误原因，**旧记录器继续运行不受影响**。修复配置后再次 reload 即可。

### Q: 日志文件使用什么编码？

A: **UTF-8**。无论服务器操作系统是什么，日志文件始终以 UTF-8 编码写入，确保中文字符 `空` 在任何平台上正确显示。解析时请指定 `encoding="utf-8"`。

---

## 参与贡献

请先阅读 [CONTRIBUTING.md](CONTRIBUTING.md)。简单说：

1. **Fork** 这个仓库
2. 在你 Fork 中**创建功能分支**（不要在 master 上改！）
3. 开发和测试后，从**功能分支**发起 PR

> PR 来自 Fork 的 master/main 分支？不会被接受。请开功能分支。

---

## 技术栈

---

## 更新日志

### v1.0.0（2026-07-14）

**新增**
- 按时段切分日志文件（`rotation-hours`，默认 4 小时），支持 1/2/3/4/6/8/12/24 小时
- NPC/假玩家自动过滤（`getAddress() == null`）
- TPS 阈值 NaN/Infinity 校验
- 路径穿越自动检测与回退
- 线程属性仅首次设置（`threadConfigured` 标志）

**修复**
- 热重载时输出目录变更不生效（validate-then-switch 完善）
- `cleanupStaleWriters` / `shutdown` 中 flush 失败跳过 close 导致 fd 泄漏
- 全 NPC 在线时创建空日志文件
- 写入测试文件删除失败误禁用插件

**优化**
- `Entity.getX/Y/Z()` 替代 `getLocation()` —— 消除每玩家一个 Location 对象分配
- `StringBuilder` + `DecimalFormat` 替代 `String.format` —— 消除每玩家一个 Formatter 对象分配
- 所有 `String.format` 统一使用 `Locale.US`
- `/movelog status` 使用 1 分钟 TPS 而非三项均值

---

<p align="center">
  Made with ❤️ for the Minecraft community
</p>
