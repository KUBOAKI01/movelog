package com.proma.movelog;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;

/**
 * 异步玩家移动记录器。
 * <p>
 * 通过 {@link BukkitRunnable#runTaskTimerAsynchronously} 在后台线程中定时执行，
 * 完成 TPS 检测、数据采集、格式化与缓冲文件写入。所有 I/O 操作均不触及主线程。
 * </p>
 *
 * <h3>性能设计</h3>
 * <ul>
 *   <li>线程优先级设为 {@code Thread.MIN_PRIORITY}，由操作系统调度到低负载核心。</li>
 *   <li>使用 {@link BufferedWriter}（8 KiB 缓冲区）合并批量写入。</li>
 *   <li>玩家数超过阈值时启用分批写入，避免单次 I/O 过大。</li>
 *   <li>TPS 低于阈值自动跳过本周期，保护服务器主循环。</li>
 *   <li>所有异常均在插件日志中静默记录，绝不抛出到主线程。</li>
 * </ul>
 */
public class MoveRecorder extends BukkitRunnable {

    // ─── 依赖与配置 ─────────────────────────────────────────────

    private final Plugin plugin;
    private final File moveLogDir;
    private volatile boolean enabled;
    private volatile double tpsThreshold;
    private final int bufferSize;
    private final int batchThreshold;
    private final int batchSize;
    private final int rotationHours;
    private final ZoneId zoneId;
    private final DateTimeFormatter dateFormatter;
    private final DateTimeFormatter timeFormatter;

    /** 坐标格式化器（DecimalFormat 非线程安全，但 AtomicBoolean 保证单线程调用） */
    private final DecimalFormat coordFormat =
            new DecimalFormat("0.00", DecimalFormatSymbols.getInstance(Locale.US));

    // ─── 运行时状态 ─────────────────────────────────────────────

    /** 按时间块映射的缓冲写入器，使用 HashMap + ReentrantLock 保证线程安全 */
    private final Map<String, BufferedWriter> writerMap = new HashMap<>();

    /** 用于 writerMap 的并发保护 */
    private final ReentrantLock writerLock = new ReentrantLock();

    /** 是否正在执行中（防重入，AtomicBoolean 提供原子 check-then-set） */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /** 是否已关闭（防止 shutdown 后旧 recorder 再创建新文件句柄） */
    private volatile boolean shutdown = false;

    /** 线程属性是否已初始化（仅首次 run 时设置） */
    private volatile boolean threadConfigured = false;

    // ─── 构造 ───────────────────────────────────────────────────

    public MoveRecorder(Plugin plugin, File moveLogDir, double tpsThreshold,
                        int bufferSize, int batchThreshold, int batchSize,
                        int rotationHours, String timezone, String dateFormat, String timeFormat,
                        boolean enabled) {
        // 参数校验：防止无效配置导致无限循环或运行时异常
        if (bufferSize < 1) {
            throw new IllegalArgumentException("buffer-size 必须 >= 1，当前值: " + bufferSize);
        }
        if (batchSize < 1) {
            throw new IllegalArgumentException("batch-size 必须 >= 1，当前值: " + batchSize);
        }
        if (batchThreshold < 1) {
            throw new IllegalArgumentException("batch-threshold 必须 >= 1，当前值: " + batchThreshold);
        }
        if (rotationHours < 1 || rotationHours > 24 || 24 % rotationHours != 0) {
            throw new IllegalArgumentException("rotation-hours 必须能被 24 整除且在 1-24 之间，当前值: " + rotationHours);
        }
        if (!Double.isFinite(tpsThreshold)) {
            throw new IllegalArgumentException("tps-threshold 必须是有限数值，当前值: " + tpsThreshold);
        }

        this.plugin = plugin;
        this.moveLogDir = moveLogDir;
        this.tpsThreshold = tpsThreshold;
        this.bufferSize = bufferSize;
        this.batchThreshold = batchThreshold;
        this.batchSize = batchSize;
        this.rotationHours = rotationHours;
        this.enabled = enabled;

        this.zoneId = ZoneId.of(timezone);
        this.dateFormatter = DateTimeFormatter.ofPattern(dateFormat).withZone(zoneId);
        this.timeFormatter = DateTimeFormatter.ofPattern(timeFormat).withZone(zoneId);
    }

    // ─── 主循环 ─────────────────────────────────────────────────

    @Override
    public void run() {
        // ── 已关闭则不执行 ──
        if (shutdown) return;
        if (!enabled) return;

        // ── 防重入保护：原子 check-then-set，消除 TOCTOU 窗口 ──
        if (!running.compareAndSet(false, true)) {
            plugin.getLogger().warning("上一轮记录尚未完成，跳过本轮。");
            return;
        }

        try {
            // ── 低优先级让出主循环资源（仅首次执行时设置）──
            if (!threadConfigured) {
                try {
                    Thread currentThread = Thread.currentThread();
                    currentThread.setPriority(Thread.MIN_PRIORITY);
                    currentThread.setName("PlayerMoveLog-Worker");
                    threadConfigured = true;
                } catch (SecurityException ignored) {
                    // 极少见：SecurityManager 禁止修改线程属性，不影响核心功能
                }
            }

            // ── TPS 检测 ──
            double tps = Bukkit.getServer().getTPS()[0];
            if (tps > 0 && tps < tpsThreshold) {
                plugin.getLogger().warning(String.format(
                        "TPS（%.1f）低于阈值（%.1f），跳过本轮记录。", tps, tpsThreshold));
                return;
            }

            // ── 玩家列表 ──
            Collection<? extends Player> players;
            try {
                players = Bukkit.getOnlinePlayers();
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "获取在线玩家列表失败: " + e.getMessage(), e);
                return;
            }

            if (players.isEmpty()) return;

            // ── 时间戳 ──
            ZonedDateTime now = ZonedDateTime.now(zoneId);
            String timeStr = timeFormatter.format(now);
            String blockKey = computeBlockKey(now);

            // ── 格式化日志行 ──
            List<String> lines = new ArrayList<>(players.size());
            for (Player player : players) {
                try {
                    // 跳过 NPC / 假玩家：真玩家一定有网络地址，NPC 的 getAddress() 返回 null
                    if (player.getAddress() == null) {
                        continue;
                    }
                    lines.add(formatLogLine(player, timeStr));
                } catch (Exception e) {
                    plugin.getLogger().log(Level.WARNING,
                            "格式化玩家 " + player.getName() + " 数据失败: " + e.getMessage());
                    // 继续处理下一个玩家
                }
            }

            // ── 分批写入 ──
            if (lines.isEmpty()) return;

            if (lines.size() > batchThreshold) {
                for (int i = 0; i < lines.size(); i += batchSize) {
                    int end = Math.min(i + batchSize, lines.size());
                    appendToFile(blockKey, lines.subList(i, end));
                }
            } else {
                appendToFile(blockKey, lines);
            }

            // ── 滚动清理：关闭已过期时间块的旧文件句柄 ──
            cleanupStaleWriters(blockKey);

        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING,
                    "[PlayerMoveLog] 记录过程中发生异常: " + e.getMessage(), e);
        } finally {
            running.set(false);
        }
    }

    // ─── 日志行格式化 ───────────────────────────────────────────

    /**
     * 按固定格式构造一条日志行：
     * {@code 时间 | 玩家名 | 世界名:X:Y:Z | 主手物品}
     * <p>
     * 使用 Entity.getX/Y/Z() 替代 getLocation() 避免每次分配 Location 对象；
     * 使用 StringBuilder + DecimalFormat 替代 String.format 避免每次分配 Formatter。
     * </p>
     */
    private String formatLogLine(Player player, String timeStr) {
        ItemStack mainHand = player.getInventory().getItemInMainHand();
        String itemStr;
        if (mainHand == null || mainHand.getType().isAir()) {
            itemStr = "空";
        } else {
            itemStr = mainHand.getType().getKey().toString();
        }

        StringBuilder sb = new StringBuilder(128);
        sb.append(timeStr).append(" | ").append(player.getName()).append(" | ")
          .append(player.getWorld().getName()).append(':')
          .append(coordFormat.format(player.getX())).append(':')
          .append(coordFormat.format(player.getY())).append(':')
          .append(coordFormat.format(player.getZ())).append(" | ")
          .append(itemStr);
        return sb.toString();
    }

    // ─── 文件写入 ───────────────────────────────────────────────

    /**
     * 计算当前时间所属的文件名块键。
     * <p>
     * 例如 rotationHours=4 时，hour 0-3 → "00"，4-7 → "04"，以此类推。
     * 文件名 = {@code dateStr + "-" + blockHour + ".log"}。
     * </p>
     */
    private String computeBlockKey(ZonedDateTime now) {
        String dateStr = dateFormatter.format(now);
        int blockHour = (now.getHour() / rotationHours) * rotationHours;
        String key = dateStr + "-" + String.format(Locale.US, "%02d", blockHour);
        // 防止恶意 date-format 配置导致路径穿越
        if (key.contains("..") || key.contains("/") || key.contains("\\")) {
            plugin.getLogger().severe("日志文件名包含非法字符（路径穿越风险）: " + key);
            // 回退到安全的默认键名（纯日期 + 小时块）
            String safeDate = java.time.LocalDate.now(zoneId).toString();
            return safeDate + "-" + String.format(Locale.US, "%02d", blockHour);
        }
        return key;
    }

    /**
     * 将一批日志行追加写入指定时间块的文件中。
     * <p>
     * 使用 {@link BufferedWriter} 做缓冲写入；若文件无法创建则静默记录错误。
     * </p>
     */
    private void appendToFile(String blockKey, List<String> lines) {
        BufferedWriter writer = null;
        try {
            writer = getOrCreateWriter(blockKey);
            if (writer == null) return;

            for (String line : lines) {
                writer.write(line);
                writer.newLine();
            }
            writer.flush();
        } catch (IOException e) {
            // 写入失败时清除损坏的 writer，下次调用会重新创建
            removeWriter(blockKey);
            plugin.getLogger().log(Level.WARNING,
                    "写入日志文件失败 (块=" + blockKey + ")，已清除损坏的写入器: " + e.getMessage(), e);
        }
    }

    /**
     * 安全移除并关闭指定时间块的 BufferedWriter（通常用于损坏的 writer 清理）。
     */
    private void removeWriter(String blockKey) {
        writerLock.lock();
        try {
            BufferedWriter w = writerMap.remove(blockKey);
            if (w != null) {
                try {
                    w.close();
                } catch (IOException ignored) {
                    // 损坏的 writer 关闭时再次抛异常是预期行为
                }
            }
        } finally {
            writerLock.unlock();
        }
    }

    /**
     * 获取或创建指定时间块的 BufferedWriter。
     */
    private BufferedWriter getOrCreateWriter(String blockKey) throws IOException {
        writerLock.lock();
        try {
            // shutdown 后拒绝创建新 writer（防止并发窗口中的句柄泄漏）
            if (shutdown) return null;

            BufferedWriter writer = writerMap.get(blockKey);
            if (writer != null) {
                return writer;
            }
            File logFile = new File(moveLogDir, blockKey + ".log");
            writer = new BufferedWriter(new FileWriter(logFile, StandardCharsets.UTF_8, true), bufferSize);
            writerMap.put(blockKey, writer);
            plugin.getLogger().info("创建日志文件: " + logFile.getName());
            return writer;
        } finally {
            writerLock.unlock();
        }
    }

    /**
     * 关闭非当前时间块的文件写入器，释放文件句柄。
     */
    private void cleanupStaleWriters(String currentBlockKey) {
        writerLock.lock();
        try {
            List<String> stale = new ArrayList<>();
            for (String key : writerMap.keySet()) {
                if (!key.equals(currentBlockKey)) {
                    stale.add(key);
                }
            }
            for (String key : stale) {
                BufferedWriter w = writerMap.remove(key);
                if (w != null) {
                    try {
                        w.flush();
                    } catch (IOException e) {
                        plugin.getLogger().log(Level.WARNING,
                                "刷新写入器失败 (" + key + ".log): " + e.getMessage());
                    }
                    try {
                        w.close();
                        plugin.getLogger().info("已关闭过期日志文件写入器: " + key + ".log");
                    } catch (IOException e) {
                        plugin.getLogger().log(Level.WARNING,
                                "关闭写入器失败 (" + key + ".log): " + e.getMessage());
                    }
                }
            }
        } finally {
            writerLock.unlock();
        }
    }

    // ─── 生命周期管理 ───────────────────────────────────────────

    /**
     * 关闭插件时调用：强制刷新并关闭所有文件写入器，防止数据丢失。
     */
    public void shutdown() {
        // 设置关闭标志（在获取锁之前），防止并发 run() 在 writerMap 被清空后
        // 重新创建 writer 导致句柄泄漏
        this.shutdown = true;

        writerLock.lock();
        try {
            for (Map.Entry<String, BufferedWriter> entry : writerMap.entrySet()) {
                try {
                    entry.getValue().flush();
                } catch (IOException e) {
                    plugin.getLogger().log(Level.WARNING,
                            "刷新日志文件失败 (" + entry.getKey() + ".log): " + e.getMessage());
                }
                try {
                    entry.getValue().close();
                    plugin.getLogger().info("已安全关闭日志文件: " + entry.getKey() + ".log");
                } catch (IOException e) {
                    plugin.getLogger().log(Level.WARNING,
                            "关闭日志文件失败 (" + entry.getKey() + ".log): " + e.getMessage());
                }
            }
            writerMap.clear();
        } finally {
            writerLock.unlock();
        }
    }

    // ─── 属性访问 ───────────────────────────────────────────────

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public double getTpsThreshold() {
        return tpsThreshold;
    }

    public void setTpsThreshold(double tpsThreshold) {
        this.tpsThreshold = tpsThreshold;
    }
}
