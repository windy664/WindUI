package org.windy.windui;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.lwjgl.glfw.GLFW;

@EventBusSubscriber(modid = WindUI.MODID)
public class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    // === 商店键位(GLFW 键码,十进制) ===
    // 默认 U 键(GLFW_KEY_U = 85)。改成其他数字即可换键,例如 341 = F5。
    // 完整键码表:https://www.glfw.org/docs/latest/group__keys.html
    private static final ModConfigSpec.IntValue SHOP_KEY_CODE = BUILDER
            .comment("GLFW key code for opening the shop UI (default: 85 = U). See https://www.glfw.org/docs/latest/group__keys.html")
            .defineInRange("shopKeyCode", GLFW.GLFW_KEY_U, 0, 512);

    static final ModConfigSpec SPEC = BUILDER.build();

    public static int shopKeyCode;

    @SubscribeEvent
    static void onLoad(final ModConfigEvent event) {
        shopKeyCode = SHOP_KEY_CODE.get();
    }
}
