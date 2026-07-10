package org.windy.windui.core.placeholder;

/**
 * 占位符解析器：把 {@code %key%} 中的 key 解析成字符串值。
 * <p>
 * 平台无关的 SPI——core 只定义"如何替换"，具体值由各平台提供：
 * Velocity 侧用代理已知数据（玩家名/子服…），未来 Bukkit 侧可桥接 PlaceholderAPI。
 */
@FunctionalInterface
public interface PlaceholderResolver {

    /**
     * @param key 占位符名（不含两侧的 %）
     * @return 对应的值；若本解析器不认识该 key，返回 {@code null}（交给下一个解析器或原样保留）
     */
    String resolve(String key);
}
