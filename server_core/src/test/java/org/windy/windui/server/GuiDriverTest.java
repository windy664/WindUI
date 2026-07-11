package org.windy.windui.server;

import org.junit.jupiter.api.Test;
import org.windy.windui.core.gui.GuiRegistry;
import org.windy.windui.core.gui.HudRegistry;
import org.windy.windui.core.parser.UIJsonParser;
import org.windy.windui.core.protocol.ControlCodec;
import org.windy.windui.core.protocol.HudCodec;
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

    /** 测试用 UiHost：占位符原样、捕获下发的 UI/图片/消息及跨频道调用顺序。 */
    private static final class FakeHost implements UiHost {
        final UUID id = UUID.randomUUID();
        String lastUi;
        final List<String> messages = new ArrayList<>();
        final List<byte[]> assets = new ArrayList<>();
        final List<byte[]> controls = new ArrayList<>();
        final List<byte[]> patches = new ArrayList<>();
        final List<byte[]> huds = new ArrayList<>();
        final List<String> timeline = new ArrayList<>();
        InventoryOps inv; // 默认 null（无库存，如代理）；测试真库存时注入

        @Override
        public UUID id() {
            return id;
        }

        @Override
        public InventoryOps inventory() {
            return inv;
        }

        @Override
        public Map<String, Object> ctx() {
            Map<String, Object> context = new LinkedHashMap<>();
            context.put("player", "Steve");
            context.put("online", 1);
            return context;
        }

        @Override
        public String applyPlaceholders(String json) {
            return json;
        }

        @Override
        public void sendUi(String json) {
            lastUi = json;
            timeline.add("ui");
        }

        @Override
        public void sendAsset(byte[] payload) {
            assets.add(payload);
            timeline.add("asset");
        }

        @Override
        public void sendMessage(String legacyText) {
            messages.add(legacyText);
            timeline.add("message");
        }

        @Override
        public void sendControl(byte[] payload) {
            controls.add(payload);
            timeline.add("control");
        }

        @Override
        public void sendPatch(byte[] payload) {
            patches.add(payload);
            timeline.add("patch");
        }

        @Override
        public void sendHud(byte[] payload) {
            huds.add(payload);
            timeline.add("hud");
        }
    }

    private static GuiDriver driver(Path base) {
        GuiRegistry registry = new GuiRegistry(base.resolve("guis"));
        registry.load();

        UiScriptService scripts = new UiScriptService(base.resolve("scripts"));
        scripts.load();

        ImgStore images = new ImgStore(base.resolve("img"));
        images.init();

        HudRegistry huds = new HudRegistry(base.resolve("huds"));
        huds.load();

        return new GuiDriver(registry, scripts, images, huds);
    }

    @Test
    void openScriptReturnsJs() throws Exception {
        assertEquals(
                "JS",
                driver(Files.createTempDirectory("w"))
                        .open(new FakeHost(), "shop"));
    }

    @Test
    void openJsonReturnsJson() throws Exception {
        assertEquals(
                "JSON",
                driver(Files.createTempDirectory("w"))
                        .open(new FakeHost(), "menu_main"));
    }

    @Test
    void openMissingReturnsNull() throws Exception {
        assertNull(
                driver(Files.createTempDirectory("w"))
                        .open(new FakeHost(), "nope"));
    }

    @Test
    void eventRoutesToScriptAndAppliesEffects() throws Exception {
        GuiDriver driver = driver(Files.createTempDirectory("w"));
        FakeHost host = new FakeHost();

        driver.open(host, "shop");
        assertTrue(driver.event(host, "buy_diamond", List.of("1")));
        assertTrue(host.messages.stream().anyMatch(message -> message.contains("钻石")));
    }

    @Test
    void serverEffectsGoOutOverControlChannel() throws Exception {
        Path base = Files.createTempDirectory("w");
        Files.createDirectories(base.resolve("scripts"));

        Files.writeString(base.resolve("scripts").resolve("act.js"), """
                function render(ctx){ return { type:'TextView', props:{ text:'hi' } }; }
                function onEvent(ctx, event, data){
                  ctx.command('/spawn');
                  ctx.toast('已传送');
                  ctx.sound('minecraft:ui.button.click');
                  ctx.close();
                }
                """);

        GuiDriver driver = driver(base);
        FakeHost host = new FakeHost();

        driver.open(host, "act");
        assertTrue(driver.event(host, "go", List.of()));

        assertEquals(1, host.controls.size(), "连续的四个控制效果应合并成一个控制包");
        var actions =
                UIJsonParser.parseActions(ControlCodec.decodeJson(host.controls.get(0)));
        assertEquals(
                List.of(
                        "executeCommand",
                        "showToast",
                        "playSound",
                        "closeScreen"),
                actions.stream().map(action -> action.get("action")).toList());
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

        GuiDriver driver = driver(base);
        FakeHost host = new FakeHost();

        driver.clients().noteHello(host.id(), WindUIProtocol.VERSION);
        driver.open(host, "p");
        assertTrue(driver.event(host, "go", List.of()));

        assertEquals(1, host.patches.size(), "一次事件的连续 patch 应合并成一个增量包");
        var list = PatchCodec.decode(host.patches.get(0));
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

        GuiDriver driver = driver(base);
        FakeHost host = new FakeHost();

        // 不上报 hello → 基线 v0 → 不支持 patch。
        driver.open(host, "p");
        host.patches.clear();
        host.lastUi = null;

        assertTrue(driver.event(host, "go", List.of()));
        assertTrue(host.patches.isEmpty(), "老客户端不该收到 patch 包");
        assertTrue(
                host.lastUi != null && host.lastUi.contains("status"),
                "老客户端应在 patch 原位置退回整树重推");
    }

    @Test
    void crossChannelEffectsPreserveScriptOrder() throws Exception {
        Path base = Files.createTempDirectory("w");
        Files.createDirectories(base.resolve("scripts"));

        Files.writeString(base.resolve("scripts").resolve("ordered.js"), """
                function render(ctx){
                  return { type:'TextView', id:'status', props:{ text:'ordered' } };
                }
                function onEvent(ctx, event, data){
                  ctx.close();
                  ctx.rerender();
                  ctx.patch('status', { text: 'patched' });
                  ctx.toast('after patch');
                  ctx.hud([]);
                  ctx.sound('minecraft:ui.button.click');
                }
                """);

        GuiDriver driver = driver(base);
        FakeHost host = new FakeHost();

        driver.clients().noteHello(host.id(), WindUIProtocol.VERSION);
        driver.open(host, "ordered");
        host.timeline.clear();

        assertTrue(driver.event(host, "go", List.of()));

        assertEquals(
                List.of("control", "ui", "patch", "control", "hud", "control"),
                host.timeline,
                "跨频道效果必须严格保持脚本声明顺序");

        assertEquals(3, host.controls.size());
        assertEquals(1, host.patches.size());
        assertEquals(1, host.huds.size());

        var firstControl =
                UIJsonParser.parseActions(
                        ControlCodec.decodeJson(host.controls.get(0)));
        assertEquals("closeScreen", firstControl.get(0).get("action"));

        var secondControl =
                UIJsonParser.parseActions(
                        ControlCodec.decodeJson(host.controls.get(1)));
        assertEquals("showToast", secondControl.get(0).get("action"));

        var thirdControl =
                UIJsonParser.parseActions(
                        ControlCodec.decodeJson(host.controls.get(2)));
        assertEquals("playSound", thirdControl.get(0).get("action"));
    }

    @Test
    void consecutiveControlAndPatchBatchesRemainCompact() throws Exception {
        Path base = Files.createTempDirectory("w");
        Files.createDirectories(base.resolve("scripts"));

        Files.writeString(base.resolve("scripts").resolve("batches.js"), """
                function render(ctx){
                  return {
                    type:'LinearLayout',
                    children:[
                      { type:'TextView', id:'a', props:{ text:'a' } },
                      { type:'TextView', id:'b', props:{ text:'b' } }
                    ]
                  };
                }
                function onEvent(ctx, event, data){
                  ctx.toast('one');
                  ctx.sound('minecraft:ui.button.click');
                  ctx.patch('a', { text: 'A' });
                  ctx.patch('b', { text: 'B' });
                  ctx.close();
                }
                """);

        GuiDriver driver = driver(base);
        FakeHost host = new FakeHost();

        driver.clients().noteHello(host.id(), WindUIProtocol.VERSION);
        driver.open(host, "batches");
        host.timeline.clear();

        assertTrue(driver.event(host, "go", List.of()));

        assertEquals(
                List.of("control", "patch", "control"),
                host.timeline);
        assertEquals(2, host.controls.size(), "被 patch 分隔的控制效果应分成两个包");
        assertEquals(1, host.patches.size(), "连续 patch 仍应合并成一个包");

        var patches = PatchCodec.decode(host.patches.get(0));
        assertEquals(2, patches.size());
        assertEquals(List.of("a", "b"), patches.stream().map(PatchCodec.Patch::id).toList());
    }

    @Test
    void legacyPatchFallbackKeepsFollowingControlAfterRerender() throws Exception {
        Path base = Files.createTempDirectory("w");
        Files.createDirectories(base.resolve("scripts"));

        Files.writeString(base.resolve("scripts").resolve("legacy-order.js"), """
                function render(ctx){
                  return { type:'TextView', id:'status', props:{ text:'old' } };
                }
                function onEvent(ctx, event, data){
                  ctx.patch('status', { text: 'new' });
                  ctx.close();
                }
                """);

        GuiDriver driver = driver(base);
        FakeHost host = new FakeHost();

        // 不登记协议版本，模拟不支持 patch 的老客户端。
        driver.open(host, "legacy-order");
        host.timeline.clear();

        assertTrue(driver.event(host, "go", List.of()));

        assertEquals(
                List.of("ui", "control"),
                host.timeline,
                "老客户端的 patch 整树降级必须发生在后续 close 之前");
        assertTrue(host.patches.isEmpty());
    }

    @Test
    void eventOnJsonGuiNotHandledByScript() throws Exception {
        GuiDriver driver = driver(Files.createTempDirectory("w"));
        FakeHost host = new FakeHost();

        driver.open(host, "menu_main");
        assertFalse(driver.event(host, "whatever", List.of()));
    }

    @Test
    void assetPushRewriteAndSessionDedup() throws Exception {
        Path base = Files.createTempDirectory("w");
        Files.createDirectories(base.resolve("img"));
        Files.write(
                base.resolve("img").resolve("b.png"),
                new byte[]{1, 2, 3, 4});

        Files.createDirectories(base.resolve("guis"));
        Files.writeString(
                base.resolve("guis").resolve("t.json"),
                "{\"type\":\"ImageView\",\"props\":{\"src\":\"img:b.png\"}}");

        GuiDriver driver = driver(base);
        FakeHost host = new FakeHost();

        driver.open(host, "t");
        assertEquals(1, host.assets.size(), "首次开界面应推一张图");
        assertTrue(host.lastUi.contains("asset:"), "img: 应被重写为 asset:hash");
        assertFalse(host.lastUi.contains("img:b.png"), "原 img: 引用应被替换");

        driver.open(host, "t");
        assertEquals(1, host.assets.size(), "同一图第二次不该重推（会话去重）");
    }

    @Test
    void hudPushesAssetsAndRewrites() throws Exception {
        Path base = Files.createTempDirectory("w");
        Files.createDirectories(base.resolve("img"));
        Files.write(base.resolve("img").resolve("logo.png"), new byte[]{9, 8, 7, 6});

        GuiDriver driver = driver(base);
        FakeHost host = new FakeHost();
        driver.clients().noteHello(host.id(), WindUIProtocol.VERSION); // HUD 特性需协议 v3

        String panels = "[{\"anchor\":\"top_left\",\"root\":"
                + "{\"type\":\"ImageView\",\"props\":{\"src\":\"img:logo.png\",\"width\":128,\"height\":128}}}]";
        driver.hud(host, panels);

        assertEquals(1, host.assets.size(), "HUD 里的 img: 也应经 asset 通道推一张图");
        assertEquals(1, host.huds.size(), "应下发一帧 HUD");

        // 下发的 HUD 面板里 img: 应已被重写成 asset:hash（与 open() 同款）。
        HudCodec.Panel panel = HudCodec.decode(host.huds.get(0)).get(0);
        String src = panel.root().getString("src", "");
        assertTrue(src.startsWith("asset:"), "HUD 的 img: 应被重写为 asset:hash，实际=" + src);
    }

    @Test
    void hudSkippedForLegacyClient() throws Exception {
        GuiDriver driver = driver(Files.createTempDirectory("w"));
        FakeHost host = new FakeHost(); // 未 noteHello → 视为 v0，不支持 HUD 特性
        driver.hud(host, "[]");
        assertTrue(host.huds.isEmpty(), "旧客户端不应收到 HUD 帧");
    }

    /** 记录 give/take 调用的 InventoryOps。 */
    private static final class RecordingInventory implements InventoryOps {
        String givenId;
        int givenCount;
        String takenId;
        int takenCount;

        @Override public java.util.List<ItemView> snapshot() {
            return java.util.List.of();
        }

        @Override public boolean give(String itemId, int count) {
            givenId = itemId;
            givenCount = count;
            return true;
        }

        @Override public int take(String itemId, int count) {
            takenId = itemId;
            takenCount = count;
            return count;
        }
    }

    @Test
    void giveTakeEffectsReachHostInventory() throws Exception {
        Path base = Files.createTempDirectory("w");
        Files.createDirectories(base.resolve("scripts"));
        Files.writeString(base.resolve("scripts").resolve("inv.js"),
                "function render(ctx){return {type:'TextView',props:{text:'inv'}};}\n"
                        + "function onEvent(ctx,event,data){\n"
                        + "  if(event==='give') ctx.give(data[0], 3);\n"
                        + "  else if(event==='take') ctx.take(data[0], 2);\n"
                        + "}");
        GuiDriver driver = driver(base);
        RecordingInventory inv = new RecordingInventory();
        FakeHost host = new FakeHost();
        host.inv = inv;

        driver.open(host, "inv");
        assertTrue(driver.event(host, "give", List.of("minecraft:diamond")));
        assertEquals("minecraft:diamond", inv.givenId);
        assertEquals(3, inv.givenCount);

        assertTrue(driver.event(host, "take", List.of("minecraft:dirt")));
        assertEquals("minecraft:dirt", inv.takenId);
        assertEquals(2, inv.takenCount);
    }

    @Test
    void inventoryEffectsNoOpWhenHostHasNoInventory() throws Exception {
        Path base = Files.createTempDirectory("w");
        Files.createDirectories(base.resolve("scripts"));
        Files.writeString(base.resolve("scripts").resolve("inv.js"),
                "function render(ctx){return {type:'TextView',props:{text:'inv'}};}\n"
                        + "function onEvent(ctx,event,data){ ctx.give('minecraft:diamond', 1); }");
        GuiDriver driver = driver(base);
        FakeHost host = new FakeHost(); // inv 为 null（代理型 host，无库存）
        driver.open(host, "inv");
        // 不应抛异常，静默跳过
        assertTrue(driver.event(host, "give", List.of()));
    }
}
