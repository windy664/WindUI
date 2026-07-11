package org.windy.windui.server.script;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UiScriptServiceTest {

    private static Map<String, Object> ctx() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("player", "Steve");
        m.put("online", 7);
        return m;
    }

    private static UiScriptService loaded() throws Exception {
        UiScriptService svc = new UiScriptService(Files.createTempDirectory("windui-scripts"));
        svc.load();
        return svc;
    }

    @Test
    void loadsBundledDefaultsFromJar() throws Exception {
        UiScriptService svc = loaded();
        assertTrue(svc.ids().contains("shop"));
        assertTrue(svc.ids().contains("showcase"));
    }

    @Test
    void renderProducesComponentsAndInterpolatesCtx() throws Exception {
        String json = loaded().render("shop", ctx());
        assertNotNull(json);
        assertTrue(json.contains("Button"), "shop 应含按钮");
        assertTrue(json.contains("Steve"), "ctx.player 应被拼进去");
        assertTrue(json.contains("#007AFF"), "默认示例应使用 iOS 系统蓝");
        assertTrue(json.contains("\"overlayColor\":\"#66000000\""), "默认示例应使用中性遮罩");
    }

    @Test
    void showcaseHasRichComponents() throws Exception {
        String json = loaded().render("showcase", ctx());
        assertNotNull(json);
        assertTrue(json.contains("ItemSlot"), "showcase 应含物品格");
        assertTrue(json.contains("Markdown"), "showcase 应含 markdown");
        assertTrue(json.contains("TextBox"), "showcase 应含输入框");
        assertTrue(json.contains("Bar"), "showcase 应含进度条");
        assertTrue(json.contains("Divider"), "showcase 应含分隔线");
        assertTrue(json.contains("\"type\":\"entity\""), "showcase 应含 vanilla entity Model3D");
        assertTrue(json.contains("minecraft:zombie"), "entity Model3D 应使用可用的原版实体 id");
        assertTrue(json.contains("#F5FFFFFF"), "showcase 应使用 iOS 半透明白卡");
        assertTrue(json.contains("\"overlayColor\":\"#66000000\""), "showcase 应覆盖默认深蓝背景");
    }

    @Test
    void eventProducesEffects() throws Exception {
        List<Effect> fx = loaded().dispatchEvent("shop", ctx(), "buy_diamond", List.of("2"), Map.of());
        assertNotNull(fx);
        assertTrue(fx.stream().anyMatch(e -> "message".equals(e.type()) && e.arg().contains("2")));
    }

    @Test
    void valueReadbackReachesScriptViaCtxValues() throws Exception {
        List<Effect> fx = loaded().dispatchEvent(
                "showcase", ctx(), "search", List.of(), Map.of("query", "钻石剑"));
        assertNotNull(fx);
        assertTrue(fx.stream().anyMatch(e -> e.arg() != null && e.arg().contains("钻石剑")));
    }

    @Test
    void diskScriptOverridesBundled() throws Exception {
        Path dir = Files.createTempDirectory("windui-scripts");
        Files.writeString(dir.resolve("shop.js"),
                "function render(ctx){ return { type: 'TextView', props: { text: 'CUSTOM' } }; }");
        UiScriptService svc = new UiScriptService(dir);
        svc.load();
        assertTrue(svc.render("shop", ctx()).contains("CUSTOM"));
    }

    @Test
    void runawayScriptTimesOutGracefully() throws Exception {
        Path dir = Files.createTempDirectory("windui-scripts");
        Files.writeString(dir.resolve("evil.js"), "function render(ctx){ while(true){} }");
        UiScriptService svc = new UiScriptService(dir);
        svc.load();
        long t0 = System.currentTimeMillis();
        String r = svc.render("evil", ctx());
        long dt = System.currentTimeMillis() - t0;
        assertNull(r, "失控脚本应返回 null");
        assertTrue(dt < 4000, "应约 2s 中止，实际 " + dt + "ms");
    }

    @Test
    void compileCacheGivesConsistentOutput() throws Exception {
        UiScriptService svc = loaded();
        assertEquals(svc.render("shop", ctx()), svc.render("shop", ctx()));
    }

    @Test
    void missingScriptReturnsNull() throws Exception {
        assertNull(loaded().render("does_not_exist", ctx()));
        assertFalse(loaded().has("does_not_exist"));
    }
}
