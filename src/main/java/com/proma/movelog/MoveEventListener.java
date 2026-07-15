package com.proma.movelog;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 事件驱动的玩家轨迹监听器。
 * <p>
 * 监听玩家加入、退出、死亡、切换世界、传送等关键事件，
 * 在主线程中格式化事件日志行（纯字符串操作，不涉及 I/O），
 * 然后通过 {@link MoveRecorder#recordEvent(String)} 推送到异步写入队列。
 * </p>
 *
 * <h3>设计原则</h3>
 * <ul>
 *   <li>所有事件处理使用 {@link EventPriority#MONITOR} 优先级，不干扰其他插件。</li>
 *   <li>{@code ignoreCancelled = true} 跳过已取消的事件。</li>
 *   <li>不在主线程做任何 I/O 操作。</li>
 *   <li>NPC/假玩家（{@code getAddress() == null}）自动跳过。</li>
 * </ul>
 */
public class MoveEventListener implements Listener {

    private final MoveRecorder recorder;
    private final ZoneId zoneId;
    private final DateTimeFormatter timeFormatter;
    private final String emptyItemText;
    private final boolean logInventory;

    public MoveEventListener(MoveRecorder recorder, String timezone,
                             String timeFormat, String emptyItemText,
                             boolean logInventory) {
        this.recorder = recorder;
        this.zoneId = ZoneId.of(timezone);
        this.timeFormatter = DateTimeFormatter.ofPattern(timeFormat).withZone(zoneId);
        this.emptyItemText = emptyItemText;
        this.logInventory = logInventory;
    }

    // ─── 事件处理 ───────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!isRealPlayer(player) || !recorder.shouldRecord(player)) return;
        recorder.recordEvent(formatEvent(player, "JOIN"));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (!isRealPlayer(player) || !recorder.shouldRecord(player)) return;
        recorder.recordEvent(formatEvent(player, "QUIT"));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        if (!isRealPlayer(player) || !recorder.shouldRecord(player)) return;
        String deathMsg = event.getDeathMessage();
        String cause = (deathMsg != null) ? deathMsg.replace(player.getName(), "").trim() : "unknown";
        recorder.recordEvent(formatEvent(player, "DEATH | " + cause));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        if (!isRealPlayer(player) || !recorder.shouldRecord(player)) return;
        String from = event.getFrom().getName();
        recorder.recordEvent(formatEvent(player, "WORLD_CHANGE | from=" + from));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        if (!isRealPlayer(player) || !recorder.shouldRecord(player)) return;
        String msg = PlainTextComponentSerializer.plainText().serialize(event.message());
        // 转义换行符防止破坏日志格式
        msg = msg.replace('\n', ' ').replace('\r', ' ');
        recorder.recordChat(formatChat(player, msg));
    }

    // ─── 内部方法 ───────────────────────────────────────────────

    /**
     * 判断是否为真实玩家（排除 NPC/假玩家）。
     */
    private boolean isRealPlayer(Player player) {
        return player.getAddress() != null;
    }

    /**
     * 格式化事件日志行。
     * <p>
     * 格式与定时记录兼容，末尾追加事件类型标记：
     * {@code 时间 | 玩家名 | 世界:X:Y:Z | 主手物品 | EVENT:事件类型}
     * </p>
     */
    private String formatEvent(Player player, String eventType) {
        String timeStr = timeFormatter.format(ZonedDateTime.now(zoneId));
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
          .append(fmtCoord(player.getZ()))
          .append(" | ").append(itemStr);

        if (logInventory) {
            sb.append(" | INV ");
            appendInventorySummary(sb, player);
        }

        sb.append(" | EVENT:").append(eventType);
        return sb.toString();
    }

    /**
     * 格式化聊天日志行。
     * 格式：{@code 时间 | 玩家名 | 世界:X:Y:Z | 消息内容}
     */
    private String formatChat(Player player, String message) {
        String timeStr = timeFormatter.format(ZonedDateTime.now(zoneId));
        return new StringBuilder(256)
                .append(timeStr).append(" | ")
                .append(safeName(player.getName())).append(" | ")
                .append(player.getWorld().getName()).append(':')
                .append(fmtCoord(player.getX())).append(':')
                .append(fmtCoord(player.getY())).append(':')
                .append(fmtCoord(player.getZ()))
                .append(" | ").append(message)
                .toString();
    }

    private static String safeName(String name) {
        return name.replace('|', '_').replace('\n', ' ').replace('\r', ' ');
    }

    private static String fmtCoord(double v) {
        return Double.isFinite(v)
                ? String.format(java.util.Locale.US, "%.2f", v) : "0.00";
    }

    private void appendInventorySummary(StringBuilder sb, Player player) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (ItemStack item : player.getInventory().getContents()) {
            if (item != null && !item.getType().isAir()) {
                counts.merge(item.getType().getKey().toString(), item.getAmount(), Integer::sum);
            }
        }
        ItemStack offHand = player.getInventory().getItemInOffHand();
        if (offHand != null && !offHand.getType().isAir()) {
            counts.merge(offHand.getType().getKey().toString(), offHand.getAmount(), Integer::sum);
        }
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
}
