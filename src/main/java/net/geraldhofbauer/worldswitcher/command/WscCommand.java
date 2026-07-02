package net.geraldhofbauer.worldswitcher.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.geraldhofbauer.worldswitcher.WorldSwitcherMod;
import net.geraldhofbauer.worldswitcher.util.Messages;
import net.geraldhofbauer.worldswitcher.world.DynamicDimensionManager;
import net.geraldhofbauer.worldswitcher.world.ImportService;
import net.geraldhofbauer.worldswitcher.world.WorldRegistry;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * {@code /wsc <action> [args]} — world management, OP level 2+.
 */
public final class WscCommand {

    /** Sentinel "UUID" for non-player command sources (console) in the pending-delete map. */
    private static final UUID CONSOLE_UUID = new UUID(0L, 0L);
    private static final long CONFIRM_TIMEOUT_MS = 30_000;

    private record PendingDelete(String worldId, long requestedAt) {
    }

    private static final Map<UUID, PendingDelete> PENDING_DELETES = new HashMap<>();

    private WscCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("wsc")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("list")
                        .executes(WscCommand::executeList))
                .then(Commands.literal("info")
                        .then(Commands.argument("world", StringArgumentType.word())
                                .suggests(WorldSuggestions.REGISTERED_WORLDS)
                                .executes(WscCommand::executeInfo)))
                .then(Commands.literal("create")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(context -> executeCreate(context, null))
                                .then(Commands.argument("seed", LongArgumentType.longArg())
                                        .executes(context ->
                                                executeCreate(context, LongArgumentType.getLong(context, "seed"))))))
                .then(Commands.literal("import")
                        .then(Commands.argument("source", StringArgumentType.string())
                                .suggests(ImportService.IMPORT_CANDIDATES)
                                .executes(context -> executeImport(context, null))
                                .then(Commands.literal("as")
                                        .then(Commands.argument("name", StringArgumentType.word())
                                                .executes(context -> executeImport(context,
                                                        StringArgumentType.getString(context, "name")))))))
                .then(Commands.literal("rename")
                        .then(Commands.argument("world", StringArgumentType.word())
                                .suggests(WorldSuggestions.REGISTERED_WORLDS)
                                .then(Commands.argument("newName", StringArgumentType.word())
                                        .executes(WscCommand::executeRename))))
                .then(Commands.literal("load")
                        .then(Commands.argument("world", StringArgumentType.word())
                                .suggests(WorldSuggestions.UNLOADED_WORLDS)
                                .executes(WscCommand::executeLoad)))
                .then(Commands.literal("unload")
                        .then(Commands.argument("world", StringArgumentType.word())
                                .suggests(WorldSuggestions.REGISTERED_WORLDS)
                                .executes(WscCommand::executeUnload)))
                .then(Commands.literal("tp")
                        .then(Commands.argument("player", EntityArgument.player())
                                .then(Commands.argument("world", StringArgumentType.word())
                                        .suggests(WorldSuggestions.SWITCH_TARGETS)
                                        .executes(WscCommand::executeTp))))
                .then(Commands.literal("delete")
                        .then(Commands.argument("world", StringArgumentType.word())
                                .suggests(WorldSuggestions.REGISTERED_WORLDS)
                                .executes(WscCommand::executeDeleteRequest)))
                .then(Commands.literal("confirm")
                        .executes(WscCommand::executeDeleteConfirm))
                .then(Commands.literal("cancel")
                        .executes(WscCommand::executeDeleteCancel)));
    }

    private static WorldRegistry.WorldEntry resolveWorld(CommandContext<CommandSourceStack> context) {
        String name = StringArgumentType.getString(context, "world");
        WorldRegistry.WorldEntry entry = WorldRegistry.get(context.getSource().getServer()).byName(name);
        if (entry == null) {
            context.getSource().sendFailure(Messages.error("Unknown world: " + name));
        }
        return entry;
    }

    private static int executeList(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        MinecraftServer server = source.getServer();
        var entries = WorldRegistry.get(server).entries();

        source.sendSuccess(() -> Messages.highlight("Worlds (" + entries.size() + "):"), false);
        for (WorldRegistry.WorldEntry entry : entries) {
            ServerLevel level = DynamicDimensionManager.getLoadedLevel(server, entry);
            boolean loaded = level != null;
            int playerCount = loaded ? level.players().size() : 0;

            var line = Component.literal("  ")
                    .append(Messages.runCommand(entry.name(), "/ws " + entry.name(), ChatFormatting.AQUA))
                    .append(Component.literal(loaded ? "  loaded" : "  unloaded")
                            .withStyle(loaded ? ChatFormatting.GREEN : ChatFormatting.RED));
            if (loaded) {
                line.append(Component.literal("  " + playerCount + " player" + (playerCount == 1 ? "" : "s"))
                        .withStyle(ChatFormatting.GRAY));
            }
            source.sendSuccess(() -> line, false);
        }
        if (entries.isEmpty()) {
            source.sendSuccess(() -> Messages.info("  (none — use /wsc import or /wsc create)"), false);
        }
        return entries.size();
    }

    private static int executeInfo(CommandContext<CommandSourceStack> context) {
        WorldRegistry.WorldEntry entry = resolveWorld(context);
        if (entry == null) {
            return 0;
        }
        CommandSourceStack source = context.getSource();
        MinecraftServer server = source.getServer();
        ServerLevel level = DynamicDimensionManager.getLoadedLevel(server, entry);

        Path dimensionPath = DynamicDimensionManager.storageSource(server)
                .getDimensionPath(entry.dimensionKey());
        long diskSize = folderSize(dimensionPath);

        source.sendSuccess(() -> Messages.highlight("World '" + entry.name() + "'"), false);
        source.sendSuccess(() -> Messages.info("  id: " + entry.id()
                + "  dimension: " + entry.dimensionKey().location()), false);
        source.sendSuccess(() -> Messages.info("  status: " + (level != null ? "loaded, "
                + level.players().size() + " players" : "unloaded")), false);
        source.sendSuccess(() -> Messages.info("  seed: " + entry.seed()), false);
        source.sendSuccess(() -> Messages.info("  spawn: "
                + (entry.spawnPos() != null ? entry.spawnPos().toShortString() : "not set")), false);
        source.sendSuccess(() -> Messages.info("  folder: " + dimensionPath + " (" + formatSize(diskSize) + ")"), false);
        if (!entry.sourcePath().isEmpty()) {
            source.sendSuccess(() -> Messages.info("  imported from: " + entry.sourcePath()), false);
        }
        return 1;
    }

    private static int executeCreate(CommandContext<CommandSourceStack> context, Long seedArg) {
        CommandSourceStack source = context.getSource();
        MinecraftServer server = source.getServer();
        String name = StringArgumentType.getString(context, "name");

        String id = name.toLowerCase(Locale.ROOT);
        if (!WorldRegistry.ID_PATTERN.matcher(id).matches()) {
            source.sendFailure(Messages.error("Invalid name (allowed: a-z 0-9 _ -, max 32 chars): " + name));
            return 0;
        }
        WorldRegistry registry = WorldRegistry.get(server);
        if (registry.byId(id) != null || registry.nameTaken(name)) {
            source.sendFailure(Messages.error("A world with this name already exists: " + name));
            return 0;
        }

        long seed = seedArg != null ? seedArg : RandomSource.create().nextLong();
        WorldRegistry.WorldEntry entry = new WorldRegistry.WorldEntry(
                id, name, seed, null, 0.0F, false, System.currentTimeMillis(), "");
        registry.put(entry);
        DynamicDimensionManager.getOrCreateLevel(server, entry);

        source.sendSuccess(() -> Messages.success("Created world ")
                .append(Messages.runCommand(name, "/ws " + name, ChatFormatting.AQUA))
                .append(Messages.info(" (seed " + seed + ") — click to switch")), true);
        return 1;
    }

    private static int executeImport(CommandContext<CommandSourceStack> context, String nameArg) {
        CommandSourceStack source = context.getSource();
        String sourcePath = StringArgumentType.getString(context, "source");
        ImportService.importWorld(source, sourcePath, nameArg);
        return 1;
    }

    private static int executeRename(CommandContext<CommandSourceStack> context) {
        WorldRegistry.WorldEntry entry = resolveWorld(context);
        if (entry == null) {
            return 0;
        }
        CommandSourceStack source = context.getSource();
        String newName = StringArgumentType.getString(context, "newName");
        WorldRegistry registry = WorldRegistry.get(source.getServer());

        if (registry.nameTaken(newName)) {
            source.sendFailure(Messages.error("A world with this name already exists: " + newName));
            return 0;
        }
        String oldName = entry.name();
        registry.rename(entry.id(), newName);
        source.sendSuccess(() -> Messages.success("Renamed '" + oldName + "' to ")
                .append(Messages.highlight(newName))
                .append(Messages.info(" (id stays '" + entry.id() + "' — inventories and spawns keep working)")), true);
        return 1;
    }

    private static int executeLoad(CommandContext<CommandSourceStack> context) {
        WorldRegistry.WorldEntry entry = resolveWorld(context);
        if (entry == null) {
            return 0;
        }
        CommandSourceStack source = context.getSource();
        DynamicDimensionManager.getOrCreateLevel(source.getServer(), entry);
        source.sendSuccess(() -> Messages.success("Loaded world ").append(Messages.highlight(entry.name())), true);
        return 1;
    }

    private static int executeUnload(CommandContext<CommandSourceStack> context) {
        WorldRegistry.WorldEntry entry = resolveWorld(context);
        if (entry == null) {
            return 0;
        }
        CommandSourceStack source = context.getSource();
        if (entry.unloaded()) {
            source.sendFailure(Messages.error("World '" + entry.name() + "' is already unloaded."));
            return 0;
        }
        DynamicDimensionManager.unloadLevel(source.getServer(), entry);
        source.sendSuccess(() -> Messages.success("Unloaded world ").append(Messages.highlight(entry.name())), true);
        return 1;
    }

    private static int executeTp(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = EntityArgument.getPlayer(context, "player");
        String worldName = StringArgumentType.getString(context, "world");
        return WsCommand.switchToWorld(context.getSource(), player, worldName);
    }

    private static UUID sourceKey(CommandSourceStack source) {
        return source.getEntity() instanceof ServerPlayer player ? player.getUUID() : CONSOLE_UUID;
    }

    private static int executeDeleteRequest(CommandContext<CommandSourceStack> context) {
        WorldRegistry.WorldEntry entry = resolveWorld(context);
        if (entry == null) {
            return 0;
        }
        CommandSourceStack source = context.getSource();
        PENDING_DELETES.put(sourceKey(source), new PendingDelete(entry.id(), System.currentTimeMillis()));

        source.sendSuccess(() -> Messages.error("Delete world '" + entry.name()
                + "' including ALL its data and stored inventories?"), false);
        source.sendSuccess(() -> Component.literal("  ")
                .append(Messages.runCommand("[Confirm]", "/wsc confirm", ChatFormatting.RED))
                .append(Component.literal("  "))
                .append(Messages.runCommand("[Cancel]", "/wsc cancel", ChatFormatting.GRAY))
                .append(Messages.info("  (expires in 30s)")), false);
        return 1;
    }

    private static int executeDeleteConfirm(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        PendingDelete pending = PENDING_DELETES.remove(sourceKey(source));
        if (pending == null || System.currentTimeMillis() - pending.requestedAt() > CONFIRM_TIMEOUT_MS) {
            source.sendFailure(Messages.error("Nothing to confirm (or the request expired)."));
            return 0;
        }
        WorldRegistry.WorldEntry entry = WorldRegistry.get(source.getServer()).byId(pending.worldId());
        if (entry == null) {
            source.sendFailure(Messages.error("World no longer exists."));
            return 0;
        }
        try {
            DynamicDimensionManager.deleteWorld(source.getServer(), entry);
        } catch (IOException e) {
            WorldSwitcherMod.LOGGER.error("Failed to delete world '{}'", entry.name(), e);
            source.sendFailure(Messages.error("Delete failed: " + e.getMessage()));
            return 0;
        }
        source.sendSuccess(() -> Messages.success("World '" + entry.name() + "' deleted."), true);
        return 1;
    }

    private static int executeDeleteCancel(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        boolean removed = PENDING_DELETES.remove(sourceKey(source)) != null;
        source.sendSuccess(() -> Messages.info(removed ? "Delete cancelled." : "Nothing to cancel."), false);
        return removed ? 1 : 0;
    }

    private static long folderSize(Path path) {
        if (!Files.exists(path)) {
            return 0;
        }
        try (Stream<Path> walk = Files.walk(path)) {
            return walk.filter(Files::isRegularFile).mapToLong(file -> {
                try {
                    return Files.size(file);
                } catch (IOException e) {
                    return 0;
                }
            }).sum();
        } catch (IOException e) {
            return 0;
        }
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        return String.format(Locale.ROOT, "%.1f %sB", bytes / Math.pow(1024, exp), "KMGT".charAt(exp - 1));
    }
}
