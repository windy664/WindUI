package org.windy.windui.core.placeholder;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 占位符替换引擎。平台无关。
 * <p>
 * 语法 {@code %key%}（key 由字母/数字/下划线组成）。未知占位符**原样保留**，
 * 以便后续环节（如后端 PAPI）继续解析。
 */
public final class Placeholders {

    private static final Pattern TOKEN = Pattern.compile("%([a-zA-Z0-9_]+)%");

    private Placeholders() {
    }

    /** 用 resolver 替换 template 中的所有 {@code %key%}。 */
    public static String apply(String template, PlaceholderResolver resolver) {
        if (template == null || resolver == null || template.indexOf('%') < 0) {
            return template;
        }
        Matcher m = TOKEN.matcher(template);
        StringBuilder sb = new StringBuilder(template.length());
        while (m.find()) {
            String value = resolver.resolve(m.group(1));
            // 未知占位符原样保留（含两侧 %），已知则替换
            m.appendReplacement(sb, Matcher.quoteReplacement(value == null ? m.group() : value));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** 组合多个解析器：按顺序取第一个返回非 null 的结果。 */
    public static PlaceholderResolver compose(List<PlaceholderResolver> resolvers) {
        return key -> {
            for (PlaceholderResolver r : resolvers) {
                String v = r.resolve(key);
                if (v != null) return v;
            }
            return null;
        };
    }
}
