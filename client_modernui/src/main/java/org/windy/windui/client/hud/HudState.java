package org.windy.windui.client.hud;

import org.windy.windui.core.protocol.HudCodec;

import java.util.List;

/**
 * 客户端当前 HUD 面板集(版本无关的持有者)。服务端经 {@code windui:hud} 推来即 {@link #set};
 * 每版的 HUD 渲染钩子每帧 {@link #get} 出来画。空 = 不画。
 */
public final class HudState {

    private static volatile List<HudCodec.Panel> panels = List.of();

    private HudState() {
    }

    public static void set(List<HudCodec.Panel> p) {
        panels = p == null ? List.of() : p;
    }

    public static List<HudCodec.Panel> get() {
        return panels;
    }

    public static void clear() {
        panels = List.of();
    }
}
