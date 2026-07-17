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
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
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
 *   <li>使用 {@link BufferedWriter}（可配置缓冲区大小）合并批量写入。</li>
 *   <li>玩家数超过阈值时启用分批写入，避免单次 I/O 过大。</li>
 *   <li>TPS 低于阈值自动跳过本周期，保护服务器主循环。</li>
 *   <li>故障计数器 + 退避策略防止磁盘满时 I/O 风暴。</li>
 *   <li>事件队列（ConcurrentLinkedQueue）支持主线程安全推送事件日志行。</li>
 * </ul>
 *
 * <h3>线程安全</h3>
 * <ul>
 *   <li>{@link AtomicBoolean} running 保证同时只有一个周期在执行。</li>
 *   <li>{@link ReentrantLock} writerLock 保护 writerMap 的所有访问。</li>
 *   <li>{@code volatile} shutdown / enabled / tpsThreshold 保证跨线程可见性。</li>
 *   <li>{@link ConcurrentLinkedQueue} 事件队列无锁线程安全。</li>
 * </ul>
 */
public class MoveRecorder extends BukkitRunnable {

    // ─── 预计算常量 ─────────────────────────────────────────────

    /** 00..23 零填充字符串，替代 String.format("%02d", hour) 消除 Formatter 分配 */
    private static final String[] PADDED_HOURS = {
        "00", "01", "02", "03", "04", "05", "06", "07",
        "08", "09", "10", "11", "12", "13", "14", "15",
        "16", "17", "18", "19", "20", "21", "22", "23"
    };

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
    private final int logRetentionDays;
    private final boolean excelBom;
    private final String emptyItemText;
    private final String exemptPermission;
    private final Set<String> worldWhitelist;
    private final Set<String> worldBlacklist;
    private final Set<String> excludedPlayers;
    private final Set<String> includedPlayers;
    private final int maxConsecutiveFailures;
    private final boolean eventLogEnabled;
    private final boolean logInventory;
    private final File chatLogDir;     // null = chat logging disabled
    private final int chatRotationHours;

    /** 坐标格式化器（DecimalFormat 非线程安全，由 AtomicBoolean running 保证单线程调用） */
    private final DecimalFormat coordFormat =
            new DecimalFormat("0.00", DecimalFormatSymbols.getInstance(Locale.US));

    // ─── 运行时状态 ─────────────────────────────────────────────

    /** 按时间块映射的缓冲写入器 */
    private final Map<String, BufferedWriter> writerMap = new HashMap<>();

    /** writerMap 的并发保护 */
    private final ReentrantLock writerLock = new ReentrantLock();

    /** 是否正在执行中（防重入） */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /** 是否已关闭 */
    private volatile boolean shutdown = false;

    /** 线程属性是否已初始化 */
    private volatile boolean threadConfigured = false;

    /** 最近一次成功记录的时间戳（毫秒），用于健康检查 */
    private final AtomicLong lastSuccessTime = new AtomicLong(0);

    /** 连续 I/O 失败次数（按 blockKey 记录），用于退避策略 */
    private final Map<String, Integer> writeFailCounts = new HashMap<>();

    /** 主线程推送的事件日志行队列（无锁线程安全） */
    private final ConcurrentLinkedQueue<String> eventQueue = new ConcurrentLinkedQueue<>();

    /** 聊天消息队列 */
    private final ConcurrentLinkedQueue<String> chatQueue = new ConcurrentLinkedQueue<>();

    /** 聊天日志的缓冲写入器 */
    private final Map<String, BufferedWriter> chatWriterMap = new HashMap<>();
    private final ReentrantLock chatWriterLock = new ReentrantLock();

    /** 累计记录条数 */
    private final AtomicLong totalRecords = new AtomicLong(0);

    /** 累计跳过周期数（TPS 过低） */
    private final AtomicLong totalTpsSkips = new AtomicLong(0);

    /** BOM 是否已写入（每个新文件只写一次） */
    private final Set<String> bomWrittenFiles = new HashSet<>();

    // ─── 构造 ───────────────────────────────────────────────────

    public MoveRecorder(Plugin plugin, File moveLogDir, double tpsThreshold,
                        int bufferSize, int batchThreshold, int batchSize,
                        int rotationHours, String timezone, String dateFormat, String timeFormat,
                        boolean enabled, int logRetentionDays, boolean excelBom,
                        String emptyItemText, String exemptPermission,
                        List<String> worldWhitelistRaw, List<String> worldBlacklistRaw,
                        List<String> excludedPlayersRaw, List<String> includedPlayersRaw,
                        int maxConsecutiveFailures, boolean eventLogEnabled, boolean logInventory,
                        File chatLogDir, int chatRotationHours) {
        // ── 参数校验 ──
        if (bufferSize < 1 || bufferSize > 1048576) {
            throw new IllegalArgumentException("buffer-size 必须在 1-1048576（1MB）之间，当前值: " + bufferSize);
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
        // 输出目录路径穿越防护
        String dirPath = moveLogDir.getAbsolutePath();
        if (dirPath.contains("..") || moveLogDir.getPath().contains("..")) {
            throw new IllegalArgumentException("output-dir 包含非法路径字符 (..): " + moveLogDir.getPath());
        }

        this.plugin = plugin;
        this.moveLogDir = moveLogDir;
        this.tpsThreshold = tpsThreshold;
        this.bufferSize = bufferSize;
        this.batchThreshold = batchThreshold;
        this.batchSize = batchSize;
        this.rotationHours = rotationHours;
        this.enabled = enabled;
        this.logRetentionDays = logRetentionDays;
        this.excelBom = excelBom;
        this.emptyItemText = emptyItemText;
        this.exemptPermission = (exemptPermission != null && !exemptPermission.isEmpty()) ? exemptPermission : null;
        this.worldWhitelist = (worldWhitelistRaw != null) ? new HashSet<>(worldWhitelistRaw) : Collections.emptySet();
        this.worldBlacklist = (worldBlacklistRaw != null) ? new HashSet<>(worldBlacklistRaw) : Collections.emptySet();
        this.excludedPlayers = (excludedPlayersRaw != null) ? new HashSet<>(excludedPlayersRaw) : Collections.emptySet();
        this.includedPlayers = (includedPlayersRaw != null) ? new HashSet<>(includedPlayersRaw) : Collections.emptySet();
        this.maxConsecutiveFailures = Math.max(1, maxConsecutiveFailures);
        this.eventLogEnabled = eventLogEnabled;
        this.logInventory = logInventory;
        this.chatLogDir = chatLogDir;
        // 校验 chatRotationHours（防御性，正常路径已由 buildRecorderFromConfig 保证）
        if (chatRotationHours < 1 || chatRotationHours > 24 || 24 % chatRotationHours != 0) {
            throw new IllegalArgumentException("chat-rotation-hours 必须能被 24 整除且在 1-24 之间，当前值: " + chatRotationHours);
        }
        this.chatRotationHours = chatRotationHours;

        this.zoneId = ZoneId.of(timezone);
        this.dateFormatter = DateTimeFormatter.ofPattern(dateFormat).withZone(zoneId);
        this.timeFormatter = DateTimeFormatter.ofPattern(timeFormat).withZone(zoneId);
    }

    // ─── 主循环 ─────────────────────────────────────────────────

    @Override
    public void run() {
        if (shutdown) return;
        if (!enabled) return;

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
                    // SecurityManager 禁止修改线程属性，不影响核心功能
                }
            }

            // ── TPS 检测 ──
            double tps = Bukkit.getServer().getTPS()[0];
            if (tps >= 0 && tps < tpsThreshold) {
                totalTpsSkips.incrementAndGet();
                plugin.getLogger().warning(String.format(
                        Locale.US, "TPS（%.1f）低于阈值（%.1f），跳过本轮记录。", tps, tpsThreshold));
                return;
            }

            // ── 获取在线玩家 ──
            Collection<? extends Player> players;
            try {
                players = Bukkit.getOnlinePlayers();
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING,
                        "获取在线玩家列表失败: " + e.getMessage(), e);
                return;
            }

            // ── 时间戳 ──
            ZonedDateTime now = ZonedDateTime.now(zoneId);
            String timeStr = timeFormatter.format(now);
            String blockKey = computeBlockKey(now);

            // ── 每日日志清理（每天首次进入新 block 时触发一次）──
            cleanOldLogsIfDue(now);

            // ── 排出事件队列 ──
            List<String> lines = new ArrayList<>();
            String eventLine;
            while ((eventLine = eventQueue.poll()) != null) {
                lines.add(eventLine);
            }

            // ── 排出聊天队列 ──
            List<String> chatLines = null;
            if (chatLogDir != null) {
                chatLines = new ArrayList<>();
                String chatLine;
                while ((chatLine = chatQueue.poll()) != null) {
                    chatLines.add(chatLine);
                }
            }

            // ── 格式化玩家数据 ──
            if (!players.isEmpty()) {
                for (Player player : players) {
                    try {
                        if (player.getAddress() == null) continue; // 跳过 NPC

                        // 过滤系统
                        if (!shouldRecord(player)) continue;

                        lines.add(formatLogLine(player, timeStr));
                    } catch (Exception e) {
                        // 单个玩家格式化失败不应影响整体，使用 FINE 级别避免告警疲劳
                        plugin.getLogger().log(Level.FINE,
                                "格式化玩家 " + player.getName() + " 数据失败: " + e.getMessage());
                    }
                }
            }

            // 注意：即使 lines 为空，仍可能有聊天消息需写入
            boolean hasChat = (chatLines != null && !chatLines.isEmpty());

            if (lines.isEmpty() && !hasChat) return;

            // ── 分批写入 ──
            int written = 0;
            if (lines.size() > batchThreshold) {
                for (int i = 0; i < lines.size(); i += batchSize) {
                    int end = Math.min(i + batchSize, lines.size());
                    written += appendToFile(blockKey, lines.subList(i, end));
                }
            } else {
                written = appendToFile(blockKey, lines);
            }

            if (written > 0) {
                totalRecords.addAndGet(written);
                lastSuccessTime.set(System.currentTimeMillis());
            }

            // ── 写入聊天日志 ──
            if (hasChat) {
                String chatBlockKey = computeChatBlockKey(now);
                for (String cl : chatLines) {
                    try {
                        appendToChatFile(chatBlockKey, cl);
                    } catch (Exception e) {
                        plugin.getLogger().log(Level.FINE,
                                "写入聊天日志失败: " + e.getMessage());
                    }
                }
                cleanupStaleChatWriters(chatBlockKey);
            }

            // ── 滚动清理：关闭已过期时间块的旧文件句柄 ──
            cleanupStaleWriters(blockKey);

        } catch (Throwable t) {
            // 捕获 Error 和 Exception，防止杀死异步线程
            // ThreadDeath 在 Java 21+ 已废弃且不再触发，无需特殊处理
            plugin.getLogger().log(Level.SEVERE,
                    "[PlayerMoveLog] 记录过程中发生不可恢复错误: " + t.getMessage(), t);
        } finally {
            running.set(false);
        }
    }

    // ─── 过滤系统 ───────────────────────────────────────────────

    /**
     * 判断是否应该记录指定玩家（公开方法，供事件监听器调用）。
     */
    public boolean shouldRecord(Player player) {
        // 权限豁免
        if (exemptPermission != null && player.hasPermission(exemptPermission)) {
            return false;
        }
        // 包含列表（非空时只记录列表中的玩家）
        if (!includedPlayers.isEmpty() && !includedPlayers.contains(player.getName())) {
            return false;
        }
        // 排除列表
        if (excludedPlayers.contains(player.getName())) {
            return false;
        }
        String worldName = player.getWorld().getName();
        // 世界白名单（非空时只记录白名单中的世界）
        if (!worldWhitelist.isEmpty() && !worldWhitelist.contains(worldName)) {
            return false;
        }
        // 世界黑名单
        if (worldBlacklist.contains(worldName)) {
            return false;
        }
        return true;
    }

    // ─── 事件记录（主线程安全）───────────────────────────────────

    /**
     * 从主线程安全地推送一条事件日志行。
     * <p>
     * 调用者负责格式化字符串，此方法仅将格式化后的行入队。
     * 实际文件 I/O 在下一次 {@link #run()} 周期中完成。
     * </p>
     *
     * @param formattedLine 已格式化的完整日志行（含事件类型标记）
     */
    public void recordEvent(String formattedLine) {
        if (!eventLogEnabled) return;
        if (!enabled) return;
        if (shutdown) return;
        eventQueue.add(formattedLine);
    }

    /**
     * 从主线程安全推送聊天日志行。
     */
    public void recordChat(String formattedLine) {
        if (chatLogDir == null) return;
        if (!enabled) return;
        if (shutdown) return;
        chatQueue.add(formattedLine);
    }

    // ─── 日志行格式化 ───────────────────────────────────────────

    /**
     * 按固定格式构造一条日志行：
     * {@code 时间 | 玩家名 | 世界:X:Y:Z | 主手物品}
     * <p>
     * 使用 Entity.getX/Y/Z() 替代 getLocation() 避免每次分配 Location 对象；
     * 使用 StringBuilder + DecimalFormat 替代 String.format 避免每次分配 Formatter。
     * </p>
     */
    private String formatLogLine(Player player, String timeStr) {
        ItemStack mainHand = player.getInventory().getItemInMainHand();
        String itemStr;
        if (mainHand == null || mainHand.getType().isAir()) {
            itemStr = emptyItemText;
        } else {
            itemStr = mainHand.getType().getKey().toString();
        }

        StringBuilder sb = new StringBuilder(logInventory ? 1024 : 256);
        sb.append(timeStr).append(" | ")
          .append(safeName(player.getName())).append(" | ")
          .append(player.getWorld().getName()).append(':')
          .append(fmtCoord(player.getX())).append(':')
          .append(fmtCoord(player.getY())).append(':')
          .append(fmtCoord(player.getZ())).append(" | ")
          .append(itemStr);

        // 背包记录：统计所有非空槽位，按物品类型合并计数
        if (logInventory) {
            sb.append(" | INV ");
            appendInventorySummary(sb, player);
        }

        return sb.toString();
    }

    /**
     * 统计玩家背包中所有非空物品并按类型合并计数，追加到 StringBuilder。
     * 覆盖：36 主背包 + 副手 + 4 护甲，共 41 个槽位。
     * 格式：{@code stone:5, dirt:3, iron_sword:1}
     */
    private void appendInventorySummary(StringBuilder sb, Player player) {
        Map<String, Integer> counts = new LinkedHashMap<>();

        // 扫描 36 个主背包槽位（不含护甲/副手，单独处理）
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (item != null && !item.getType().isAir()) {
                counts.merge(item.getType().getKey().toString(), item.getAmount(), Integer::sum);
            }
        }
        // 副手
        ItemStack offHand = player.getInventory().getItemInOffHand();
        if (offHand != null && !offHand.getType().isAir()) {
            counts.merge(offHand.getType().getKey().toString(), offHand.getAmount(), Integer::sum);
        }
        // 护甲
        for (ItemStack armor : player.getInventory().getArmorContents()) {
            if (armor != null && !armor.getType().isAir()) {
                counts.merge(armor.getType().getKey().toString(), armor.getAmount(), Integer::sum);
            }
        }

        if (counts.isEmpty()) {
            sb.append('-');
            return;
        }

        boolean first = true;
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (!first) sb.append(',');
            sb.append(entry.getKey());
            if (entry.getValue() > 1) {
                sb.append(':').append(entry.getValue());
            }
            first = false;
        }
    }

    // ─── 文件写入 ───────────────────────────────────────────────

    /**
     * 计算当前时间所属的文件名块键。
     * <p>
     * 使用预计算 PADDED_HOURS 数组替代 String.format，零分配。
     * </p>
     */
    private String computeBlockKey(ZonedDateTime now) {
        String dateStr = dateFormatter.format(now);
        int blockHour = (now.getHour() / rotationHours) * rotationHours;
        String key = dateStr + "-" + PADDED_HOURS[blockHour];
        // 防止恶意 date-format 配置导致路径穿越
        if (key.contains("..") || key.contains("/") || key.contains("\\")) {
            plugin.getLogger().severe("日志文件名包含非法字符（路径穿越风险）: " + key);
            String safeDate = LocalDate.now(zoneId).toString();
            return safeDate + "-" + PADDED_HOURS[blockHour];
        }
        return key;
    }

    /**
     * 将一批日志行追加写入指定时间块的文件中。
     *
     * @return 成功写入的行数
     */
    private int appendToFile(String blockKey, List<String> lines) {
        // 退避检查：连续失败超过阈值时拒绝写入
        synchronized (writeFailCounts) {
            Integer count = writeFailCounts.get(blockKey);
            if (count != null && count >= maxConsecutiveFailures) {
                // 不重复记录日志（由 getOrCreateWriter 首次失败时输出 SEVERE）
                return 0;
            }
        }

        BufferedWriter writer = null;
        try {
            writer = getOrCreateWriter(blockKey);
            if (writer == null) return 0;

            for (String line : lines) {
                writer.write(line);
                writer.newLine();
            }
            writer.flush();

            // 重置该 block 的失败计数器
            synchronized (writeFailCounts) {
                writeFailCounts.remove(blockKey);
            }
            return lines.size();

        } catch (IOException e) {
            // 记录失败次数
            int failCount;
            synchronized (writeFailCounts) {
                failCount = writeFailCounts.merge(blockKey, 1, Integer::sum);
            }
            if (failCount == 1) {
                plugin.getLogger().log(Level.WARNING,
                        "写入日志文件失败 (块=" + blockKey + ")，第 1 次: " + e.getMessage(), e);
            } else if (failCount == maxConsecutiveFailures) {
                plugin.getLogger().log(Level.SEVERE,
                        "写入日志文件连续失败 " + failCount + " 次 (块=" + blockKey
                        + ")，已暂停对该文件块的写入。请检查磁盘空间！");
            }
            removeWriter(blockKey);
            return 0;
        }
    }

    /**
     * 安全移除并关闭指定时间块的 BufferedWriter。
     * close 失败时重试一次，防止文件句柄泄漏。
     */
    private void removeWriter(String blockKey) {
        writerLock.lock();
        try {
            BufferedWriter w = writerMap.remove(blockKey);
            if (w != null) {
                closeSafely(w, blockKey);
            }
        } finally {
            writerLock.unlock();
        }
    }

    /**
     * 安全关闭 BufferedWriter：flush → close，失败重试一次。
     */
    private void closeSafely(BufferedWriter w, String key) {
        try {
            w.flush();
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING,
                    "刷新写入器失败 (" + key + "): " + e.getMessage());
        }
        try {
            w.close();
        } catch (IOException e) {
            // 重试一次
            try {
                w.close();
            } catch (IOException ignored) {
                plugin.getLogger().log(Level.WARNING,
                        "关闭写入器失败（重试后仍失败）(" + key + ")，句柄可能泄漏: " + e.getMessage());
            }
        }
    }

    /**
     * 获取或创建指定时间块的 BufferedWriter。
     * 文件被外部锁定时最多重试 2 次（间隔 100ms）。
     */
    private BufferedWriter getOrCreateWriter(String blockKey) throws IOException {
        writerLock.lock();
        try {
            if (shutdown) return null;

            BufferedWriter writer = writerMap.get(blockKey);
            if (writer != null) {
                return writer;
            }

            File logFile = new File(moveLogDir, blockKey + ".log");
            boolean isNewFile = !logFile.exists();

            // 文件锁重试（最多 3 次，间隔 100ms）
            IOException lastException = null;
            for (int attempt = 0; attempt < 3; attempt++) {
                try {
                    writer = new BufferedWriter(
                            new FileWriter(logFile, StandardCharsets.UTF_8, true), bufferSize);
                    break;
                } catch (IOException e) {
                    lastException = e;
                    if (attempt < 2) {
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException ignored) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
            }

            if (writer == null) {
                throw lastException != null ? lastException
                        : new IOException("无法创建日志文件写入器: " + logFile.getName());
            }

            writerMap.put(blockKey, writer);
            plugin.getLogger().info("创建日志文件: " + logFile.getName());

            // Excel BOM：新文件写入 UTF-8 BOM
            if (excelBom && isNewFile) {
                synchronized (bomWrittenFiles) {
                    if (!bomWrittenFiles.contains(blockKey)) {
                        writer.write('﻿'); // UTF-8 BOM
                        bomWrittenFiles.add(blockKey);
                    }
                }
            }

            return writer;
        } finally {
            writerLock.unlock();
        }
    }

    // ─── 聊天日志文件管理 ────────────────────────────────────────

    private String computeChatBlockKey(ZonedDateTime now) {
        String dateStr = dateFormatter.format(now);
        int blockHour = (now.getHour() / chatRotationHours) * chatRotationHours;
        String key = dateStr + "-" + PADDED_HOURS[blockHour];
        if (key.contains("..") || key.contains("/") || key.contains("\\")) {
            String safeDate = LocalDate.now(zoneId).toString();
            return safeDate + "-" + PADDED_HOURS[blockHour];
        }
        return key;
    }

    private void appendToChatFile(String blockKey, String line) {
        BufferedWriter writer = null;
        try {
            writer = getOrCreateChatWriter(blockKey);
            if (writer == null) return;
            writer.write(line);
            writer.newLine();
            writer.flush();
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING,
                    "写入聊天日志失败: " + e.getMessage());
            removeChatWriter(blockKey);
        }
    }

    private BufferedWriter getOrCreateChatWriter(String blockKey) throws IOException {
        chatWriterLock.lock();
        try {
            if (shutdown) return null;
            BufferedWriter writer = chatWriterMap.get(blockKey);
            if (writer != null) return writer;
            File f = new File(chatLogDir, blockKey + ".log");
            writer = new BufferedWriter(new FileWriter(f, StandardCharsets.UTF_8, true), bufferSize);
            chatWriterMap.put(blockKey, writer);
            return writer;
        } finally {
            chatWriterLock.unlock();
        }
    }

    private void removeChatWriter(String blockKey) {
        chatWriterLock.lock();
        try {
            BufferedWriter w = chatWriterMap.remove(blockKey);
            if (w != null) {
                try { w.flush(); } catch (IOException ignored) {}
                try { w.close(); } catch (IOException ignored) {
                    try { w.close(); } catch (IOException ignored2) {}
                }
            }
        } finally {
            chatWriterLock.unlock();
        }
    }

    private void cleanupStaleChatWriters(String currentBlockKey) {
        chatWriterLock.lock();
        try {
            List<String> stale = new ArrayList<>();
            for (String key : chatWriterMap.keySet()) {
                if (!key.equals(currentBlockKey)) stale.add(key);
            }
            for (String key : stale) {
                BufferedWriter w = chatWriterMap.get(key);
                if (w != null) {
                    try { w.flush(); } catch (IOException ignored) {}
                    try { w.close(); } catch (IOException ignored) {
                        try { w.close(); } catch (IOException ignored2) {}
                    }
                }
                chatWriterMap.remove(key);
            }
        } finally {
            chatWriterLock.unlock();
        }
    }

    // ─── 主日志清理 ────────────────────────────────────────────

    /**
     * 关闭非当前时间块的文件写入器，释放文件句柄。
     * 先 flush/close 再从 map 移除，防止 close 失败时句柄泄漏。
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
                BufferedWriter w = writerMap.get(key);
                if (w != null) {
                    closeSafely(w, key);
                }
                writerMap.remove(key);
                // 清理对应的失败计数
                synchronized (writeFailCounts) {
                    writeFailCounts.remove(key);
                }
                // 清理 BOM 追踪（释放内存）
                synchronized (bomWrittenFiles) {
                    bomWrittenFiles.remove(key);
                }
            }
        } finally {
            writerLock.unlock();
        }
    }

    // ─── 日志保留策略 ───────────────────────────────────────────

    private LocalDate lastCleanupDate = null;

    /**
     * 每天首次调用时触发一次旧日志清理。
     */
    private void cleanOldLogsIfDue(ZonedDateTime now) {
        if (logRetentionDays < 0) return;
        LocalDate today = now.toLocalDate();
        if (today.equals(lastCleanupDate)) return;
        lastCleanupDate = today;
        cleanOldLogs(now);
        if (chatLogDir != null) {
            cleanOldChatLogs(now);
        }
    }

    /**
     * 删除超过保留天数的日志文件。
     */
    private void cleanOldLogs(ZonedDateTime now) {
        File[] files = moveLogDir.listFiles((dir, name) -> name.endsWith(".log"));
        if (files == null || files.length == 0) return;

        LocalDate cutoff = now.toLocalDate().minusDays(logRetentionDays);
        int deleted = 0;

        for (File f : files) {
            try {
                String name = f.getName();
                // 文件名格式: yyyy-MM-dd-HH.log，提取日期部分（前 10 个字符）
                if (name.length() < 10) continue;
                String datePart = name.substring(0, 10);
                LocalDate fileDate = LocalDate.parse(datePart, DateTimeFormatter.ISO_LOCAL_DATE);
                if (fileDate.isBefore(cutoff)) {
                    // 确保文件没有被当前 writer 持有
                    String blockKey = name.substring(0, name.lastIndexOf('.'));
                    boolean isActive;
                    writerLock.lock();
                    try {
                        isActive = writerMap.containsKey(blockKey);
                    } finally {
                        writerLock.unlock();
                    }
                    if (!isActive && f.delete()) {
                        deleted++;
                    }
                }
            } catch (Exception ignored) {
                // 文件名不符合预期格式，跳过
            }
        }

        if (deleted > 0) {
            plugin.getLogger().info("已清理 " + deleted + " 个过期日志文件（保留 " + logRetentionDays + " 天）。");
        }
    }

    private void cleanOldChatLogs(ZonedDateTime now) {
        File[] files = chatLogDir.listFiles((dir, name) -> name.endsWith(".log"));
        if (files == null || files.length == 0) return;

        LocalDate cutoff = now.toLocalDate().minusDays(logRetentionDays);
        int deleted = 0;

        for (File f : files) {
            try {
                String name = f.getName();
                if (name.length() < 10) continue;
                String datePart = name.substring(0, 10);
                LocalDate fileDate = LocalDate.parse(datePart, DateTimeFormatter.ISO_LOCAL_DATE);
                if (fileDate.isBefore(cutoff)) {
                    String blockKey = name.substring(0, name.lastIndexOf('.'));
                    boolean isActive;
                    chatWriterLock.lock();
                    try {
                        isActive = chatWriterMap.containsKey(blockKey);
                    } finally {
                        chatWriterLock.unlock();
                    }
                    if (!isActive && f.delete()) {
                        deleted++;
                    }
                }
            } catch (Exception ignored) {}
        }

        if (deleted > 0) {
            plugin.getLogger().info("已清理 " + deleted + " 个过期聊天日志文件（保留 " + logRetentionDays + " 天）。");
        }
    }

    // ─── 生命周期管理 ───────────────────────────────────────────

    /**
     * 关闭插件时调用：等待当前 run() 完成 → 刷新事件队列 → 强制刷新并关闭所有文件写入器。
     * <p>
     * 使用"等待 running → 获取锁 → close"顺序消除 shutdown 与 run() 之间的竞态窗口。
     * </p>
     */
    public void shutdown() {
        // 0. 幂等检查
        if (this.shutdown) return;

        // 1. 设置关闭标志，阻止新周期启动
        this.shutdown = true;

        // 2. 等待正在执行的 run() 完成（最多 10 秒）
        long deadline = System.currentTimeMillis() + 10_000;
        while (running.get() && System.currentTimeMillis() < deadline) {
            Thread.yield();
        }
        if (running.get()) {
            plugin.getLogger().warning("等待记录周期完成超时（10 秒），将强制关闭。");
        }

        // 3. 排出事件队列中的剩余事件
        drainEventQueue();

        // 4. 排出聊天队列
        drainChatQueue();

        // 5. 安全关闭所有 writer（主日志 + 聊天日志）
        writerLock.lock();
        try {
            for (Map.Entry<String, BufferedWriter> entry : writerMap.entrySet()) {
                closeSafely(entry.getValue(), entry.getKey());
            }
            writerMap.clear();
        } finally {
            writerLock.unlock();
        }
        chatWriterLock.lock();
        try {
            for (Map.Entry<String, BufferedWriter> entry : chatWriterMap.entrySet()) {
                try { entry.getValue().flush(); } catch (IOException ignored) {}
                try { entry.getValue().close(); } catch (IOException ignored) {}
            }
            chatWriterMap.clear();
        } finally {
            chatWriterLock.unlock();
        }
    }

    /**
     * 关闭前排空事件队列。绕过 shutdown 检查直接写盘。
     */
    private void drainEventQueue() {
        ZonedDateTime now = ZonedDateTime.now(zoneId);
        String blockKey = computeBlockKey(now);
        List<String> batch = new ArrayList<>();
        String line;
        while ((line = eventQueue.poll()) != null) {
            batch.add(line);
        }
        if (batch.isEmpty()) return;

        // 绕过 getOrCreateWriter 的 shutdown 检查，直接获取或创建 writer
        writerLock.lock();
        try {
            BufferedWriter writer = writerMap.get(blockKey);
            if (writer == null) {
                writer = new BufferedWriter(
                        new FileWriter(new File(moveLogDir, blockKey + ".log"),
                                StandardCharsets.UTF_8, true), bufferSize);
                writerMap.put(blockKey, writer);
            }
            for (String l : batch) {
                writer.write(l);
                writer.newLine();
            }
            try { writer.flush(); } catch (IOException ignored) {}
            plugin.getLogger().info("已排出 " + batch.size() + " 条待写入事件。");
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING,
                    "排出事件队列时发生错误（共 " + batch.size() + " 条可能丢失）: " + e.getMessage(), e);
        } finally {
            writerLock.unlock();
        }
    }

    /**
     * 关闭前排出聊天队列。绕过 shutdown 检查直接创建 writer 写盘。
     */
    private void drainChatQueue() {
        if (chatLogDir == null) return;
        ZonedDateTime now = ZonedDateTime.now(zoneId);
        String blockKey = computeChatBlockKey(now);
        String cl;
        int count = 0;

        // 绕过 getOrCreateChatWriter 的 shutdown 检查，直接获取或创建 writer
        chatWriterLock.lock();
        try {
            BufferedWriter writer = chatWriterMap.get(blockKey);
            if (writer == null) {
                writer = new BufferedWriter(
                        new FileWriter(new File(chatLogDir, blockKey + ".log"),
                                StandardCharsets.UTF_8, true), bufferSize);
                chatWriterMap.put(blockKey, writer);
            }
            while ((cl = chatQueue.poll()) != null) {
                try {
                    writer.write(cl);
                    writer.newLine();
                    count++;
                } catch (IOException e) {
                    plugin.getLogger().log(Level.WARNING,
                            "排出聊天队列时发生错误: " + e.getMessage());
                }
            }
            try { writer.flush(); } catch (IOException ignored) {}
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING,
                    "排出聊天队列时无法创建文件: " + e.getMessage());
        } finally {
            chatWriterLock.unlock();
        }

        if (count > 0) {
            plugin.getLogger().info("已排出 " + count + " 条待写入的聊天记录。");
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

    /** 最近一次成功记录的时间戳（毫秒），0 表示尚未成功记录过 */
    public long getLastSuccessTime() {
        return lastSuccessTime.get();
    }

    /** 累计成功记录的日志行数 */
    public long getTotalRecords() {
        return totalRecords.get();
    }

    /** 累计因 TPS 过低跳过的周期数 */
    public long getTotalTpsSkips() {
        return totalTpsSkips.get();
    }

    /** 消毒玩家名：防止 | \n \r 破坏日志格式 */
    private static String safeName(String name) {
        return name.replace('|', '_').replace('\n', ' ').replace('\r', ' ');
    }

    /** 安全格式化坐标（NaN/Infinity → 0.00） */
    private String fmtCoord(double v) {
        return Double.isFinite(v) ? coordFormat.format(v) : "0.00";
    }
}
