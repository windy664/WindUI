package org.windy.windui.bukkit;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.windy.windui.server.UiHost;
import org.windy.windui.server.WindUiRuntime;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * {@code /windui} 子命令逻辑（平台内共享；各 Paper 版本的入口只把 sender+args 交进来）。与 Velocity 的
 * {@code UiCommand} 对称：open / hud / list / reload / status / event / hello。
 * <p>
 * open/event/效果分派全在 {@link org.windy.windui.server.GuiDriver}（server_core），本类只做参数解析、
 * 玩家解析与消息回显（Adventure）。
 */
public final class WindUiCommands {

    private static final List<String> PUBLIC_SUBCOMMANDS =
            List.of("open", "reload", "list", "hud", "status");

    private final WindUiRuntime runtime;
    private final BukkitPlatformAdapter adapter;

    public WindUiCommands(WindUiRuntime runtime, BukkitPlatformAdapter adapter) {
        this.runtime = runtime;
        this.adapter = adapter;
    }

    public void execute(CommandSender sender, String[] args) {
        if (args.length == 0) {
            usage(sender);
            return;
        }
        switch (args[0].toLowerCase()) {
            case "reload" -> {
                int n = runtime.driver().registry().load();
                int s = runtime.driver().scripts().load();
                int h = runtime.driver().huds().load();
                runtime.driver().images().clear();
                sender.sendMessage(Component.text(
                        "已重载 " + n + " 界面 + " + s + " 脚本 + " + h + " HUD", NamedTextColor.GREEN));
            }
            case "list" -> {
                Set<String> all = runtime.driver().ids();
                sender.sendMessage(Component.text(
                        all.isEmpty() ? "暂无界面（放 *.json 到 guis/ 或 *.js 到 scripts/）"
                                : "已加载界面: " + String.join(", ", all), NamedTextColor.AQUA));
            }
            case "open" -> handleOpen(sender, args);
            case "hud" -> handleHud(sender, args);
            case "status" -> handleStatus(sender, args);
            case "event" -> handleEvent(sender, args);
            case "hello" -> handleHello(sender, args);
            default -> usage(sender);
        }
    }

    private void handleOpen(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Component.text("用法: /windui open <界面> [玩家]", NamedTextColor.RED));
            return;
        }
        Player target = resolveTarget(sender, args, 2);
        if (target == null) {
            return;
        }
        String kind = runtime.driver().open(adapter.hostFor(target), args[1]);
        if (kind == null) {
            sender.sendMessage(Component.text(
                    "界面不存在或脚本失败: " + args[1] + "（用 /windui list 查看）", NamedTextColor.RED));
            return;
        }
        sender.sendMessage(Component.text(
                "已向 " + target.getName() + " 推送界面 " + args[1] + " (" + kind + ")", NamedTextColor.GREEN));
    }

    private void handleHud(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(Component.text("只有玩家能接收 HUD", NamedTextColor.RED));
            return;
        }
        UiHost host = adapter.hostFor(p);
        if (args.length >= 2 && args[1].equalsIgnoreCase("clear")) {
            runtime.driver().hud(host, "[]");
            sender.sendMessage(Component.text("已清空 HUD", NamedTextColor.GRAY));
            return;
        }
        String id = args.length >= 2 ? args[1] : "demo";
        String spec = runtime.driver().huds().get(id);
        if (spec == null) {
            sender.sendMessage(Component.text(
                    "HUD 不存在: " + id + "（在 huds/ 放置 *.json）", NamedTextColor.RED));
            return;
        }
        runtime.driver().hud(host, spec);
        sender.sendMessage(Component.text(
                "已推送 HUD " + id + "，使用 /windui hud clear 清空", NamedTextColor.GREEN));
    }

    /** {@code /windui status [玩家]} —— 查看 WS 会话与协议版本，供部署验收和排障。 */
    private void handleStatus(CommandSender sender, String[] args) {
        Player target = resolveTarget(sender, args, 1);
        if (target == null) {
            return;
        }
        boolean connected = runtime.ws() != null && runtime.ws().isConnected(target.getUniqueId());
        int version = runtime.driver().clients().versionOf(target.getUniqueId());
        NamedTextColor color = connected && version > 0 ? NamedTextColor.GREEN : NamedTextColor.YELLOW;
        sender.sendMessage(Component.text(
                "WindUI 状态 " + target.getName()
                        + ": ws=" + (connected ? "connected" : "disconnected")
                        + ", protocol=" + version, color));
    }

    /** 回程兜底：客户端按钮 callback 优先走 WebSocket，连接不可用时退回本命令。 */
    private void handleEvent(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p) || args.length < 2) {
            return;
        }
        String eventId = args[1];
        List<String> data = args.length > 2
                ? List.copyOf(Arrays.asList(args).subList(2, args.length))
                : List.of();
        if (runtime.driver().event(adapter.hostFor(p), eventId, data)) {
            return;
        }
        adapter.onUnhandledEvent(p.getUniqueId(), eventId, data);
    }

    /** 客户端握手：{@code /windui hello <协议版本>}（客户端登录后自动上报，静默处理）。 */
    private void handleHello(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p) || args.length < 2) {
            return;
        }
        try {
            runtime.driver().clients().noteHello(p.getUniqueId(), Integer.parseInt(args[1]));
        } catch (NumberFormatException ignored) {
            // 非法版本号，忽略（保持基线 v0）
        }
    }

    /**
     * 解析目标玩家：{@code args[nameIndex]} 指定则用它，否则默认命令发送者本人。
     * 返回 null 表示已回错误消息、调用方应中止。
     */
    private Player resolveTarget(CommandSender sender, String[] args, int nameIndex) {
        if (args.length > nameIndex) {
            Player target = Bukkit.getPlayerExact(args[nameIndex]);
            if (target == null) {
                sender.sendMessage(Component.text("玩家不在线: " + args[nameIndex], NamedTextColor.RED));
            }
            return target;
        }
        if (sender instanceof Player p) {
            return p;
        }
        sender.sendMessage(Component.text("控制台需指定玩家", NamedTextColor.RED));
        return null;
    }

    private void usage(CommandSender sender) {
        sender.sendMessage(Component.text("用法:", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("  /windui open <界面> [玩家]", NamedTextColor.GRAY));
        sender.sendMessage(Component.text("  /windui hud [HUD|clear]", NamedTextColor.GRAY));
        sender.sendMessage(Component.text("  /windui status [玩家]", NamedTextColor.GRAY));
        sender.sendMessage(Component.text("  /windui reload", NamedTextColor.GRAY));
        sender.sendMessage(Component.text("  /windui list", NamedTextColor.GRAY));
    }

    /** 补全：与 Velocity 的 UiCommand 对齐（子命令 / open 的界面+玩家 / hud 的 id+clear）。 */
    public List<String> tabComplete(CommandSender sender, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length <= 1) {
            String prefix = args.length == 0 ? "" : args[0].toLowerCase();
            for (String sub : PUBLIC_SUBCOMMANDS) {
                if (sub.startsWith(prefix)) out.add(sub);
            }
            return out;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("open")) {
            addMatching(out, runtime.driver().ids(), args[1]);
            return out;
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("open")) {
            String prefix = args[2].toLowerCase();
            Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(name -> name.toLowerCase().startsWith(prefix))
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .forEach(out::add);
            return out;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("hud")) {
            if ("clear".startsWith(args[1].toLowerCase())) out.add("clear");
            addMatching(out, runtime.driver().huds().ids(), args[1]);
        }
        return out;
    }

    private static void addMatching(List<String> out, Iterable<String> candidates, String rawPrefix) {
        String prefix = rawPrefix == null ? "" : rawPrefix.toLowerCase();
        for (String candidate : candidates) {
            if (candidate.toLowerCase().startsWith(prefix)) out.add(candidate);
        }
    }
}
