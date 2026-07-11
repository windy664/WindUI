package org.windy.windui.velocity;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.windy.windui.core.gui.GuiEventBus;
import org.windy.windui.server.GuiDriver;
import org.windy.windui.server.ws.WsServer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * {@code /windui open <界面> [玩家]} —— 向玩家推送 JSON 界面或 JS 脚本界面（脚本优先）。
 * {@code /windui reload}            —— 重载 guis/、scripts/、huds/ 与图片缓存。
 * {@code /windui list}              —— 列出已加载界面。
 * {@code /windui hud [id|clear]}    —— 推送或清空自己的 HUD。
 * {@code /windui event <id> [data]} —— 客户端按钮回调入口（回程通道，玩家触发）。
 * <p>
 * 薄 glue：平台无关的 open/event/效果分派全在 {@link GuiDriver}（server_core），本类只做参数解析、
 * 玩家解析与 Velocity 消息回显。
 */
public class UiCommand implements SimpleCommand {

    private static final List<String> PUBLIC_SUBCOMMANDS =
            List.of("open", "reload", "list", "hud", "status");

    private final ProxyServer server;
    private final GuiDriver driver;
    private final GuiEventBus<Player> eventBus;
    private final WsServer ws;

    public UiCommand(ProxyServer server, GuiDriver driver, GuiEventBus<Player> eventBus, WsServer ws) {
        this.server = server;
        this.driver = driver;
        this.eventBus = eventBus;
        this.ws = ws;
    }

    /** 构造一个绑定该玩家的 {@link VelocityUiHost}（下发走 WebSocket）。 */
    private VelocityUiHost host(Player p) {
        return new VelocityUiHost(p, server, ws);
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        String[] args = invocation.arguments();

        if (args.length == 0) {
            usage(source);
            return;
        }

        switch (args[0].toLowerCase()) {
            case "reload" -> {
                int n = driver.registry().load();
                int s = driver.scripts().load();
                int h = driver.huds().load();
                driver.images().clear();
                source.sendMessage(Component.text(
                        "已重载 " + n + " 界面 + " + s + " 脚本 + " + h + " HUD", NamedTextColor.GREEN));
            }
            case "list" -> {
                Set<String> all = driver.ids();
                source.sendMessage(Component.text(
                        all.isEmpty() ? "暂无界面（放 *.json 到 guis/ 或 *.js 到 scripts/）"
                                : "已加载界面: " + String.join(", ", all),
                        NamedTextColor.AQUA));
            }
            case "open" -> handleOpen(source, args);
            case "event" -> handleEvent(source, args);
            case "hello" -> handleHello(source, args);
            case "hud" -> handleHud(source, args);
            case "status" -> handleStatus(source, args);
            default -> usage(source);
        }
    }

    /** {@code /windui status [玩家]} —— 查看 WS 会话与客户端协议版本，供部署验收和排障。 */
    private void handleStatus(CommandSource source, String[] args) {
        Player target;
        if (args.length >= 2) {
            target = server.getPlayer(args[1]).orElse(null);
            if (target == null) {
                source.sendMessage(Component.text("玩家不在线: " + args[1], NamedTextColor.RED));
                return;
            }
        } else if (source instanceof Player player) {
            target = player;
        } else {
            source.sendMessage(Component.text(
                    "控制台需指定玩家: /windui status <玩家>", NamedTextColor.RED));
            return;
        }

        boolean connected = ws != null && ws.isConnected(target.getUniqueId());
        int protocolVersion = driver.clients().versionOf(target.getUniqueId());
        NamedTextColor color = connected && protocolVersion > 0
                ? NamedTextColor.GREEN
                : NamedTextColor.YELLOW;
        source.sendMessage(Component.text(
                "WindUI 状态 " + target.getUsername()
                        + ": ws=" + (connected ? "connected" : "disconnected")
                        + ", protocol=" + protocolVersion,
                color));
    }

    /** {@code /windui hud [<id>|clear]} —— 从 huds/ 推一个 HUD 到自己，默认 id 为 {@code demo}。 */
    private void handleHud(CommandSource source, String[] args) {
        if (!(source instanceof Player p)) {
            source.sendMessage(Component.text("只有玩家能接收 HUD", NamedTextColor.RED));
            return;
        }

        var host = host(p);
        if (args.length >= 2 && args[1].equalsIgnoreCase("clear")) {
            driver.hud(host, "[]");
            source.sendMessage(Component.text("已清空 HUD", NamedTextColor.GRAY));
            return;
        }

        String id = args.length >= 2 ? args[1] : "demo";
        String spec = driver.huds().get(id);
        if (spec == null) {
            source.sendMessage(Component.text(
                    "HUD 不存在: " + id + "（在 huds/ 放置 *.json）", NamedTextColor.RED));
            return;
        }

        driver.hud(host, spec);
        source.sendMessage(Component.text(
                "已推送 HUD " + id + "，使用 /windui hud clear 清空", NamedTextColor.GREEN));
    }

    /**
     * 客户端握手：{@code /windui hello <协议版本>}。客户端登录后自动上报，服务端据此门控特性
     * （前向兼容，见 {@link org.windy.windui.core.protocol.ProtocolFeatures}）。玩家触发，静默处理。
     */
    private void handleHello(CommandSource source, String[] args) {
        if (!(source instanceof Player p) || args.length < 2) {
            return;
        }
        try {
            driver.clients().noteHello(p.getUniqueId(), Integer.parseInt(args[1]));
        } catch (NumberFormatException ignored) {
            // 非法版本号，忽略（保持基线 v0）
        }
    }

    private void handleOpen(CommandSource source, String[] args) {
        if (args.length < 2) {
            source.sendMessage(Component.text("用法: /windui open <界面> [玩家]", NamedTextColor.RED));
            return;
        }
        String guiId = args[1];

        // 目标玩家：显式指定优先，否则默认命令发送者本人
        Player target;
        if (args.length >= 3) {
            target = server.getPlayer(args[2]).orElse(null);
            if (target == null) {
                source.sendMessage(Component.text("玩家不在线: " + args[2], NamedTextColor.RED));
                return;
            }
        } else if (source instanceof Player p) {
            target = p;
        } else {
            source.sendMessage(Component.text("控制台需指定玩家: /windui open <界面> <玩家>", NamedTextColor.RED));
            return;
        }

        String kind = driver.open(host(target), guiId);
        if (kind == null) {
            source.sendMessage(Component.text(
                    "界面不存在或脚本失败: " + guiId + "（用 /windui list 查看）", NamedTextColor.RED));
            return;
        }
        source.sendMessage(Component.text(
                "已向 " + target.getUsername() + " 推送界面 " + guiId + " (" + kind + ")", NamedTextColor.GREEN));
    }

    /**
     * 回程通道：客户端按钮 callback 优先走 WebSocket，连接不可用时退回本命令。
     * JS 脚本界面由 {@link GuiDriver} 路由到 onEvent；JSON 界面走结构化事件总线。
     */
    private void handleEvent(CommandSource source, String[] args) {
        if (!(source instanceof Player p)) {
            return;
        }
        if (args.length < 2) {
            return;
        }

        String eventId = args[1];
        List<String> data = args.length > 2
                ? List.copyOf(Arrays.asList(args).subList(2, args.length))
                : List.of();

        if (driver.event(host(p), eventId, data)) {
            return;
        }

        boolean handled = eventBus.dispatch(eventId, p, data);
        if (!handled) {
            p.sendMessage(Component.text(
                    "[WindUI] 收到事件: " + eventId + (data.isEmpty() ? "" : " " + String.join(" ", data)),
                    NamedTextColor.LIGHT_PURPLE));
        }
    }

    private void usage(CommandSource source) {
        source.sendMessage(Component.text("用法:", NamedTextColor.YELLOW));
        source.sendMessage(Component.text("  /windui open <界面> [玩家]", NamedTextColor.GRAY));
        source.sendMessage(Component.text("  /windui hud [HUD|clear]", NamedTextColor.GRAY));
        source.sendMessage(Component.text("  /windui status [玩家]", NamedTextColor.GRAY));
        source.sendMessage(Component.text("  /windui reload", NamedTextColor.GRAY));
        source.sendMessage(Component.text("  /windui list", NamedTextColor.GRAY));
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();
        List<String> out = new ArrayList<>();

        if (args.length == 0 || args.length == 1) {
            String prefix = args.length == 0 ? "" : args[0].toLowerCase();
            for (String subcommand : PUBLIC_SUBCOMMANDS) {
                if (subcommand.startsWith(prefix)) {
                    out.add(subcommand);
                }
            }
            return out;
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("open")) {
            addMatching(out, driver.ids(), args[1]);
            return out;
        }

        if (args.length == 3 && args[0].equalsIgnoreCase("open")) {
            String prefix = args[2].toLowerCase();
            server.getAllPlayers().stream()
                    .map(Player::getUsername)
                    .filter(name -> name.toLowerCase().startsWith(prefix))
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .forEach(out::add);
            return out;
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("hud")) {
            String prefix = args[1].toLowerCase();
            if ("clear".startsWith(prefix)) {
                out.add("clear");
            }
            addMatching(out, driver.huds().ids(), args[1]);
        }

        return out;
    }

    private static void addMatching(List<String> out, Iterable<String> candidates, String rawPrefix) {
        String prefix = rawPrefix == null ? "" : rawPrefix.toLowerCase();
        for (String candidate : candidates) {
            if (candidate.toLowerCase().startsWith(prefix)) {
                out.add(candidate);
            }
        }
    }
}
