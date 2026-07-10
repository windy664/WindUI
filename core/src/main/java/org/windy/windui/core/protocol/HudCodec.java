package org.windy.windui.core.protocol;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.windy.windui.core.parser.UIJsonParser;
import org.windy.windui.core.parser.WidgetNode;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * HUD 频道（{@link WindUIProtocol#HUD_CHANNEL}）线格式编解码。两端共用。
 * <p>
 * 载荷 = 一组「锚定面板」的 JSON 数组:每个面板 = 屏幕锚点 + 偏移 + 一棵 SDUI 子树({@link WidgetNode})。
 * 客户端用 vanilla GuiGraphics 把每个面板的子树按锚点画到 HUD 层(非模态、不复用 ModernUI)。
 * <b>空数组 = 清空 HUD</b>。
 * <pre>
 * [ { "anchor":"top_left", "x":8, "y":8,
 *     "root": { "type":"column", ... SDUI 子树 ... } } ]
 * </pre>
 * 帧：{@code [jsonLen:4][json UTF-8]}(与 {@link UiPayloadCodec} 同结构,独立频道)。
 */
public final class HudCodec {

    private HudCodec() {
    }

    /** 面板锚点(相对屏幕)。 */
    public enum Anchor {
        TOP_LEFT, TOP_CENTER, TOP_RIGHT,
        CENTER_LEFT, CENTER, CENTER_RIGHT,
        BOTTOM_LEFT, BOTTOM_CENTER, BOTTOM_RIGHT;

        static Anchor of(String s) {
            if (s == null) return TOP_LEFT;
            try {
                return valueOf(s.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                return TOP_LEFT;
            }
        }
    }

    /**
     * 一个 HUD 面板。
     *
     * @param anchor 屏幕锚点
     * @param x      锚点起的水平偏移(dp,右锚点时向左为正由渲染器处理)
     * @param y      锚点起的垂直偏移(dp)
     * @param root   面板内容子树(column/text/markdown/bar/…)
     */
    public record Panel(Anchor anchor, int x, int y, WidgetNode root) {
    }

    /** 把已是 JSON 数组的 HUD spec 编码为可发送字节。 */
    public static byte[] encode(String json) {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream buf = new ByteArrayOutputStream(bytes.length + 4);
        try (DataOutputStream out = new DataOutputStream(buf)) {
            out.writeInt(bytes.length);
            out.write(bytes);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return buf.toByteArray();
    }

    /** 解出面板列表(客户端用)。空/非法返回空列表(= 清空 HUD)。 */
    public static List<Panel> decode(byte[] data) {
        List<Panel> out = new ArrayList<>();
        JsonElement root = JsonParser.parseString(unframe(data));
        if (!root.isJsonArray()) return out;
        for (JsonElement el : root.getAsJsonArray()) {
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            if (!o.has("root") || !o.get("root").isJsonObject()) continue;
            Anchor anchor = Anchor.of(o.has("anchor") ? o.get("anchor").getAsString() : null);
            int x = o.has("x") ? o.get("x").getAsInt() : 0;
            int y = o.has("y") ? o.get("y").getAsInt() : 0;
            WidgetNode node = UIJsonParser.parse(o.getAsJsonObject("root").toString());
            out.add(new Panel(anchor, x, y, node));
        }
        return out;
    }

    private static String unframe(byte[] data) {
        try (DataInputStream in = new DataInputStream(new java.io.ByteArrayInputStream(data))) {
            int len = in.readInt();
            if (len < 0 || len > data.length) {
                throw new IllegalArgumentException("bad jsonLen " + len);
            }
            byte[] json = new byte[len];
            in.readFully(json);
            return new String(json, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
