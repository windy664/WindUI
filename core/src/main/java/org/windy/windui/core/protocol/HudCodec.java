package org.windy.windui.core.protocol;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
 */
public final class HudCodec {

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
     * 线格式损坏、非法 UTF-8 或非法 JSON 会抛异常，由 WebSocket 回调隔离层记录并跳过本条消息。
     */
    public static List<Panel> decode(byte[] data) {
        List<Panel> out = new ArrayList<>();
        JsonElement root = JsonParser.parseString(WireCodecSupport.unframeUtf8(data, "hud json"));
        if (!root.isJsonArray()) {
            return out;
        }

        for (JsonElement element : root.getAsJsonArray()) {
            if (!element.isJsonObject()) {
                continue;
            }

            JsonObject object = element.getAsJsonObject();
            if (!object.has("root") || !object.get("root").isJsonObject()) {
                continue;
            }

            Anchor anchor = Anchor.of(
                    object.has("anchor") && object.get("anchor").isJsonPrimitive()
                            ? object.get("anchor").getAsString()
                            : null);
            int x = object.has("x") && object.get("x").isJsonPrimitive()
                    ? object.get("x").getAsInt()
                    : 0;
            int y = object.has("y") && object.get("y").isJsonPrimitive()
                    ? object.get("y").getAsInt()
                    : 0;
            WidgetNode node = UIJsonParser.parse(object.getAsJsonObject("root").toString());
            out.add(new Panel(anchor, x, y, node));
        }
        return out;
    }
}
