package com.proma.movelog;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.logging.Level;

/**
 * PlayerMoveLog — Paper 插件主类
 * <p>
 * 每 1 分钟（默认）异步记录所有在线玩家的坐标和主手物品到服务器根目录的 movelog 文件夹，
 * 按日期分文件存储，零阻塞主线程，内置 TPS 保护。
 * </p>
 *
 * <h3>功能</h3>
 * <ul>
 *   <li>定时记录（默认 60 秒/次）</li>
 *   <li>事件驱动记录（加入/退出/死亡/切换世界）</li>
 *   <li>玩家/世界过滤系统</li>
 *   <li>日志保留策略（自动清理过期文件）</li>
 *   <li>/movelog search 查询玩家轨迹</li>
 *   <li>TPS 保护 + 健康检查</li>
 *   <li>Excel UTF-8 BOM 兼容（可选）</li>
 *   <li>安全热重载（validate-then-switch，失败回滚）</li>
 * </ul>
 */
public final class PlayerMoveLog extends JavaPlugin implements CommandExecutor, TabCompleter {

    /** reload 后启动新定时任务的初始延迟（ticks = 10 秒），给初始化留出缓冲 */
    private static final long RELOAD_INITIAL_DELAY_TICKS = 200L;

    private MoveRecorder moveRecorder;
    private int recorderTaskId = -1;
    private File moveLogDir;
    private long recordIntervalTicks;
    private String timezone;
    private int searchLines;

    // ─── 插件生命周期 ───────────────────────────────────────────

    @Override
    public void onEnable() {
        saveDefaultConfig();

        // 确定 movelog 输出目录
        File serverRoot = getServer().getWorldContainer();
        String outputDirName = getConfig().getString("output-dir", "movelog");
        if (outputDirName == null || outputDirName.isEmpty()) outputDirName = "movelog";

        // 路径穿越防护
        if (outputDirName.contains("..") || outputDirName.contains("/") || outputDirName.contains("\\")) {
            getLogger().severe("output-dir 包含非法路径字符: " + outputDirName);
            getLogger().warning("插件已禁用，请修复配置文件中的 output-dir 后重启服务器。");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        moveLogDir = new File(serverRoot, outputDirName);
        timezone = getConfig().getString("timezone", "Asia/Shanghai");

        // 创建目录并验证可写性
        if (!moveLogDir.exists() && !moveLogDir.mkdirs()) {
            getLogger().severe("无法创建 movelog 目录: " + moveLogDir.getAbsolutePath());
            getLogger().warning("插件已禁用，请检查文件系统权限后重启服务器。");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        // 写测试
        File testFile = new File(moveLogDir, ".writetest");
        try {
            Files.writeString(testFile.toPath(), "test");
        } catch (IOException e) {
            getLogger().severe("movelog 目录不可写: " + moveLogDir.getAbsolutePath());
            getLogger().log(Level.SEVERE, "错误详情: " + e.getMessage(), e);
            getLogger().warning("插件已禁用，请检查文件系统权限后重启服务器。");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        try {
            Files.deleteIfExists(testFile.toPath());
        } catch (IOException e) {
            testFile.deleteOnExit(); // 注册 JVM 退出时清理
            getLogger().warning("无法删除写入测试文件（将在 JVM 退出时清理）: " + e.getMessage());
        }

        getLogger().info("movelog 输出目录: " + moveLogDir.getAbsolutePath());

        // 读取所有配置并创建 MoveRecorder
        MoveRecorder recorder;
        try {
            recorder = buildRecorderFromConfig();
        } catch (RuntimeException e) {
            getLogger().log(Level.SEVERE, "创建记录器失败（配置有误？）: " + e.getMessage(), e);
            getLogger().warning("插件已禁用，请检查配置文件后重启服务器。");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        moveRecorder = recorder;

        // 注册命令
        var cmd = getCommand("movelog");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }

        // 注册事件监听器（需要监听事件或聊天时都必须注册）
        searchLines = Math.max(1, getConfig().getInt("search-lines", 20));
        boolean eventLogEnabled = getConfig().getBoolean("event-logging.enabled", true);
        boolean chatLogEnabled = getConfig().getBoolean("chat-logging.enabled", false);
        if (eventLogEnabled || chatLogEnabled) {
            getServer().getPluginManager().registerEvents(
                    new MoveEventListener(moveRecorder, timezone,
                            getConfig().getString("time-format", "yyyy-MM-dd HH:mm:ss"),
                            getConfig().getString("item-empty-text", "空"),
                            getConfig().getBoolean("log-inventory", false)),
                    this);
        }

        // 启动异步定时任务
        var task = moveRecorder.runTaskTimerAsynchronously(this, 0L, recordIntervalTicks);
        recorderTaskId = task.getTaskId();

        getLogger().info("PlayerMoveLog 已启动");
        getLogger().info("  记录间隔: " + (recordIntervalTicks / 20) + " 秒");
        getLogger().info("  切分间隔: " + getConfig().getInt("rotation-hours", 4) + " 小时");
        getLogger().info("  TPS 阈值: " + getConfig().getDouble("tps-threshold", 18.0));
        getLogger().info("  日志保留: " + getConfig().getInt("log-retention-days", 30) + " 天");
        getLogger().info("  Excel BOM: " + (getConfig().getBoolean("excel-bom", false) ? "开启" : "关闭"));
        getLogger().info("  事件记录: " + (eventLogEnabled ? "开启" : "关闭"));
        getLogger().info("  背包记录: " + (getConfig().getBoolean("log-inventory", false) ? "开启" : "关闭"));
        getLogger().info("  聊天记录: " + (getConfig().getBoolean("chat-logging.enabled", false) ? "开启" : "关闭"));
        getLogger().info("  记录状态: " + (getConfig().getBoolean("enabled", true) ? "开启" : "暂停"));
        getLogger().info("  输出目录: " + moveLogDir.getAbsolutePath());
    }

    @Override
    public void onDisable() {
        if (recorderTaskId != -1) {
            Bukkit.getScheduler().cancelTask(recorderTaskId);
        }

        if (moveRecorder != null) {
            moveRecorder.shutdown();
        }

        getLogger().info("PlayerMoveLog 已卸载，所有缓冲区已刷新。");
    }

    // ─── 配置读取 ───────────────────────────────────────────────

    /**
     * 从当前 config.yml 构建 MoveRecorder 实例（同时更新实例级配置字段）。
     */
    private MoveRecorder buildRecorderFromConfig() {
        recordIntervalTicks = getConfig().getLong("record-interval-ticks", 1200L);
        if (recordIntervalTicks < 100) {
            getLogger().warning("record-interval-ticks 必须 >= 100（5 秒），已重置为默认值 1200（60 秒）");
            recordIntervalTicks = 1200L;
        }
        int rotationHours = getConfig().getInt("rotation-hours", 4);
        double tpsThreshold = getConfig().getDouble("tps-threshold", 18.0);
        int bufferSize = getConfig().getInt("buffer-size", 8192);
        int batchThreshold = getConfig().getInt("batch-threshold", 100);
        int batchSize = getConfig().getInt("batch-size", 50);
        String dateFormat = getConfig().getString("date-format", "yyyy-MM-dd");
        String timeFormat = getConfig().getString("time-format", "yyyy-MM-dd HH:mm:ss");
        boolean enabled = getConfig().getBoolean("enabled", true);
        int logRetentionDays = getConfig().getInt("log-retention-days", -1);
        boolean excelBom = getConfig().getBoolean("excel-bom", false);
        String emptyItemText = getConfig().getString("item-empty-text", "空");
        String exemptPerm = getConfig().getString("filter.exempt-permission", "");
        List<String> worldWhitelist = getConfig().getStringList("filter.world-whitelist");
        List<String> worldBlacklist = getConfig().getStringList("filter.world-blacklist");
        List<String> excludedPlayers = getConfig().getStringList("filter.excluded-players");
        List<String> includedPlayers = getConfig().getStringList("filter.included-players");
        int maxFailures = getConfig().getInt("max-consecutive-failures", 3);
        boolean eventLogEnabled = getConfig().getBoolean("event-logging.enabled", true);
        boolean logInventory = getConfig().getBoolean("log-inventory", false);

        // 聊天日志
        boolean chatLogEnabled = getConfig().getBoolean("chat-logging.enabled", false);
        File chatLogDir = null;
        int chatRotationHours = rotationHours;
        if (chatLogEnabled) {
            String chatDirName = getConfig().getString("chat-logging.output-dir", "chatlog");
            if (chatDirName == null || chatDirName.contains("..") || chatDirName.contains("/") || chatDirName.contains("\\")) {
                getLogger().warning("chat-logging.output-dir 包含非法路径字符，聊天记录已禁用。");
            } else {
                chatLogDir = new File(getServer().getWorldContainer(), chatDirName);
                if (!chatLogDir.exists() && !chatLogDir.mkdirs()) {
                    getLogger().warning("无法创建 chatlog 目录: " + chatLogDir.getAbsolutePath() + "，聊天记录已禁用。");
                    chatLogDir = null;
                }
                if (chatLogDir != null) {
                    chatRotationHours = getConfig().getInt("chat-logging.rotation-hours", rotationHours);
                    if (chatRotationHours < 1 || chatRotationHours > 24 || 24 % chatRotationHours != 0) {
                        chatRotationHours = rotationHours;
                    }
                }
            }
        }

        return new MoveRecorder(
                this, moveLogDir, tpsThreshold, bufferSize, batchThreshold, batchSize,
                rotationHours, timezone, dateFormat, timeFormat, enabled,
                logRetentionDays, excelBom, emptyItemText, exemptPerm,
                worldWhitelist, worldBlacklist, excludedPlayers, includedPlayers,
                maxFailures, eventLogEnabled, logInventory, chatLogDir, chatRotationHours
        );
    }

    // ─── 命令处理 ───────────────────────────────────────────────

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            printStatus(sender);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "on":
                if (moveRecorder != null) {
                    moveRecorder.setEnabled(true);
                    sender.sendMessage("§a[PlayerMoveLog] 记录已开启。");
                    getLogger().info("记录已由 " + sender.getName() + " 手动开启。");
                } else {
                    sender.sendMessage("§c[PlayerMoveLog] 插件未初始化。");
                }
                break;

            case "off":
                if (moveRecorder != null) {
                    moveRecorder.setEnabled(false);
                    sender.sendMessage("§e[PlayerMoveLog] 记录已暂停。");
                    getLogger().info("记录已由 " + sender.getName() + " 手动暂停。");
                } else {
                    sender.sendMessage("§c[PlayerMoveLog] 插件未初始化。");
                }
                break;

            case "reload":
                reloadConfig();
                if (applyConfig(sender)) {
                    sender.sendMessage("§a[PlayerMoveLog] 配置已重新加载。");
                    getLogger().info("配置已由 " + sender.getName() + " 重新加载。");
                }
                break;

            case "status":
                printStatus(sender);
                break;

            case "stats":
                printStats(sender);
                break;

            case "search":
                if (args.length < 2) {
                    sender.sendMessage("§c用法: /movelog search <玩家名> [页码]");
                    return true;
                }
                int page = 1;
                if (args.length > 2) {
                    try {
                        page = Integer.parseInt(args[2]);
                        if (page < 1) page = 1;
                    } catch (NumberFormatException e) {
                        sender.sendMessage("§c页码必须是数字。");
                        return true;
                    }
                }
                searchPlayer(sender, args[1], page);
                break;

            case "version":
                sender.sendMessage("§6[PlayerMoveLog] §e版本 " + getDescription().getVersion());
                sender.sendMessage("§7  Paper 1.21.x · Java 21 · MIT License");
                sender.sendMessage("§7  https://github.com/KUBOAKI01/movelog");
                break;

            default:
                sender.sendMessage("§c用法: /movelog <on|off|reload|status|stats|search|version>");
                break;
        }
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                 @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase();
            List<String> options = new ArrayList<>();
            for (String opt : Arrays.asList("on", "off", "reload", "status", "stats", "search", "version")) {
                if (opt.startsWith(prefix)) {
                    options.add(opt);
                }
            }
            return options;
        }
        return Collections.emptyList();
    }

    // ─── 状态显示 ───────────────────────────────────────────────

    private void printStatus(CommandSender sender) {
        boolean enabled = moveRecorder != null && moveRecorder.isEnabled();
        double tps = Bukkit.getServer().getTPS()[0];
        int totalOnline = Bukkit.getOnlinePlayers().size();
        // 统计真人玩家数（与记录器使用相同过滤逻辑）
        int realPlayers = 0;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getAddress() != null) realPlayers++;
        }
        long lastSuccess = moveRecorder != null ? moveRecorder.getLastSuccessTime() : 0;
        String lastRecordStr = lastSuccess > 0
                ? new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date(lastSuccess))
                : "暂无记录";

        sender.sendMessage("§6========== PlayerMoveLog 状态 ==========");
        sender.sendMessage("§e  记录状态: " + (enabled ? "§a开启" : "§c暂停"));
        sender.sendMessage("§e  记录间隔: " + (recordIntervalTicks / 20) + " 秒");
        sender.sendMessage("§e  TPS 阈值: " + (moveRecorder != null ? moveRecorder.getTpsThreshold() : "N/A"));
        sender.sendMessage("§e  当前 TPS: " + String.format(Locale.US, "%.1f", tps));
        sender.sendMessage("§e  在线玩家: " + realPlayers + "（总计 " + totalOnline + "）");
        sender.sendMessage("§e  累计记录: " + (moveRecorder != null ? moveRecorder.getTotalRecords() : 0) + " 条");
        sender.sendMessage("§e  上次记录: " + lastRecordStr);
        sender.sendMessage("§e  输出目录: " + moveLogDir.getAbsolutePath());
        sender.sendMessage("§6========================================");
    }

    private void printStats(CommandSender sender) {
        if (moveRecorder == null) {
            sender.sendMessage("§c[PlayerMoveLog] 插件未初始化。");
            return;
        }
        sender.sendMessage("§6========== PlayerMoveLog 统计 ==========");
        sender.sendMessage("§e  累计记录行数: " + moveRecorder.getTotalRecords());
        sender.sendMessage("§e  TPS 跳过次数: " + moveRecorder.getTotalTpsSkips());
        sender.sendMessage("§e  日志目录大小: " + getDirSize(moveLogDir));
        sender.sendMessage("§6========================================");
    }

    private String getDirSize(File dir) {
        if (!dir.exists()) return "0 B";
        long size = 0;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isFile()) size += f.length();
            }
        }
        if (size < 1024) return size + " B";
        if (size < 1024 * 1024) return String.format(Locale.US, "%.1f KB", size / 1024.0);
        return String.format(Locale.US, "%.1f MB", size / (1024.0 * 1024.0));
    }

    // ─── 查询功能 ───────────────────────────────────────────────

    private void searchPlayer(CommandSender sender, String playerName, int page) {
        if (moveLogDir == null || !moveLogDir.exists()) {
            sender.sendMessage("§c[PlayerMoveLog] 日志目录不存在。");
            return;
        }

        // 按修改时间倒序列出日志文件
        File[] logFiles = moveLogDir.listFiles((dir, name) -> name.endsWith(".log"));
        if (logFiles == null || logFiles.length == 0) {
            sender.sendMessage("§e[PlayerMoveLog] 暂无日志文件。");
            return;
        }
        Arrays.sort(logFiles, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));

        // 从最近的文件开始搜索，收集匹配行
        List<String> matches = new ArrayList<>();
        int filesSearched = 0;
        for (File logFile : logFiles) {
            if (filesSearched >= 5) break; // 最多搜索 5 个文件
            try (BufferedReader reader = new BufferedReader(
                    new FileReader(logFile, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.contains(playerName)) {
                        // 提取并格式化时间部分
                        String displayLine = line;
                        if (line.length() > 19) {
                            String timePart = line.substring(0, 19);
                            String rest = line.substring(19);
                            displayLine = "§7" + timePart + "§r" + rest;
                        }
                        matches.add(displayLine);
                    }
                }
            } catch (IOException e) {
                getLogger().log(Level.WARNING, "搜索日志文件失败: " + logFile.getName(), e);
            }
            filesSearched++;
        }

        if (matches.isEmpty()) {
            sender.sendMessage("§e[PlayerMoveLog] 未找到玩家 §b" + playerName + "§e 的记录。");
            return;
        }

        int perPage = searchLines;
        int totalPages = (int) Math.ceil((double) matches.size() / perPage);
        if (page > totalPages) page = totalPages;

        int start = (page - 1) * perPage;
        int end = Math.min(start + perPage, matches.size());

        sender.sendMessage("§6===== " + playerName + " 的轨迹记录 (" + page + "/" + totalPages + ") =====");
        for (int i = start; i < end; i++) {
            sender.sendMessage(matches.get(i));
        }
        sender.sendMessage("§7共 " + matches.size() + " 条记录 | 使用 /movelog search " + playerName + " " + (page + 1) + " 翻页");
    }

    // ─── 配置热重载 ─────────────────────────────────────────────

    /**
     * 将当前配置同步到记录器（热重载时调用）。
     * <p>
     * 采用"先创建新实例 → 先启动新任务 → 再关闭旧实例"的顺序，
     * 确保新任务启动失败时旧记录器不受影响（避免僵尸状态）。
     * </p>
     */
    private boolean applyConfig(CommandSender sender) {
        if (moveRecorder == null) return false;

        // ── 第一步：验证新输出目录 ──
        File serverRoot = getServer().getWorldContainer();
        String outputDirName = getConfig().getString("output-dir", "movelog");
        if (outputDirName == null || outputDirName.isEmpty()) outputDirName = "movelog";

        // 路径穿越防护
        if (outputDirName.contains("..") || outputDirName.contains("/") || outputDirName.contains("\\")) {
            getLogger().severe("output-dir 包含非法路径字符: " + outputDirName);
            sender.sendMessage("§c[PlayerMoveLog] reload 失败：output-dir 包含非法路径字符。");
            return false;
        }

        File newLogDir = new File(serverRoot, outputDirName);
        if (!newLogDir.exists() && !newLogDir.mkdirs()) {
            getLogger().severe("无法创建新的输出目录: " + newLogDir.getAbsolutePath());
            sender.sendMessage("§c[PlayerMoveLog] reload 失败：无法创建输出目录，旧配置仍在使用。");
            return false;
        }

        // ── 第二步：更新 timezone（供 buildRecorderFromConfig 使用）──
        timezone = getConfig().getString("timezone", "Asia/Shanghai");

        // ── 第三步：构建新记录器 ──
        MoveRecorder newRecorder;
        try {
            newRecorder = buildRecorderFromConfig();
        } catch (RuntimeException e) {
            getLogger().log(Level.SEVERE,
                    "创建新记录器失败，请检查配置文件: " + e.getMessage(), e);
            sender.sendMessage("§c[PlayerMoveLog] reload 失败：配置参数非法，详见服务器日志。旧配置仍在使用。");
            return false;
        }

        // ── 第四步：先启动新任务（失败则回滚，旧记录器不受影响）──
        BukkitTask newTask;
        try {
            newTask = newRecorder.runTaskTimerAsynchronously(this, RELOAD_INITIAL_DELAY_TICKS, recordIntervalTicks);
        } catch (RuntimeException e) {
            newRecorder.shutdown();
            getLogger().log(Level.SEVERE,
                    "启动新记录器任务失败，已回滚。旧记录器继续运行: " + e.getMessage(), e);
            sender.sendMessage("§c[PlayerMoveLog] reload 失败：无法启动新任务，旧配置仍在使用。");
            return false;
        }

        // ── 第五步：新任务已成功启动，安全切换 ──
        moveLogDir = newLogDir;

        // 取消旧任务
        if (recorderTaskId != -1) {
            Bukkit.getScheduler().cancelTask(recorderTaskId);
            recorderTaskId = -1;
        }

        // 关闭旧记录器（新记录器已在运行，安全关闭旧的）
        moveRecorder.shutdown();

        // 切换引用
        moveRecorder = newRecorder;
        recorderTaskId = newTask.getTaskId();
        searchLines = getConfig().getInt("search-lines", 20);

        // 重新注册事件监听器（先注销旧的再注册新的）
        org.bukkit.event.HandlerList.unregisterAll((org.bukkit.plugin.Plugin) this);
        boolean eventLogEnabled = getConfig().getBoolean("event-logging.enabled", true);
        boolean chatLogEnabled = getConfig().getBoolean("chat-logging.enabled", false);
        if (eventLogEnabled || chatLogEnabled) {
            getServer().getPluginManager().registerEvents(
                    new MoveEventListener(moveRecorder, timezone,
                            getConfig().getString("time-format", "yyyy-MM-dd HH:mm:ss"),
                            getConfig().getString("item-empty-text", "空"),
                            getConfig().getBoolean("log-inventory", false)),
                    this);
        }

        getLogger().info("配置已重新加载，记录器已重建。");
        getLogger().info("  记录间隔: " + (recordIntervalTicks / 20) + " 秒");
        getLogger().info("  TPS 阈值: " + moveRecorder.getTpsThreshold());
        getLogger().info("  切分间隔: " + getConfig().getInt("rotation-hours", 4) + " 小时");
        getLogger().info("  日志保留: " + getConfig().getInt("log-retention-days", 30) + " 天");
        getLogger().info("  记录状态: " + (moveRecorder.isEnabled() ? "开启" : "暂停"));

        return true;
    }
}
