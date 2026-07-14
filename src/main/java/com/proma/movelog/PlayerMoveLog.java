package com.proma.movelog;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;

/**
 * PlayerMoveLog — Paper 插件主类
 * <p>
 * 每 1 分钟（默认）异步记录所有在线玩家的坐标和主手物品到服务器根目录的 movelog 文件夹，
 * 按日期分文件存储，零阻塞主线程，内置 TPS 保护。
 * </p>
 */
public final class PlayerMoveLog extends JavaPlugin implements CommandExecutor, TabCompleter {

    private MoveRecorder moveRecorder;
    private int recorderTaskId = -1;
    private File moveLogDir;
    private long recordIntervalTicks;

    // ─── 插件生命周期 ───────────────────────────────────────────

    @Override
    public void onEnable() {
        // 保存默认配置文件
        saveDefaultConfig();

        // 确定 movelog 输出目录：服务器根目录/movelog
        File serverRoot = getServer().getWorldContainer();
        String outputDirName = getConfig().getString("output-dir", "movelog");
        moveLogDir = new File(serverRoot, outputDirName);

        // 尝试创建目录并验证可写性
        if (!moveLogDir.exists() && !moveLogDir.mkdirs()) {
            getLogger().severe("无法创建 movelog 目录: " + moveLogDir.getAbsolutePath());
            getLogger().warning("插件已禁用，请检查文件系统权限后重启服务器。");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        // 写测试：验证目录可写
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
            getLogger().warning("无法删除写入测试文件: " + e.getMessage());
            // 不影响插件功能，目录已确认可写
        }

        getLogger().info("movelog 输出目录: " + moveLogDir.getAbsolutePath());

        // 读取配置
        recordIntervalTicks = getConfig().getLong("record-interval-ticks", 1200L);
        if (recordIntervalTicks < 1) {
            getLogger().warning("record-interval-ticks 必须 >= 1，已重置为默认值 1200（60 秒）");
            recordIntervalTicks = 1200L;
        }
        double tpsThreshold = getConfig().getDouble("tps-threshold", 18.0);
        int bufferSize = getConfig().getInt("buffer-size", 8192);
        int batchThreshold = getConfig().getInt("batch-threshold", 100);
        int batchSize = getConfig().getInt("batch-size", 50);
        int rotationHours = getConfig().getInt("rotation-hours", 4);
        String timezone = getConfig().getString("timezone", "Asia/Shanghai");
        String dateFormat = getConfig().getString("date-format", "yyyy-MM-dd");
        String timeFormat = getConfig().getString("time-format", "yyyy-MM-dd HH:mm:ss");
        boolean enabled = getConfig().getBoolean("enabled", true);

        // 创建异步记录器
        moveRecorder = new MoveRecorder(
                this, moveLogDir, tpsThreshold, bufferSize,
                batchThreshold, batchSize, rotationHours,
                timezone, dateFormat, timeFormat, enabled
        );

        // 注册命令
        var cmd = getCommand("movelog");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }

        // 启动异步定时任务（零初始延迟，按配置间隔循环）
        var task = moveRecorder.runTaskTimerAsynchronously(this, 0L, recordIntervalTicks);
        recorderTaskId = task.getTaskId();

        getLogger().info("PlayerMoveLog 已启动");
        getLogger().info("  记录间隔: " + (recordIntervalTicks / 20) + " 秒");
        getLogger().info("  切分间隔: " + rotationHours + " 小时");
        getLogger().info("  TPS 阈值: " + tpsThreshold);
        getLogger().info("  记录状态: " + (enabled ? "开启" : "暂停"));
        getLogger().info("  输出目录: " + moveLogDir.getAbsolutePath());
    }

    @Override
    public void onDisable() {
        // 取消定时任务
        if (recorderTaskId != -1) {
            Bukkit.getScheduler().cancelTask(recorderTaskId);
        }

        // 终止记录器，强制刷新并关闭所有文件流
        if (moveRecorder != null) {
            moveRecorder.shutdown();
        }

        getLogger().info("PlayerMoveLog 已卸载，所有缓冲区已刷新。");
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
                }
                break;

            case "off":
                if (moveRecorder != null) {
                    moveRecorder.setEnabled(false);
                    sender.sendMessage("§e[PlayerMoveLog] 记录已暂停。");
                    getLogger().info("记录已由 " + sender.getName() + " 手动暂停。");
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

            default:
                sender.sendMessage("§c用法: /movelog <on|off|reload|status>");
                break;
        }
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                 @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            return Arrays.asList("on", "off", "reload", "status");
        }
        return Collections.emptyList();
    }

    // ─── 内部方法 ───────────────────────────────────────────────

    private void printStatus(CommandSender sender) {
        boolean enabled = moveRecorder != null && moveRecorder.isEnabled();
        double tps = Bukkit.getServer().getTPS()[0];
        int online = Bukkit.getOnlinePlayers().size();

        sender.sendMessage("§6========== PlayerMoveLog 状态 ==========");
        sender.sendMessage("§e  记录状态: " + (enabled ? "§a开启" : "§c暂停"));
        sender.sendMessage("§e  记录间隔: " + (recordIntervalTicks / 20) + " 秒");
        sender.sendMessage("§e  TPS 阈值: " + (moveRecorder != null ? moveRecorder.getTpsThreshold() : "N/A"));
        sender.sendMessage("§e  当前 TPS: " + String.format(Locale.US, "%.1f", tps));
        sender.sendMessage("§e  在线玩家: " + online);
        sender.sendMessage("§e  输出目录: " + moveLogDir.getAbsolutePath());
        sender.sendMessage("§6========================================");
    }

    /**
     * 将当前配置同步到记录器（热重载时调用）。
     * <p>
     * 通过关闭旧记录器并构建新实例的方式，确保所有配置字段
     *（时区、日期格式、缓冲区大小、分批参数等）全部生效，
     * 而不只是 enabled / tpsThreshold / interval 三项。
     * </p>
     */
    private boolean applyConfig(CommandSender sender) {
        if (moveRecorder == null) return false;

        // ── 第一步：验证所有配置（不修改任何运行状态）──

        // 验证输出目录（用户可能在配置中修改了 output-dir）
        File serverRoot = getServer().getWorldContainer();
        String outputDirName = getConfig().getString("output-dir", "movelog");
        File newLogDir = new File(serverRoot, outputDirName);
        if (!newLogDir.exists() && !newLogDir.mkdirs()) {
            getLogger().severe("无法创建新的输出目录: " + newLogDir.getAbsolutePath());
            getLogger().warning("reload 已中止，请检查文件系统权限。旧记录器将继续运行。");
            sender.sendMessage("§c[PlayerMoveLog] reload 失败：无法创建输出目录，旧配置仍在使用。");
            return false;
        }

        // 从配置文件重新读取所有参数
        long newIntervalTicks = getConfig().getLong("record-interval-ticks", 1200L);
        if (newIntervalTicks < 1) {
            getLogger().warning("record-interval-ticks 必须 >= 1，已重置为默认值 1200（60 秒）");
            newIntervalTicks = 1200L;
        }
        double tpsThreshold = getConfig().getDouble("tps-threshold", 18.0);
        int bufferSize = getConfig().getInt("buffer-size", 8192);
        int batchThreshold = getConfig().getInt("batch-threshold", 100);
        int batchSize = getConfig().getInt("batch-size", 50);
        int rotationHours = getConfig().getInt("rotation-hours", 4);
        String timezone = getConfig().getString("timezone", "Asia/Shanghai");
        String dateFormat = getConfig().getString("date-format", "yyyy-MM-dd");
        String timeFormat = getConfig().getString("time-format", "yyyy-MM-dd HH:mm:ss");
        boolean enabled = getConfig().getBoolean("enabled", true);

        // 尝试创建新记录器（捕获配置错误如非法时区/日期格式/rotation-hours）
        MoveRecorder newRecorder;
        try {
            newRecorder = new MoveRecorder(
                    this, newLogDir, tpsThreshold, bufferSize,
                    batchThreshold, batchSize, rotationHours,
                    timezone, dateFormat, timeFormat, enabled
            );
        } catch (RuntimeException e) {
            getLogger().log(Level.SEVERE,
                    "创建新记录器失败，请检查配置文件（时区/日期格式是否合法？）: " + e.getMessage(), e);
            getLogger().warning("reload 已中止，旧记录器将继续运行。");
            sender.sendMessage("§c[PlayerMoveLog] reload 失败：配置参数非法，详见服务器日志。旧配置仍在使用。");
            return false;
        }

        // 至此所有验证通过，可以安全切换
        moveLogDir = newLogDir;

        // 取消旧定时任务
        if (recorderTaskId != -1) {
            Bukkit.getScheduler().cancelTask(recorderTaskId);
            recorderTaskId = -1;
        }

        // 安全关闭旧记录器（强制刷新所有缓冲区，防止数据丢失）
        moveRecorder.shutdown();

        // 切换到新实例
        moveRecorder = newRecorder;
        recordIntervalTicks = newIntervalTicks;

        // 启动新定时任务（初始延迟 200 ticks = 10 秒，留出初始化缓冲）
        var task = moveRecorder.runTaskTimerAsynchronously(this, 200L, recordIntervalTicks);
        recorderTaskId = task.getTaskId();

        getLogger().info("配置已重新加载，记录器已重建。");
        getLogger().info("  记录间隔: " + (recordIntervalTicks / 20) + " 秒");
        getLogger().info("  TPS 阈值: " + tpsThreshold);
        getLogger().info("  切分间隔: " + rotationHours + " 小时");
        getLogger().info("  缓冲区大小: " + bufferSize + " 字节");
        getLogger().info("  分批阈值/大小: " + batchThreshold + " / " + batchSize);
        getLogger().info("  记录状态: " + (enabled ? "开启" : "暂停"));

        return true;
    }
}
