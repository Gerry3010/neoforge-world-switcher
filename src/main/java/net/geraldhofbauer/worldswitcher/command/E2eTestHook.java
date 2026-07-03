package net.geraldhofbauer.worldswitcher.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.serialization.Codec;
import net.geraldhofbauer.worldswitcher.WorldSwitcherMod;
import net.geraldhofbauer.worldswitcher.util.Messages;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import javax.annotation.Nullable;
import java.util.function.Supplier;

/**
 * Test-only hook, active ONLY with {@code -Dworldswitcher.e2e=true}: registers a serializable
 * marker attachment and a {@code /wsc debug} subcommand so the per-world attachment and
 * persistent-data swap can be exercised end-to-end by a vanilla-protocol test client (mods like
 * Curios register mandatory network payloads and would reject it). The attachment-type registry
 * is not synced to clients, so vanilla clients still join.
 */
public final class E2eTestHook {

    public static final String PROPERTY = "worldswitcher.e2e";

    @Nullable
    private static Supplier<AttachmentType<String>> e2eMarker;

    private E2eTestHook() {
    }

    public static boolean enabled() {
        return e2eMarker != null;
    }

    /** Called from the mod constructor, only when the system property is set. */
    public static void register(IEventBus modEventBus) {
        DeferredRegister<AttachmentType<?>> attachments =
                DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, WorldSwitcherMod.MODID);
        e2eMarker = attachments.register("e2e_marker",
                () -> AttachmentType.builder(() -> "").serialize(Codec.STRING).build());
        attachments.register(modEventBus);
        WorldSwitcherMod.LOGGER.warn("E2E test hook active ({}=true) — do not use in production", PROPERTY);
    }

    /** {@code /wsc debug attachment|pdata set/get …} — requires an executing player. */
    public static LiteralArgumentBuilder<CommandSourceStack> buildDebugNode() {
        return Commands.literal("debug")
                .then(Commands.literal("attachment")
                        .then(Commands.literal("set")
                                .then(Commands.argument("value", StringArgumentType.word())
                                        .executes(E2eTestHook::attachmentSet)))
                        .then(Commands.literal("get")
                                .executes(E2eTestHook::attachmentGet)))
                .then(Commands.literal("pdata")
                        .then(Commands.literal("set")
                                .then(Commands.argument("key", StringArgumentType.word())
                                        .then(Commands.argument("value", StringArgumentType.word())
                                                .executes(E2eTestHook::pdataSet))))
                        .then(Commands.literal("get")
                                .then(Commands.argument("key", StringArgumentType.word())
                                        .executes(E2eTestHook::pdataGet))));
    }

    private static int attachmentSet(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        String value = StringArgumentType.getString(context, "value");
        player.setData(e2eMarker.get(), value);
        context.getSource().sendSuccess(() -> Messages.info("e2e attachment = " + value), false);
        return 1;
    }

    private static int attachmentGet(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        String value = player.getExistingData(e2eMarker.get()).orElse("<absent>");
        context.getSource().sendSuccess(() -> Messages.info("e2e attachment = " + value), false);
        return 1;
    }

    private static int pdataSet(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        String key = StringArgumentType.getString(context, "key");
        String value = StringArgumentType.getString(context, "value");
        player.getPersistentData().putString(key, value);
        context.getSource().sendSuccess(() -> Messages.info("e2e pdata " + key + " = " + value), false);
        return 1;
    }

    private static int pdataGet(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        String key = StringArgumentType.getString(context, "key");
        String value = player.getPersistentData().contains(key)
                ? player.getPersistentData().getString(key) : "<absent>";
        context.getSource().sendSuccess(() -> Messages.info("e2e pdata " + key + " = " + value), false);
        return 1;
    }
}
