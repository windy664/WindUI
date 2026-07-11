package org.windy.windui.server;

import org.windy.windui.core.gui.GuiRegistry;
import org.windy.windui.core.gui.HudRegistry;
import org.windy.windui.core.protocol.AssetCodec;
import org.windy.windui.core.protocol.ControlCodec;
import org.windy.windui.core.protocol.ControlCodec.CtrlAction;
import org.windy.windui.core.protocol.HudCodec;
import org.windy.windui.core.protocol.PatchCodec;
import org.windy.windui.core.protocol.ProtocolFeatures;
import org.windy.windui.server.asset.ImgStore;
import org.windy.windui.server.script.Effect;
import org.windy.windui.server.script.UiScriptService;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 平台无关的 GUI 驱动：统一 JSON 界面（{@link GuiRegistry}）与 JS 脚本界面（{@link UiScriptService}）的
 * 打开、事件路由与效果分派。平台只需实现 {@link UiHost}。
 * <p>
 * 打开时脚本优先（有 {@code scripts/<id>.js} 走 JS，否则 {@code guis/<id>.json}）；
 * 事件路由：玩家当前界面是脚本→派发到脚本 {@code onEvent} 并执行效果，否则返回 false 交调用方
 * （平台的 JSON 事件总线）处理。
 */
public final class GuiDriver {

    private final GuiRegistry registry;
    private final UiScriptService scripts;
    private final ImgStore images;
    private final HudRegistry huds;

    /** 界面里的图片引用 {@code img:<path>}。 */
    private static final Pattern IMG_REF = Pattern.compile("img:([^\"\\s]+)");

    /** 每个玩家当前打开的界面 id，用于把回调事件路由到对应脚本。 */
    private final Map<UUID, String> openScreens = new ConcurrentHashMap<>();

    /** 每个玩家本会话已下发过的图片 hash，避免重复推送（客户端已缓存）。 */
    private final Map<UUID, Set<String>> sentAssets = new ConcurrentHashMap<>();

    /** 客户端协议版本登记（前向兼容门控）。 */
    private final ClientRegistry clients = new ClientRegistry();

    public GuiDriver(GuiRegistry registry, UiScriptService scripts, ImgStore images, HudRegistry huds) {
        this.registry = registry;
        this.scripts = scripts;
        this.images = images;
        this.huds = huds;
    }

    public GuiRegistry registry() {
        return registry;
    }

    /** 文件驱动的 HUD 注册表（{@code /windui hud <id>} 按 id 取）。 */
    public HudRegistry huds() {
        return huds;
    }

    /** 客户端协议版本登记（平台的 {@code /windui hello} 写入、特性门控读取）。 */
    public ClientRegistry clients() {
        return clients;
    }

    /**
     * 推 HUD 面板给玩家（前向兼容门控：客户端不支持 HUD 则静默忽略）。
     *
     * @param panelsJson 面板 JSON 数组；空数组 {@code "[]"} = 清空 HUD
     */
    public void hud(UiHost host, String panelsJson) {
        if (!clients.supports(host.id(), ProtocolFeatures.HUD)) {
            return;
        }
        // 与 open() 同款：HUD 面板里的 img:<path> 也要经 asset 通道下发字节并重写成 asset:<hash>,
        // 否则 HUD 的 ImageView 拿不到图。会话内去重由 sentAssets 负责。
        panelsJson = pushAssetsAndRewrite(host, panelsJson);
        host.sendHud(HudCodec.encode(panelsJson));
    }

    public UiScriptService scripts() {
        return scripts;
    }

    /** 图片仓库：界面里的 {@code img:} 引用由此解析和下发。 */
    public ImgStore images() {
        return images;
    }

    /** JSON 界面 + JS 脚本的全部 id（有序去重）。 */
    public Set<String> ids() {
        Set<String> all = new TreeSet<>(registry.ids());
        all.addAll(scripts.ids());
        return all;
    }

    /**
     * 渲染并推送一个界面给玩家（JS 脚本优先，否则 JSON），记录为该玩家当前界面。
     *
     * @return 界面类型 {@code "JS"}/{@code "JSON"}；不存在或脚本失败返回 {@code null}
     */
    public String open(UiHost host, String guiId) {
        String json;
        String kind;
        if (scripts.has(guiId)) {
            json = scripts.render(guiId, host.ctx());
            if (json == null) {
                return null;
            }
            kind = "JS";
        } else {
            json = registry.get(guiId);
            if (json == null) {
                return null;
            }
            kind = "JSON";
        }

        json = host.applyPlaceholders(json);
        json = pushAssetsAndRewrite(host, json);
        host.sendUi(json);
        openScreens.put(host.id(), guiId);
        return kind;
    }

    /**
     * 扫描界面 JSON 里的 {@code img:<path>} 引用：经 {@link ImgStore} 解析为图片，在 asset 频道下发字节
     * （本会话未发过的才推），并把 {@code img:path} 重写为 {@code asset:<hash>}（客户端据 hash 取缓存图）。
     * 找不到文件的引用原样保留。
     */
    private String pushAssetsAndRewrite(UiHost host, String json) {
        if (images == null || !json.contains("img:")) {
            return json;
        }

        Set<String> sent = sentAssets.computeIfAbsent(
                host.id(), key -> ConcurrentHashMap.newKeySet());
        Matcher matcher = IMG_REF.matcher(json);
        StringBuilder output = new StringBuilder();

        while (matcher.find()) {
            AssetCodec.Asset asset = images.resolve(matcher.group(1));
            if (asset != null) {
                if (sent.add(asset.hash())) {
                    host.sendAsset(AssetCodec.encode(asset.hash(), asset.png()));
                }
                matcher.appendReplacement(
                        output,
                        Matcher.quoteReplacement("asset:" + asset.hash()));
            } else {
                matcher.appendReplacement(
                        output,
                        Matcher.quoteReplacement(matcher.group(0)));
            }
        }

        matcher.appendTail(output);
        return output.toString();
    }

    /**
     * 路由按钮回调事件。
     *
     * @return {@code true} 表示已由脚本处理；{@code false} 表示当前界面非脚本，调用方应自行处理（JSON 事件总线）
     */
    public boolean event(UiHost host, String eventId, List<String> rawData) {
        String current = openScreens.get(host.id());
        if (current == null || !scripts.has(current)) {
            return false;
        }

        // 拆出 readback 值（客户端以 __rb:<id>:<url-base64> 编码追加进 data，避免命令参数含空格）。
        List<String> data = new ArrayList<>();
        Map<String, String> values = new HashMap<>();
        for (String item : rawData) {
            if (item.startsWith("__rb:")) {
                String body = item.substring(5);
                int colon = body.indexOf(':');
                if (colon > 0) {
                    try {
                        String value = new String(
                                Base64.getUrlDecoder().decode(body.substring(colon + 1)),
                                StandardCharsets.UTF_8);
                        values.put(body.substring(0, colon), value);
                    } catch (IllegalArgumentException ignored) {
                        // 非法编码，忽略该项。
                    }
                }
            } else {
                data.add(item);
            }
        }

        List<Effect> effects =
                scripts.dispatchEvent(current, host.ctx(), eventId, data, values);
        if (effects == null) {
            host.sendMessage("§c[WindUI] 脚本事件处理失败: " + current);
            return true;
        }

        applyEffects(host, current, effects);
        return true;
    }

    /** 玩家断开时清理追踪（当前界面 + 已下发图片记录 + 客户端版本）。 */
    public void clear(UUID id) {
        openScreens.remove(id);
        sentAssets.remove(id);
        clients.clear(id);
    }

    /**
     * 按脚本产出顺序执行 {@code onEvent} 效果。
     * <p>
     * 为减少网络消息，同一频道上连续出现的效果会合并为一个消息；但一旦遇到其他频道或即时效果，
     * 会先刷新当前批次，再继续执行。这样既能保留批量发送的收益，也不会改变脚本声明的跨频道顺序。
     * <p>
     * 例如：
     * <pre>
     * ctx.close();
     * ctx.open("next");
     * </pre>
     * 会先发送 {@code closeScreen} 控制消息，再发送新 UI，而不是把 close 延迟到事件末尾。
     * <p>
     * WebSocket 对同一连接上的消息保证顺序。聊天消息与游戏内聊天通道不共享 WebSocket 顺序，
     * 但本方法仍会在执行聊天或打开界面前先完成待发送批次的写入。
     */
    private void applyEffects(UiHost host, String currentGui, List<Effect> effects) {
        PendingEffects pending = new PendingEffects();

        for (Effect effect : effects) {
            if (effect == null || effect.type() == null) {
                continue;
            }

            switch (effect.type()) {
                case "close" -> {
                    switchTo(host, currentGui, pending, PendingChannel.CONTROL);
                    pending.control.add(CtrlAction.of("closeScreen"));
                }
                case "toast" -> {
                    if (effect.arg() != null) {
                        switchTo(host, currentGui, pending, PendingChannel.CONTROL);
                        pending.control.add(CtrlAction.of("showToast", effect.arg()));
                    }
                }
                case "command" -> {
                    if (effect.arg() != null) {
                        switchTo(host, currentGui, pending, PendingChannel.CONTROL);
                        pending.control.add(CtrlAction.of("executeCommand", effect.arg()));
                    }
                }
                case "sound" -> {
                    if (effect.arg() != null) {
                        switchTo(host, currentGui, pending, PendingChannel.CONTROL);
                        pending.control.add(CtrlAction.of("playSound", effect.arg()));
                    }
                }
                case "patch" -> {
                    if (effect.arg() != null) {
                        switchTo(host, currentGui, pending, PendingChannel.PATCH);
                        pending.patches.add(effect.arg());
                    }
                }
                case "message" -> {
                    flushPending(host, currentGui, pending);
                    if (effect.arg() != null) {
                        host.sendMessage(effect.arg());
                    }
                }
                case "rerender" -> {
                    flushPending(host, currentGui, pending);
                    open(host, currentGui);
                }
                case "open" -> {
                    flushPending(host, currentGui, pending);
                    if (effect.arg() != null) {
                        open(host, effect.arg());
                    }
                }
                case "hud" -> {
                    flushPending(host, currentGui, pending);
                    if (effect.arg() != null) {
                        hud(host, effect.arg());
                    }
                }
                case "hudClear" -> {
                    flushPending(host, currentGui, pending);
                    hud(host, "[]");
                }
                case "give", "take" -> {
                    // 真库存操作：立即执行（服务端权威），host 无库存能力则静默跳过。
                    flushPending(host, currentGui, pending);
                    applyInventory(host, effect.type(), effect.arg());
                }
                default -> {
                    // 未知效果不能跨越并重排已有批次；先刷新，再忽略未知效果。
                    flushPending(host, currentGui, pending);
                }
            }
        }

        flushPending(host, currentGui, pending);
    }

    /**
     * 执行真库存效果 {@code give}/{@code take}。{@code arg} 格式 {@code "<itemId>\t<count>"}。
     * host 无库存能力（如纯代理 Velocity）或参数非法则静默跳过——脚本可移植，效果按平台能力降级。
     */
    private void applyInventory(UiHost host, String type, String arg) {
        InventoryOps inv = host.inventory();
        if (inv == null || arg == null) {
            return;
        }
        int tab = arg.indexOf('\t');
        String itemId = tab < 0 ? arg : arg.substring(0, tab);
        int count = 1;
        if (tab >= 0) {
            try {
                count = Integer.parseInt(arg.substring(tab + 1).trim());
            } catch (NumberFormatException ignored) {
                count = 1;
            }
        }
        if (count <= 0 || itemId.isBlank()) {
            return;
        }
        if ("give".equals(type)) {
            inv.give(itemId, count);
        } else {
            inv.take(itemId, count);
        }
    }

    /**
     * 切换待发送批次的频道。若频道发生变化，先发送上一频道已积累的内容。
     */
    private void switchTo(
            UiHost host,
            String currentGui,
            PendingEffects pending,
            PendingChannel channel) {
        if (pending.channel != PendingChannel.NONE
                && pending.channel != channel) {
            flushPending(host, currentGui, pending);
        }
        pending.channel = channel;
    }

    /**
     * 发送当前连续批次并清空缓冲。
     * <p>
     * Patch 对老客户端的整树重推就在 Patch 原本出现的位置执行，因此后续 control/open/HUD 不会越过它。
     */
    private void flushPending(
            UiHost host,
            String currentGui,
            PendingEffects pending) {
        switch (pending.channel) {
            case CONTROL -> {
                if (!pending.control.isEmpty()) {
                    host.sendControl(ControlCodec.encode(pending.control));
                }
            }
            case PATCH -> {
                if (!pending.patches.isEmpty()) {
                    if (clients.supports(host.id(), ProtocolFeatures.PATCH)) {
                        host.sendPatch(PatchCodec.encode(
                                "[" + String.join(",", pending.patches) + "]"));
                    } else {
                        open(host, currentGui);
                    }
                }
            }
            case NONE -> {
                // 没有待发送内容。
            }
        }

        pending.control.clear();
        pending.patches.clear();
        pending.channel = PendingChannel.NONE;
    }

    /** 可批量下发的频道类型。 */
    private enum PendingChannel {
        NONE,
        CONTROL,
        PATCH
    }

    /** 一次脚本事件执行期间的连续频道批次。 */
    private static final class PendingEffects {
        private PendingChannel channel = PendingChannel.NONE;
        private final List<CtrlAction> control = new ArrayList<>();
        private final List<String> patches = new ArrayList<>();
    }
}
