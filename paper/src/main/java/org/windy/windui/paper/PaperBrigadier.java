package org.windy.windui.paper;

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
 * 把 {@link PaperCommands} 桥接到 Paper 原生 Brigadier 命令树（paper-plugin.yml 无 commands 块）。
 * 用贪婪字符串参数吃掉 {@code /windui} 后全部内容，切成 {@code String[]} 交回 PaperCommands。
 */
public final class PaperBrigadier {

    private PaperBrigadier() {
    }

    public static LiteralCommandNode<CommandSourceStack> node(PaperCommands commands) {
        return Commands.literal("windui")
                .executes(ctx -> exec(commands, ctx.getSource(), ""))
                .then(Commands.argument("args", StringArgumentType.greedyString())
                        .suggests((ctx, sb) -> suggest(commands, ctx.getSource(), sb))
                        .executes(ctx -> exec(commands, ctx.getSource(), StringArgumentType.getString(ctx, "args"))))
                .build();
    }

    private static int exec(PaperCommands commands, CommandSourceStack src, String raw) {
        commands.execute(src.getSender(), split(raw));
        return com.mojang.brigadier.Command.SINGLE_SUCCESS;
    }

    private static CompletableFuture<Suggestions> suggest(PaperCommands commands, CommandSourceStack src,
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
