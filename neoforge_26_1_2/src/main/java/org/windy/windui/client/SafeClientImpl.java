package org.windy.windui.client;

import net.minecraft.client.Minecraft;
import org.windy.windui.client.net.WsClient;
import org.windy.windui.core.script.SafeClientAPI;

import java.util.List;

/**
 * {@link SafeClientAPI} 的 NeoForge 客户端实现。
 * <p>
 * <b>平台特定 glue（每个 MC 版本各写一份）</b>：sendCommand/setScreen/Component.literal 等 MC API
 * 在不同版本会漂移，故按版本各实现一份；core 只认 {@link SafeClientAPI} 接口，共享渲染层不受影响。
 * <p>
 * 所有操作都通过 {@code Minecraft.getInstance().execute()} 投递到主线程，避免从 UI 线程直接调用 MC API。
 */
public class SafeClientImpl implements SafeClientAPI {

    @Override
    public void closeScreen() {
        // 关屏走原版 setScreen(null)：MuiModApi.openScreen 参数 @Nonnull，传 null 关不掉。
        // ModernUI 界面本质是原版 Screen，setScreen(null) 会正常关闭并触发 onDestroyView→onScreenClosed。
        Minecraft.getInstance().execute(() -> Minecraft.getInstance().setScreen(null));
    }

    @Override
    public void showToast(String text) {
        Minecraft.getInstance().execute(() -> {
            var mc = Minecraft.getInstance();
            if (mc.player != null) {
                mc.player.sendSystemMessage(
                        net.minecraft.network.chat.Component.literal("[WindUI] " + text));
            }
        });
    }

    @Override
    public void executeCommand(String command) {
        String cmd = command.startsWith("/") ? command.substring(1) : command;
        Minecraft.getInstance().execute(() -> {
            var mc = Minecraft.getInstance();
            if (mc.player != null) {
                mc.player.connection.sendCommand(cmd);
            }
        });
    }

    @Override
    public void playSound(String id, float volume, float pitch) {
        Minecraft.getInstance().execute(() -> {
            var loc = net.minecraft.resources.Identifier.tryParse(id);
            if (loc == null) return;
            var event = net.minecraft.sounds.SoundEvent.createVariableRangeEvent(loc);
            Minecraft.getInstance().getSoundManager().play(
                    net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(event, pitch, volume));
        });
    }

    @Override
    public void sendServerEvent(String eventId, List<String> data) {
        // 回程优先走 WebSocket（{@link WsClient}）；未连接/发送失败则退回命令 /windui event <id> [data...]
        // （Velocity 拦截派发，作兜底）。
        if (WsClient.sendEvent(eventId, data)) {
            return;
        }
        Minecraft.getInstance().execute(() -> {
            var mc = Minecraft.getInstance();
            if (mc.player == null) return;
            StringBuilder sb = new StringBuilder("windui event ").append(eventId);
            for (String d : data) {
                sb.append(' ').append(d);
            }
            mc.player.connection.sendCommand(sb.toString());
        });
    }
}
