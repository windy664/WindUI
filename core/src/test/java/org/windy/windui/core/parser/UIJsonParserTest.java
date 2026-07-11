package org.windy.windui.core.parser;

import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UIJsonParserTest {

    @Test
    void parsesBasicTree() {
        WidgetNode node = UIJsonParser.parse(
                "{\"type\":\"LinearLayout\",\"props\":{\"orientation\":1},"
                        + "\"children\":[{\"type\":\"TextView\",\"props\":{\"text\":\"hi\"}}]}");
        assertEquals("LinearLayout", node.type());
        assertEquals(1, node.getInt("orientation", -1));
        assertEquals(1, node.children().size());
        assertEquals("TextView", node.children().get(0).type());
        assertEquals("hi", node.children().get(0).getString("text", ""));
    }

    @Test
    void preservesRootScreenBackgroundProps() {
        WidgetNode node = UIJsonParser.parse(
                "{\"type\":\"FrameLayout\",\"props\":{"
                        + "\"dimBackground\":false,\"overlayColor\":\"#80000000\"}}"
        );

        assertEquals(false, node.getBool("dimBackground", true));
        assertEquals("#80000000", node.getString("overlayColor", null));
    }

    @Test
    void bundledGuisParseAndUseIosVisualTokens() throws IOException {
        for (String id : List.of("menu_main", "shop_main", "confirm", "welcome")) {
            String json = resourceText("/windui/guis/" + id + ".json");
            WidgetNode root = UIJsonParser.parse(json);

            assertEquals("LinearLayout", root.type(), id + " 根节点类型应稳定");
            assertTrue(json.contains("#007AFF") || json.contains("#FF3B30"),
                    id + " 应使用 iOS 系统强调色");
            assertTrue(json.contains("#F5FFFFFF") || json.contains("#F8FFFFFF"),
                    id + " 应使用半透明白色卡片");
            assertTrue(root.getString("overlayColor", "").startsWith("#"),
                    id + " 应显式覆盖默认背景");
        }
    }

    @Test
    void bundledHudIsValidJsonAndUsesIosVisualTokens() throws IOException {
        String json = resourceText("/windui/huds/demo.json");

        assertTrue(JsonParser.parseString(json).isJsonArray());
        assertTrue(json.contains("#EFFFFFFF"), "HUD 应使用半透明白色小组件背景");
        assertTrue(json.contains("#007AFF"), "HUD 应使用 iOS 系统蓝");
    }

    @Test
    void onClickSingleObjectBecomesList() {
        WidgetNode node = UIJsonParser.parse(
                "{\"type\":\"Button\",\"onClick\":{\"action\":\"closeScreen\"}}");
        assertEquals(1, node.onClick().size());
        assertEquals("closeScreen", node.onClick().get(0).get("action"));
    }

    @Test
    void onClickArrayAndNestedArgsPreserved() {
        WidgetNode node = UIJsonParser.parse(
                "{\"type\":\"Button\",\"onClick\":["
                        + "{\"action\":\"executeCommand\",\"args\":[\"/spawn\"]},"
                        + "{\"action\":\"closeScreen\"}]}");
        assertEquals(2, node.onClick().size());
        Object args = node.onClick().get(0).get("args");
        assertTrue(args instanceof List, "args should stay a List, not a String");
        assertEquals("/spawn", ((List<?>) args).get(0));
    }

    @Test
    void idFieldParsed() {
        WidgetNode node = UIJsonParser.parse(
                "{\"type\":\"TextBox\",\"id\":\"query\"}");
        assertEquals("query", node.id());
    }

    @Test
    void richEventsParsedIntoEventsMap() {
        WidgetNode node = UIJsonParser.parse(
                "{\"type\":\"ItemSlot\","
                        + "\"onClick\":{\"action\":\"callback\",\"id\":\"buy\"},"
                        + "\"onRightClick\":{\"action\":\"callback\",\"id\":\"info\"},"
                        + "\"onHoverEnter\":{\"action\":\"playSound\",\"args\":[\"ui.hover\"]},"
                        + "\"onHoverLeave\":[{\"action\":\"closeScreen\"}]}");

        assertEquals("callback", node.on("click").get(0).get("action"));
        assertEquals("info", node.on("rightClick").get(0).get("id"));
        assertEquals("playSound", node.on("hoverEnter").get(0).get("action"));
        assertEquals(1, node.on("hoverLeave").size());
        assertTrue(node.on("textChange").isEmpty(), "未声明的事件应为空列表");
    }

    @Test
    void textChangeEventParsed() {
        WidgetNode node = UIJsonParser.parse(
                "{\"type\":\"TextBox\",\"id\":\"q\","
                        + "\"onTextChange\":{\"action\":\"callback\","
                        + "\"id\":\"search\",\"readback\":[\"q\"]}}");
        assertEquals("search", node.on("textChange").get(0).get("id"));
        assertTrue(node.on("textChange").get(0).get("readback") instanceof List);
    }

    @Test
    void sliderChangeEventParsed() {
        WidgetNode node = UIJsonParser.parse(
                "{\"type\":\"Slider\",\"id\":\"vol\","
                        + "\"onChange\":{\"action\":\"callback\","
                        + "\"id\":\"setvol\",\"readback\":[\"vol\"]}}");
        assertEquals("setvol", node.on("change").get(0).get("id"));
    }

    @Test
    void rejectsNullBlankAndNonObjectRoots() {
        assertThrows(JsonParseException.class, () -> UIJsonParser.parse(null));
        assertThrows(JsonParseException.class, () -> UIJsonParser.parse(" "));
        assertThrows(JsonParseException.class, () -> UIJsonParser.parse("[]"));
        assertThrows(JsonParseException.class, () -> UIJsonParser.parse("\"TextView\""));
    }

    @Test
    void rejectsMissingBlankAndNonStringType() {
        assertThrows(JsonParseException.class,
                () -> UIJsonParser.parse("{}"));
        assertThrows(JsonParseException.class,
                () -> UIJsonParser.parse("{\"type\":\"\"}"));
        assertThrows(JsonParseException.class,
                () -> UIJsonParser.parse("{\"type\":123}"));
    }

    @Test
    void rejectsInvalidPropsChildrenAndEventShapes() {
        assertThrows(JsonParseException.class,
                () -> UIJsonParser.parse(
                        "{\"type\":\"TextView\",\"props\":[]}"));
        assertThrows(JsonParseException.class,
                () -> UIJsonParser.parse(
                        "{\"type\":\"LinearLayout\",\"children\":{}}"));
        assertThrows(JsonParseException.class,
                () -> UIJsonParser.parse(
                        "{\"type\":\"LinearLayout\",\"children\":[1]}"));
        assertThrows(JsonParseException.class,
                () -> UIJsonParser.parse(
                        "{\"type\":\"Button\",\"onClick\":\"closeScreen\"}"));
    }

    @Test
    void rejectsExcessivelyDeepComponentTree() {
        StringBuilder json = new StringBuilder();
        for (int i = 0; i < 66; i++) {
            json.append(
                    "{\"type\":\"LinearLayout\",\"children\":[");
        }
        json.append("{\"type\":\"TextView\"}");
        for (int i = 0; i < 66; i++) {
            json.append("]}");
        }

        assertThrows(JsonParseException.class,
                () -> UIJsonParser.parse(json.toString()));
    }

    @Test
    void parseActionsReturnsEmptyForMalformedOrWrongRoot() {
        assertTrue(UIJsonParser.parseActions(null).isEmpty());
        assertTrue(UIJsonParser.parseActions("").isEmpty());
        assertTrue(UIJsonParser.parseActions("{}").isEmpty());
        assertTrue(UIJsonParser.parseActions("[{\"action\":").isEmpty());
    }

    @Test
    void parseActionsSkipsNonObjectEntries() {
        List<java.util.Map<String, Object>> actions =
                UIJsonParser.parseActions(
                        "[null,1,\"bad\",{\"action\":\"closeScreen\"}]");

        assertEquals(1, actions.size());
        assertEquals("closeScreen", actions.get(0).get("action"));
    }

    private static String resourceText(String path) throws IOException {
        try (InputStream stream = UIJsonParserTest.class.getResourceAsStream(path)) {
            if (stream == null) {
                throw new IOException("缺少测试资源 " + path);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
