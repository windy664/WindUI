package org.windy.windui.bukkit;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.windy.windui.server.UiHost;
import org.windy.windui.server.WindUiRuntime;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * {@code /windui} —— legacy Bukkit 命令（{@link CommandExecutor} + {@link TabCompleter}，String/ChatColor 消息）。
 * 子命令：open / hud / list / reload / status / event / hello。逻辑与 Paper 版一致，只是消息用 § 色码。
 */
public final class BukkitCommand implements CommandExecutor, TabCompleter {

    private static final List<String> PUBLIC_SUBCOMMANDS = List.of("open", "reload", "list", "hud", "status");

    private final WindUiRuntime runtime;
    private final BukkitPlatformAdapter adapter;

    public BukkitCommand(WindUiRuntime runtime, BukkitPlatformAdapter adapter) {
        this.runtime = runtime;
        this.adapter = adapter;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            usage(sender);
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "reload" -> {
                int n = runtime.driver().registry().load();
                int s = runtime.driver().scripts().load();
                int h = runtime.driver().huds().load();
                runtime.driver().images().clear();
                sender.sendMessage(ChatColor.GREEN + "已重载 " + n + " 界面 + " + s + " 脚本 + " + h + " HUD");
            }
            case "list" -> {
                Set<String> all = runtime.driver().ids();
                sender.sendMessage(ChatColor.AQUA + (all.isEmpty()
                        ? "暂无界面（放 *.json 到 guis/ 或 *.js 到 scripts/）"
                        : "已加载界面: " + String.join(", ", all)));
            }
            case "open" -> handleOpen(sender, args);
            case "hud" -> handleHud(sender, args);
            case "status" -> handleStatus(sender, args);
            case "event" -> handleEvent(sender, args);
            case "hello" -> handleHello(sender, args);
            default -> usage(sender);
        }
        return true;
    }

    private void handleOpen(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "用法: /windui open <界面> [玩家]");
            return;
        }
        Player target = resolveTarget(sender, args, 2);
        if (target == null) {
            return;
        }
        String kind = runtime.driver().open(adapter.hostFor(target), args[1]);
        if (kind == null) {
            sender.sendMessage(ChatColor.RED + "界面不存在或脚本失败: " + args[1] + "（用 /windui list 查看）");
            return;
        }
        sender.sendMessage(ChatColor.GREEN + "已向 " + target.getName() + " 推送界面 " + args[1] + " (" + kind + ")");
    }

    private void handleHud(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(ChatColor.RED + "只有玩家能接收 HUD");
            return;
        }
        UiHost host = adapter.hostFor(p);
        if (args.length >= 2 && args[1].equalsIgnoreCase("clear")) {
            runtime.driver().hud(host, "[]");
            sender.sendMessage(ChatColor.GRAY + "已清空 HUD");
            return;
        }
        String id = args.length >= 2 ? args[1] : "demo";
        String spec = runtime.driver().huds().get(id);
        if (spec == null) {
            sender.sendMessage(ChatColor.RED + "HUD 不存在: " + id + "（在 huds/ 放置 *.json）");
            return;
        }
        runtime.driver().hud(host, spec);
        sender.sendMessage(ChatColor.GREEN + "已推送 HUD " + id + "，使用 /windui hud clear 清空");
    }

    private void handleStatus(CommandSender sender, String[] args) {
        Player target = resolveTarget(sender, args, 1);
        if (target == null) {
            return;
        }
        boolean connected = runtime.ws() != null && runtime.ws().isConnected(target.getUniqueId());
        int version = runtime.driver().clients().versionOf(target.getUniqueId());
        ChatColor color = connected && version > 0 ? ChatColor.GREEN : ChatColor.YELLOW;
        sender.sendMessage(color + "WindUI 状态 " + target.getName()
                + ": ws=" + (connected ? "connected" : "disconnected") + ", protocol=" + version);
    }

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

    private void handleHello(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p) || args.length < 2) {
            return;
        }
        try {
            runtime.driver().clients().noteHello(p.getUniqueId(), Integer.parseInt(args[1]));
        } catch (NumberFormatException ignored) {
        }
    }

    private Player resolveTarget(CommandSender sender, String[] args, int nameIndex) {
        if (args.length > nameIndex) {
            Player target = Bukkit.getPlayerExact(args[nameIndex]);
            if (target == null) {
                sender.sendMessage(ChatColor.RED + "玩家不在线: " + args[nameIndex]);
            }
            return target;
        }
        if (sender instanceof Player p) {
            return p;
        }
        sender.sendMessage(ChatColor.RED + "控制台需指定玩家");
        return null;
    }

    private void usage(CommandSender sender) {
        sender.sendMessage(ChatColor.YELLOW + "用法:");
        sender.sendMessage(ChatColor.GRAY + "  /windui open <界面> [玩家]");
        sender.sendMessage(ChatColor.GRAY + "  /windui hud [HUD|clear]");
        sender.sendMessage(ChatColor.GRAY + "  /windui status [玩家]");
        sender.sendMessage(ChatColor.GRAY + "  /windui reload");
        sender.sendMessage(ChatColor.GRAY + "  /windui list");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
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
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase().startsWith(prefix)) out.add(p.getName());
            }
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
