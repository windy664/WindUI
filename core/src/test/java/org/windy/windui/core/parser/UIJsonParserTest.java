package org.windy.windui.core.parser;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void onClickSingleObjectBecomesList() {
        WidgetNode node = UIJsonParser.parse("{\"type\":\"Button\",\"onClick\":{\"action\":\"closeScreen\"}}");
        assertEquals(1, node.onClick().size());
        assertEquals("closeScreen", node.onClick().get(0).get("action"));
    }

    @Test
    void onClickArrayAndNestedArgsPreserved() {
        WidgetNode node = UIJsonParser.parse(
                "{\"type\":\"Button\",\"onClick\":[{\"action\":\"executeCommand\",\"args\":[\"/spawn\"]},"
                        + "{\"action\":\"closeScreen\"}]}");
        assertEquals(2, node.onClick().size());
        Object args = node.onClick().get(0).get("args");
        assertTrue(args instanceof List, "args should stay a List, not a String");
        assertEquals("/spawn", ((List<?>) args).get(0));
    }

    @Test
    void idFieldParsed() {
        WidgetNode node = UIJsonParser.parse("{\"type\":\"TextBox\",\"id\":\"query\"}");
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
                        + "\"onTextChange\":{\"action\":\"callback\",\"id\":\"search\",\"readback\":[\"q\"]}}");
        assertEquals("search", node.on("textChange").get(0).get("id"));
        assertTrue(node.on("textChange").get(0).get("readback") instanceof List);
    }

    @Test
    void sliderChangeEventParsed() {
        WidgetNode node = UIJsonParser.parse(
                "{\"type\":\"Slider\",\"id\":\"vol\","
                        + "\"onChange\":{\"action\":\"callback\",\"id\":\"setvol\",\"readback\":[\"vol\"]}}");
        assertEquals("setvol", node.on("change").get(0).get("id"));
    }
}
