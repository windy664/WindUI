package org.windy.windui.core.gui;

import org.windy.windui.core.placeholder.PlaceholderResolver;
import org.windy.windui.core.placeholder.Placeholders;
import org.windy.windui.core.protocol.UiPayload;

/**
 * GUI 业务门面。平台无关。
 * <p>
 * 把"取界面定义"（{@link GuiRegistry}）与"套占位符"（{@link Placeholders}）组合起来，
 * 产出可直接下发的 {@link UiPayload}。平台侧只负责提供 {@link PlaceholderResolver} 和发包。
 */
public final class GuiService {

    private final GuiRegistry registry;

    public GuiService(GuiRegistry registry) {
        this.registry = registry;
    }

    public GuiRegistry registry() {
        return registry;
    }

    /**
     * 取界面并套用占位符，返回可下发的 payload。
     *
     * @param guiId    界面 id（文件名）
     * @param resolver 占位符解析器（平台提供，绑定具体玩家）
     * @return 渲染好的 payload；界面不存在返回 {@code null}
     */
    public UiPayload render(String guiId, PlaceholderResolver resolver) {
        String json = registry.get(guiId);
        if (json == null) {
            return null;
        }
        return new UiPayload(Placeholders.apply(json, resolver));
    }
}
