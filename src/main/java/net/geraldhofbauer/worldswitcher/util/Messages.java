package net.geraldhofbauer.worldswitcher.util;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

public final class Messages {

    private Messages() {
    }

    public static MutableComponent success(String text) {
        return Component.literal(text).withStyle(ChatFormatting.GREEN);
    }

    public static MutableComponent info(String text) {
        return Component.literal(text).withStyle(ChatFormatting.GRAY);
    }

    public static MutableComponent error(String text) {
        return Component.literal(text).withStyle(ChatFormatting.RED);
    }

    public static MutableComponent highlight(String text) {
        return Component.literal(text).withStyle(ChatFormatting.AQUA);
    }

    /** Clickable text that runs a command on click. */
    public static MutableComponent runCommand(String label, String command, ChatFormatting color) {
        return Component.literal(label).withStyle(style -> style
                .withColor(color)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(command))));
    }

    /** Clickable text that puts a command into the chat input for editing. */
    public static MutableComponent suggestCommand(String label, String command, ChatFormatting color) {
        return Component.literal(label).withStyle(style -> style
                .withColor(color)
                .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, command))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(command))));
    }
}
