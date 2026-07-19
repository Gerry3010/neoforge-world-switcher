package net.geraldhofbauer.worldswitcher.hooks;

import net.geraldhofbauer.worldswitcher.Config;
import net.geraldhofbauer.worldswitcher.WorldSwitcherMod;
import net.geraldhofbauer.worldswitcher.world.WorldRegistry;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

import javax.annotation.Nullable;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Runs admin-defined command hooks (see {@link CommandHooksConfig}) on world-lifecycle events.
 *
 * <p>Detection is occupancy-based: an internal map associates each world id (per
 * {@link WorldRegistry#groupOf}) to the set of players currently in it. Set transitions drive the
 * {@link HookEvent#FIRST_PLAYER_JOIN} (0 → 1) and {@link HookEvent#LAST_PLAYER_LEAVE} (1 → 0)
 * triggers; a change of world id drives {@link HookEvent#PLAYER_MOVED}. The occupancy is rebuilt
 * from the currently online players on {@link #load}/{@link #reload} so it stays correct across
 * restarts and edge cases.</p>
 */
public final class CommandHookService {

    private static final String CONFIG_FILE = "serverconfig/worldswitcher-hooks.json";

    /** world id → players currently in that world. */
    private static final Map<String, Set<UUID>> OCCUPANCY = new HashMap<>();

    private static volatile CommandHooksConfig config = CommandHooksConfig.empty();

    private CommandHookService() {
    }

    /** Path to {@code serverconfig/worldswitcher-hooks.json} for this world save. */
    private static Path configPath(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve(CONFIG_FILE);
    }

    /** Loads the hook config and rebuilds occupancy from the online players. */
    public static void load(MinecraftServer server) {
        config = CommandHooksConfig.load(configPath(server));
        rebuildOccupancy(server);
        WorldSwitcherMod.LOGGER.info("Command hooks loaded: {} global, {} per-world (worlds: {})",
                config.globalHookCount(), config.perWorldHookCount(), config.worldIds().size());
    }

    /** Reloads the hook config from disk and rebuilds occupancy; returns the freshly loaded config. */
    public static CommandHooksConfig reload(MinecraftServer server) {
        load(server);
        return config;
    }

    /** The currently loaded config (never null). */
    public static CommandHooksConfig config() {
        return config;
    }

    /** Rebuilds {@link #OCCUPANCY} from the players currently online — the source of truth. */
    private static void rebuildOccupancy(MinecraftServer server) {
        OCCUPANCY.clear();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            String worldId = WorldRegistry.groupOf(player.level().dimension());
            OCCUPANCY.computeIfAbsent(worldId, k -> new HashSet<>()).add(player.getUUID());
        }
    }

    /** Number of players currently tracked in {@code worldId}. */
    static int occupantCount(String worldId) {
        Set<UUID> set = OCCUPANCY.get(worldId);
        return set == null ? 0 : set.size();
    }

    // --- occupancy helpers -------------------------------------------------------------------

    /** The world id whose occupancy set currently contains {@code uuid}, or {@code null}. */
    @Nullable
    private static String currentWorldOf(UUID uuid) {
        for (Map.Entry<String, Set<UUID>> entry : OCCUPANCY.entrySet()) {
            if (entry.getValue().contains(uuid)) {
                return entry.getKey();
            }
        }
        return null;
    }

    /** Adds {@code uuid} to {@code worldId}; returns true if the world went from empty to occupied. */
    private static boolean addOccupant(String worldId, UUID uuid) {
        Set<UUID> set = OCCUPANCY.computeIfAbsent(worldId, k -> new HashSet<>());
        boolean wasEmpty = set.isEmpty();
        set.add(uuid);
        return wasEmpty;
    }

    /** Removes {@code uuid} from {@code worldId}; returns true if the world became empty. */
    private static boolean removeOccupant(String worldId, UUID uuid) {
        Set<UUID> set = OCCUPANCY.get(worldId);
        if (set == null || !set.remove(uuid)) {
            return false;
        }
        if (set.isEmpty()) {
            OCCUPANCY.remove(worldId);
            return true;
        }
        return false;
    }

    // --- transition handling -----------------------------------------------------------------

    /** A player entered {@code worldId} without a prior world (login). */
    private static void handleEnter(ServerPlayer player, String worldId) {
        if (addOccupant(worldId, player.getUUID())) {
            fire(HookEvent.FIRST_PLAYER_JOIN, worldId, player);
        }
    }

    /** A player left {@code worldId} to nowhere (logout). */
    private static void handleLeave(ServerPlayer player, String worldId) {
        if (removeOccupant(worldId, player.getUUID())) {
            fire(HookEvent.LAST_PLAYER_LEAVE, worldId, player);
        }
    }

    /**
     * A player moved to {@code toWorldId}. Fires, in order: LastPlayerLeave of the world it
     * emptied, FirstPlayerJoin of the world it filled, then PlayerMoved (vars = destination).
     */
    private static void handleMove(ServerPlayer player, String toWorldId) {
        UUID uuid = player.getUUID();
        String fromWorldId = currentWorldOf(uuid);
        if (toWorldId.equals(fromWorldId)) {
            return;
        }
        if (fromWorldId != null && removeOccupant(fromWorldId, uuid)) {
            fire(HookEvent.LAST_PLAYER_LEAVE, fromWorldId, player);
        }
        if (addOccupant(toWorldId, uuid)) {
            fire(HookEvent.FIRST_PLAYER_JOIN, toWorldId, player);
        }
        fire(HookEvent.PLAYER_MOVED, toWorldId, player);
    }

    // --- firing engine -----------------------------------------------------------------------

    /** Guards against a hook command that itself triggers another world event (e.g. runs /ws). */
    private static boolean firing;

    /**
     * Runs every hook configured for {@code event}/{@code worldId} (global first, then per-world),
     * substituting the four variables. Never propagates an exception to the caller.
     */
    private static void fire(HookEvent event, String worldId, ServerPlayer player) {
        if (!Config.enableCommandHooks()) {
            return;
        }
        var hooks = config.hooksFor(event, worldId);
        if (hooks.isEmpty()) {
            return;
        }
        if (firing) {
            WorldSwitcherMod.LOGGER.warn("Skipping nested command hooks for {} in '{}' "
                    + "(a hook command triggered another world event)", event, worldId);
            return;
        }
        MinecraftServer server = player.server;
        String worldName = worldName(server, worldId);
        String playerName = player.getGameProfile().getName();
        String playerUuid = player.getUUID().toString();
        firing = true;
        try {
            for (CommandHook hook : hooks) {
                String command = hook.command()
                        .replace("{{worldName}}", worldName)
                        .replace("{{worldId}}", worldId)
                        .replace("{{playerName}}", playerName)
                        .replace("{{playerUuid}}", playerUuid);
                HookRunAs runAs = hook.as() != null ? hook.as() : defaultRunAs();
                execute(server, worldId, player, command, runAs);
            }
        } finally {
            firing = false;
        }
    }

    private static void execute(MinecraftServer server, String worldId, ServerPlayer player,
                                String command, HookRunAs runAs) {
        try {
            CommandSourceStack source;
            if (runAs == HookRunAs.PLAYER) {
                source = player.createCommandSourceStack();
            } else {
                ServerLevel level = levelFor(server, worldId, player);
                BlockPos spawn = level.getSharedSpawnPos();
                source = server.createCommandSourceStack()
                        .withPermission(4)
                        .withLevel(level)
                        .withPosition(Vec3.atBottomCenterOf(spawn))
                        .withSuppressedOutput();
            }
            server.getCommands().performPrefixedCommand(source, command);
        } catch (Exception e) {
            WorldSwitcherMod.LOGGER.error("Command hook failed (world '{}', as {}): {}",
                    worldId, runAs, command, e);
        }
    }

    private static HookRunAs defaultRunAs() {
        HookRunAs parsed = HookRunAs.byName(Config.hookDefaultRunAs());
        return parsed != null ? parsed : HookRunAs.SERVER;
    }

    /** The level to position/run a SERVER hook in: the world's own level, else the player's. */
    private static ServerLevel levelFor(MinecraftServer server, String worldId, ServerPlayer fallback) {
        if (WorldRegistry.DEFAULT_GROUP.equals(worldId)) {
            return server.overworld();
        }
        WorldRegistry.WorldEntry entry = WorldRegistry.get(server).byId(worldId);
        if (entry != null) {
            ServerLevel level = server.getLevel(entry.dimensionKey());
            if (level != null) {
                return level;
            }
        }
        return fallback.serverLevel();
    }

    /** Display name for {@code worldId}: the managed world's name, or {@code default}. */
    private static String worldName(MinecraftServer server, String worldId) {
        if (WorldRegistry.DEFAULT_GROUP.equals(worldId)) {
            return WorldRegistry.DEFAULT_GROUP;
        }
        WorldRegistry.WorldEntry entry = WorldRegistry.get(server).byId(worldId);
        return entry != null ? entry.name() : worldId;
    }

    /** Event listener instance for the NeoForge game bus. */
    public static final Object EVENTS = new Object() {

        @SubscribeEvent
        public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
            if (event.getEntity() instanceof ServerPlayer player) {
                handleEnter(player, WorldRegistry.groupOf(player.level().dimension()));
            }
        }

        @SubscribeEvent
        public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
            if (!(event.getEntity() instanceof ServerPlayer player)) {
                return;
            }
            String worldId = currentWorldOf(player.getUUID());
            if (worldId == null) {
                worldId = WorldRegistry.groupOf(player.level().dimension());
            }
            handleLeave(player, worldId);
        }

        @SubscribeEvent(priority = EventPriority.LOWEST)
        public void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
            if (event.getEntity() instanceof ServerPlayer player) {
                handleMove(player, WorldRegistry.groupOf(event.getTo()));
            }
        }

        @SubscribeEvent(priority = EventPriority.LOWEST)
        public void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
            if (event.getEntity() instanceof ServerPlayer player) {
                // Respawn can land in another world without a PlayerChangedDimensionEvent.
                handleMove(player, WorldRegistry.groupOf(player.level().dimension()));
            }
        }
    };
}
