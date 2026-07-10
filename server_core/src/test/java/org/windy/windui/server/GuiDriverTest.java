package org.windy.windui.server;

import org.junit.jupiter.api.Test;
import org.windy.windui.core.gui.GuiRegistry;
import org.windy.windui.core.gui.HudRegistry;
import org.windy.windui.core.parser.UIJsonParser;
import org.windy.windui.core.protocol.ControlCodec;
import org.windy.windui.core.protocol.PatchCodec;
import org.windy.windui.core.protocol.WindUIProtocol;
import org.windy.windui.server.asset.ImgStore;
import org.windy.windui.server.script.UiScriptService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuiDriverTest {

    /** 测试用 UiHost：占位符原样、捕获下发的 UI/图片/消息。 */
    private static final class FakeHost implements UiHost {
        final UUID id = UUID.randomUUID();
        String lastUi;
        final List<String> messages = new ArrayList<>();
        final List<byte[]> assets = new ArrayList<>();
        final List<byte[]> controls = new ArrayList<>();
        final List<byte[]> patches = new ArrayList<>();

        public UUID id() {
            return id;
        }

        public Map<String, Object> ctx() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("player", "Steve");
            m.put("online", 1);
            return m;
        }

        public String applyPlaceholders(String json) {
            return json;
        }

        public void sendUi(String json) {
            lastUi = json;
        }

        public void sendAsset(byte[] payload) {
            assets.add(payload);
        }

        public void sendMessage(String legacyText) {
            messages.add(legacyText);
        }

        public void sendControl(byte[] payload) {
            controls.add(payload);
        }

        public void sendPatch(byte[] payload) {
            patches.add(payload);
        }

        final List<byte[]> huds = new ArrayList<>();

        public void sendHud(byte[] payload) {
            huds.add(payload);
        }
    }

    private static GuiDriver driver(Path base) {
        GuiRegistry reg = new GuiRegistry(base.resolve("guis"));
        reg.load();
        UiScriptService scripts = new UiScriptService(base.resolve("scripts"));
        scripts.load();
        ImgStore img = new ImgStore(base.resolve("img"));
        img.init();
        HudRegistry huds = new HudRegistry(base.resolve("huds"));
        huds.load();
        return new GuiDriver(reg, scripts, img, huds);
    }

    @Test
    void openScriptReturnsJs() throws Exception {
        assertEquals("JS", driver(Files.createTempDirectory("w")).open(new FakeHost(), "shop"));
    }

    @Test
    void openJsonReturnsJson() throws Exception {
        assertEquals("JSON", driver(Files.createTempDirectory("w")).open(new FakeHost(), "menu_main"));
    }

    @Test
    void openMissingReturnsNull() throws Exception {
        assertNull(driver(Files.createTempDirectory("w")).open(new FakeHost(), "nope"));
    }

    @Test
    void eventRoutesToScriptAndAppliesEffects() throws Exception {
        GuiDriver d = driver(Files.createTempDirectory("w"));
        FakeHost h = new FakeHost();
        d.open(h, "shop");
        assertTrue(d.event(h, "buy_diamond", List.of("1")));
        assertTrue(h.messages.stream().anyMatch(m -> m.contains("钻石")));
    }

    @Test
    void serverEffectsGoOutOverControlChannel() throws Exception {
        Path base = Files.createTempDirectory("w");
        Files.createDirectories(base.resolve("scripts"));
        // 脚本：render 出一个空界面；onEvent 里发命令 + toast + 关屏（全走控制频道）
        Files.writeString(base.resolve("scripts").resolve("act.js"), """
                function render(ctx){ return { type:'TextView', props:{ text:'hi' } }; }
                function onEvent(ctx, event, data){
                  ctx.command('/spawn');
                  ctx.toast('已传送');
                  ctx.sound('minecraft:ui.button.click');
                  ctx.close();
                }
                """);

        GuiDriver d = driver(base);
        FakeHost h = new FakeHost();
        d.open(h, "act");
        assertTrue(d.event(h, "go", List.of()));

        assertEquals(1, h.controls.size(), "四个主动效果应攒成一个控制包下发");
        var actions = UIJsonParser.parseActions(ControlCodec.decodeJson(h.controls.get(0)));
        assertEquals(List.of("executeCommand", "showToast", "playSound", "closeScreen"),
                actions.stream().map(a -> a.get("action")).toList());
        assertEquals(List.of("/spawn"), actions.get(0).get("args"));
    }

    @Test
    void patchEffectGoesOutOverPatchChannel() throws Exception {
        Path base = Files.createTempDirectory("w");
        Files.createDirectories(base.resolve("scripts"));
        Files.writeString(base.resolve("scripts").resolve("p.js"), """
                function render(ctx){ return { type:'TextView', id:'status', props:{ text:'待选' } }; }
                function onEvent(ctx, event, data){
                  ctx.patch('status', { text: '已购买', textColor: '#66E0A3' });
                }
                """);

        GuiDriver d = driver(base);
        FakeHost h = new FakeHost();
        d.clients().noteHello(h.id(), WindUIProtocol.VERSION); // 客户端支持 patch
        d.open(h, "p");
        assertTrue(d.event(h, "go", List.of()));

        assertEquals(1, h.patches.size(), "一次事件的 patch 应攒成一个增量包");
        var list = PatchCodec.decode(h.patches.get(0));
        assertEquals(1, list.size());
        assertEquals("status", list.get(0).id());
        assertEquals("已购买", list.get(0).props().get("text"));
        assertEquals("#66E0A3", list.get(0).props().get("textColor"));
    }

    @Test
    void patchFallsBackToRerenderForLegacyClient() throws Exception {
        Path base = Files.createTempDirectory("w");
        Files.createDirectories(base.resolve("scripts"));
        Files.writeString(base.resolve("scripts").resolve("p.js"), """
                function render(ctx){ return { type:'TextView', id:'status', props:{ text:'待选' } }; }
                function onEvent(ctx, event, data){ ctx.patch('status', { text: '已购买' }); }
                """);

        GuiDriver d = driver(base);
        FakeHost h = new FakeHost();
        // 不上报 hello → 基线 v0 → 不支持 patch
        d.open(h, "p");
        h.patches.clear();
        h.lastUi = null;
        assertTrue(d.event(h, "go", List.of()));

        assertTrue(h.patches.isEmpty(), "老客户端不该收到 patch 包");
        assertTrue(h.lastUi != null && h.lastUi.contains("status"), "应退回整树重推(sendUi)");
    }

    @Test
    void eventOnJsonGuiNotHandledByScript() throws Exception {
        GuiDriver d = driver(Files.createTempDirectory("w"));
        FakeHost h = new FakeHost();
        d.open(h, "menu_main");
        assertFalse(d.event(h, "whatever", List.of()));
    }

    @Test
    void assetPushRewriteAndSessionDedup() throws Exception {
        Path base = Files.createTempDirectory("w");
        Files.createDirectories(base.resolve("img"));
        Files.write(base.resolve("img").resolve("b.png"), new byte[]{1, 2, 3, 4});
        Files.createDirectories(base.resolve("guis"));
        Files.writeString(base.resolve("guis").resolve("t.json"),
                "{\"type\":\"ImageView\",\"props\":{\"src\":\"img:b.png\"}}");

        GuiDriver d = driver(base);
        FakeHost h = new FakeHost();
        d.open(h, "t");
        assertEquals(1, h.assets.size(), "首次开界面应推一张图");
        assertTrue(h.lastUi.contains("asset:"), "img: 应被重写为 asset:hash");
        assertFalse(h.lastUi.contains("img:b.png"), "原 img: 引用应被替换");

        d.open(h, "t");
        assertEquals(1, h.assets.size(), "同一图第二次不该重推(会话去重)");
    }
}
