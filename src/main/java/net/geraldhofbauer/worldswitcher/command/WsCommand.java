package net.geraldhofbauer.worldswitcher.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.geraldhofbauer.worldswitcher.Config;
import net.geraldhofbauer.worldswitcher.player.PlayerStateManager;
import net.geraldhofbauer.worldswitcher.util.Messages;
import net.geraldhofbauer.worldswitcher.world.DynamicDimensionManager;
import net.geraldhofbauer.worldswitcher.world.WorldRegistry;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * {@code /ws <world>} — switch the executing player to another world.
 */
public final class WsCommand {

    private WsCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("ws")
                .requires(source -> source.hasPermission(Config.wsPermissionLevel()))
                .then(Commands.argument("world", StringArgumentType.word())
                        .suggests(WorldSuggestions.SWITCH_TARGETS)
                        .executes(WsCommand::executeSwitch)));
    }

    private static int executeSwitch(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayerOrException();
        String worldName = StringArgumentType.getString(context, "world");
        return switchToWorld(source, player, worldName);
    }

    /** Shared by /ws and /wsc tp. Returns the command result (1 = success). */
    static int switchToWorld(CommandSourceStack source, ServerPlayer player, String worldName) {
        MinecraftServer server = source.getServer();
        String currentGroup = WorldRegistry.groupOf(player.level().dimension());

        if (WorldRegistry.DEFAULT_GROUP.equalsIgnoreCase(worldName)) {
            if (WorldRegistry.DEFAULT_GROUP.equals(currentGroup)) {
                source.sendSuccess(() -> Messages.info("Already in the default world."), false);
                return 1;
            }
            PlayerStateManager.switchPlayer(player, server.overworld());
            source.sendSuccess(() -> Messages.success("Switched to ").append(Messages.highlight("default")), true);
            return 1;
        }

        WorldRegistry.WorldEntry entry = WorldRegistry.get(server).byName(worldName);
        if (entry == null) {
            source.sendFailure(Messages.error("Unknown world: " + worldName));
            return 0;
        }
        if (entry.unloaded()) {
            source.sendFailure(Messages.error("World '" + entry.name() + "' is unloaded — ask an admin to run ")
                    .append(Messages.runCommand("/wsc load " + entry.name(), "/wsc load " + entry.name(),
                            net.minecraft.ChatFormatting.YELLOW)));
            return 0;
        }
        if (entry.id().equals(currentGroup)) {
            source.sendSuccess(() -> Messages.info("Already in world '" + entry.name() + "'."), false);
            return 1;
        }

        ServerLevel target = DynamicDimensionManager.getLoadedLevel(server, entry);
        if (target == null) {
            source.sendFailure(Messages.error("World '" + entry.name() + "' is not loaded."));
            return 0;
        }

        PlayerStateManager.switchPlayer(player, target);
        source.sendSuccess(() -> Messages.success("Switched to ").append(Messages.highlight(entry.name())), true);
        return 1;
    }
}
