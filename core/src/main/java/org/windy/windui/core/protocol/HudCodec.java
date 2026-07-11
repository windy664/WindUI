package org.windy.windui.core.protocol;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import org.windy.windui.core.parser.UIJsonParser;
import org.windy.windui.core.parser.WidgetNode;

import java.util.ArrayList;
import java.util.List;

/**
 * HUD 频道（{@link WindUIProtocol#HUD_CHANNEL}）线格式编解码。两端共用。
 * <p>
 * 载荷 = 一组「锚定面板」的 JSON 数组：每个面板 = 屏幕锚点 + 偏移 + 一棵 SDUI 子树（{@link WidgetNode}）。
 * 客户端用 vanilla GuiGraphics 把每个面板的子树按锚点画到 HUD 层（非模态、不复用 ModernUI）。
 * <b>空数组 = 清空 HUD</b>。
 * <pre>
 * [ { "anchor":"top_left", "x":8, "y":8,
 *     "root": { "type":"LinearLayout", ... SDUI 子树 ... } } ]
 * </pre>
 * 帧：{@code [jsonLen:4][json UTF-8]}（与 {@link UiPayloadCodec} 同结构，独立频道）。
 * 解码时严格限制面板数量并校验字段类型；面板内部组件树继续复用 {@link UIJsonParser} 的深度、
 * 节点数量和属性资源预算。
 */
public final class HudCodec {

    /** 一条 HUD 消息最多包含的面板数量。 */
    private static final int MAX_PANELS = 64;

    private HudCodec() {
    }

    /** 面板锚点（相对屏幕）。 */
    public enum Anchor {
        TOP_LEFT, TOP_CENTER, TOP_RIGHT,
        CENTER_LEFT, CENTER, CENTER_RIGHT,
        BOTTOM_LEFT, BOTTOM_CENTER, BOTTOM_RIGHT;

        static Anchor of(String value) {
            if (value == null) {
                return TOP_LEFT;
            }
            try {
                return valueOf(value.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                return TOP_LEFT;
            }
        }
    }

    /**
     * 一个 HUD 面板。
     *
     * @param anchor 屏幕锚点
     * @param x      锚点起的水平偏移（右锚点时由渲染器解释为距右侧距离）
     * @param y      锚点起的垂直偏移
     * @param root   面板内容子树
     */
    public record Panel(Anchor anchor, int x, int y, WidgetNode root) {
        public Panel {
            anchor = anchor == null ? Anchor.TOP_LEFT : anchor;
            if (root == null) {
                throw new IllegalArgumentException("HUD panel root 不能为空");
            }
        }
    }

    /** 把已是 JSON 数组的 HUD spec 编码为可发送字节。 */
    public static byte[] encode(String json) {
        return WireCodecSupport.frameUtf8(json, "hud json");
    }

    /**
     * 解出面板列表（客户端用）。空数组表示清空 HUD。
     * 线格式损坏、非法 UTF-8、非法 JSON、错误字段类型或超过业务边界时抛异常，
     * 由 WebSocket 回调隔离层记录并跳过整条消息。
     */
    public static List<Panel> decode(byte[] data) {
        JsonElement root = JsonParser.parseString(
                WireCodecSupport.unframeUtf8(data, "hud json"));
        if (!root.isJsonArray()) {
            throw new JsonParseException("HUD 根节点必须是面板数组");
        }

        var array = root.getAsJsonArray();
        if (array.size() > MAX_PANELS) {
            throw new JsonParseException(
                    "HUD 面板过多: " + array.size() + "，上限 " + MAX_PANELS);
        }

        List<Panel> out = new ArrayList<>(array.size());
        for (int index = 0; index < array.size(); index++) {
            out.add(parsePanel(array.get(index), index));
        }
        return List.copyOf(out);
    }

    private static Panel parsePanel(JsonElement element, int index) {
        if (!element.isJsonObject()) {
            throw new JsonParseException("HUD panel[" + index + "] 必须是对象");
        }

        JsonObject object = element.getAsJsonObject();
        Anchor anchor = parseAnchor(object.get("anchor"), index);
        int x = parseCoordinate(object.get("x"), "x", index);
        int y = parseCoordinate(object.get("y"), "y", index);

        JsonElement root = object.get("root");
        if (root == null || !root.isJsonObject()) {
            throw new JsonParseException(
                    "HUD panel[" + index + "].root 必须是组件对象");
        }

        WidgetNode node = UIJsonParser.parse(root.toString());
        return new Panel(anchor, x, y, node);
    }

    private static Anchor parseAnchor(JsonElement element, int index) {
        if (element == null || element.isJsonNull()) {
            return Anchor.TOP_LEFT;
        }
        if (!element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isString()) {
            throw new JsonParseException(
                    "HUD panel[" + index + "].anchor 必须是字符串");
        }
        return Anchor.of(element.getAsString());
    }

    private static int parseCoordinate(
            JsonElement element,
            String field,
            int index) {
        if (element == null || element.isJsonNull()) {
            return 0;
        }
        if (!element.isJsonPrimitive()) {
            throw coordinateError(field, index);
        }

        JsonPrimitive primitive = element.getAsJsonPrimitive();
        if (!primitive.isNumber()) {
            throw coordinateError(field, index);
        }

        Number number = primitive.getAsNumber();
        double value = number.doubleValue();
        if (!Double.isFinite(value)
                || value < Integer.MIN_VALUE
                || value > Integer.MAX_VALUE
                || value != Math.rint(value)) {
            throw coordinateError(field, index);
        }
        return (int) value;
    }

    private static JsonParseException coordinateError(
            String field,
            int index) {
        return new JsonParseException(
                "HUD panel[" + index + "]." + field
                        + " 必须是 32 位整数");
    }
}
