package org.windy.windui.client;

import net.minecraft.client.Minecraft;
import org.windy.windui.client.net.WsClient;
import org.windy.windui.core.script.SafeClientAPI;

import java.util.List;

/**
 * {@link SafeClientAPI} 的 NeoForge 1.21.1 客户端实现。
 * <p>
 * <b>平台特定 glue</b>：与 26.1.2 版逐字节相同（这些 MC API 在两版一致）；若某版本 API 漂移，只改本文件。
 * 所有操作都通过 {@code Minecraft.getInstance().execute()} 投递到主线程。
 */
public class SafeClientImpl implements SafeClientAPI {

    @Override
    public void closeScreen() {
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
            var loc = net.minecraft.resources.ResourceLocation.tryParse(id);
            if (loc == null) return;
            var event = net.minecraft.sounds.SoundEvent.createVariableRangeEvent(loc);
            Minecraft.getInstance().getSoundManager().play(
                    net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(event, pitch, volume));
        });
    }

    @Override
    public void sendServerEvent(String eventId, List<String> data) {
        // 回程优先走 WebSocket（{@link WsClient}）；未连接/发送失败则退回命令 /windui event <id> [data...]。
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
