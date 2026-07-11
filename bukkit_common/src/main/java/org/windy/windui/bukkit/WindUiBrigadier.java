package org.windy.windui.bukkit;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * 把平台无关的 {@link WindUiCommands} 桥接到 Paper 原生 Brigadier 命令树。
 * <p>
 * paper-plugin.yml 不支持 {@code commands:} 块，命令必须经 {@code LifecycleEvents.COMMANDS} 注册。
 * 用一个<b>贪婪字符串参数</b>吃掉 {@code /windui} 后的全部内容，切成 {@code String[]} 交回
 * {@link WindUiCommands}——子命令逻辑零改动，只换了「命令怎么被服务器认领」这一层。做法与
 * PlayerShelter 的 {@code PsBrigadier} 一致。Brigadier API 在 Paper 1.21↔26.x 一致，故落 bukkit_common。
 */
public final class WindUiBrigadier {

    private WindUiBrigadier() {
    }

    /** 构建 {@code /windui} 命令节点（执行与补全全部委派给 {@link WindUiCommands}）。 */
    public static LiteralCommandNode<CommandSourceStack> node(WindUiCommands commands) {
        return Commands.literal("windui")
                .executes(ctx -> exec(commands, ctx.getSource(), ""))
                .then(Commands.argument("args", StringArgumentType.greedyString())
                        .suggests((ctx, sb) -> suggest(commands, ctx.getSource(), sb))
                        .executes(ctx -> exec(commands, ctx.getSource(),
                                StringArgumentType.getString(ctx, "args"))))
                .build();
    }

    private static int exec(WindUiCommands commands, CommandSourceStack src, String raw) {
        commands.execute(src.getSender(), split(raw));
        return com.mojang.brigadier.Command.SINGLE_SUCCESS;
    }

    private static CompletableFuture<Suggestions> suggest(WindUiCommands commands, CommandSourceStack src,
                                                          SuggestionsBuilder builder) {
        String input = builder.getRemaining();
        int lastSpace = input.lastIndexOf(' ');
        String last = lastSpace < 0 ? input : input.substring(lastSpace + 1);

        String[] base = split(input);
        String[] args;
        if (input.isEmpty() || input.endsWith(" ")) {
            args = new String[base.length + 1];
            System.arraycopy(base, 0, args, 0, base.length);
            args[base.length] = "";
        } else {
            args = base;
        }

        List<String> out = commands.tabComplete(src.getSender(), args);
        SuggestionsBuilder offset = builder.createOffset(builder.getStart() + lastSpace + 1);
        if (out != null) {
            String lower = last.toLowerCase(Locale.ROOT);
            for (String s : out) {
                if (s != null && s.toLowerCase(Locale.ROOT).startsWith(lower)) {
                    offset.suggest(s);
                }
            }
        }
        return offset.buildFuture();
    }

    private static String[] split(String raw) {
        String trimmed = raw.strip();
        return trimmed.isEmpty() ? new String[0] : trimmed.split("\\s+");
    }
}
