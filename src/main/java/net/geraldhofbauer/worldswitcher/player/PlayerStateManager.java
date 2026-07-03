package net.geraldhofbauer.worldswitcher.player;

import net.geraldhofbauer.worldswitcher.Config;
import net.geraldhofbauer.worldswitcher.WorldSwitcherMod;
import net.geraldhofbauer.worldswitcher.command.GameRuleHelper;
import net.geraldhofbauer.worldswitcher.util.Messages;
import net.geraldhofbauer.worldswitcher.world.DynamicDimensionManager;
import net.geraldhofbauer.worldswitcher.world.WorldRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityTravelToDimensionEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Executes world switches and keeps per-group player state consistent across every way a player
 * can cross a group boundary: {@code /ws}, portals/other-mod teleports, death respawns and
 * logins after a world was unloaded or deleted.
 */
public final class PlayerStateManager {

    /** Players currently teleported by {@link #switchPlayer} — their travel events are ours. */
    private static final Set<UUID> SWITCHING = ConcurrentHashMap.newKeySet();

    /**
     * Snapshot captured pre-teleport for portal/other-mod group crossings, committed
     * post-travel. {@code moddedApplied} = the target group's modded state was already applied
     * pre-teleport (so mods' own join-syncs send fresh data to the client).
     */
    private record PendingCross(String fromGroup, CompoundTag snapshot, boolean moddedApplied) {
    }

    private static final Map<UUID, PendingCross> PENDING_CROSS = new HashMap<>();

    /**
     * Respawn/login: the modded state that belongs to the OLD group, captured before the live
     * modded state is swapped early (in Clone / at level join). Merged into the old group's
     * snapshot by the later respawn/login handler.
     */
    private static final Map<UUID, CompoundTag> PENDING_MODDED = new HashMap<>();

    private PlayerStateManager() {
    }

    /** Event listener instance for the NeoForge game bus. */
    public static final Object EVENTS = new Object() {

        /**
         * LOWEST priority: runs after every potential canceller, so the modded pre-swap below
         * only happens for teleports that will actually proceed.
         */
        @SubscribeEvent(priority = EventPriority.LOWEST)
        public void onTravelToDimension(EntityTravelToDimensionEvent event) {
            if (!(event.getEntity() instanceof ServerPlayer player) || SWITCHING.contains(player.getUUID())) {
                return;
            }
            String fromGroup = WorldRegistry.groupOf(player.level().dimension());
            String toGroup = WorldRegistry.groupOf(event.getDimension());
            if (fromGroup.equals(toGroup) || !Config.separateInventories()) {
                return;
            }
            if (!Config.handlePortalGroupChanges()) {
                event.setCanceled(true);
                player.sendSystemMessage(Messages.error("You cannot travel between worlds this way — use /ws."));
                return;
            }
            // Capture now — position and state are still pre-teleport.
            CompoundTag snapshot = PlayerSnapshot.capture(player);
            boolean moddedApplied = false;
            if (ModdedPlayerState.anyEnabled()) {
                // Modded state must be live before the destination-level join (mods like
                // Curios sync their client view there).
                applyModded(player, PlayerStateStore.get(player.server).getSnapshot(player.getUUID(), toGroup));
                moddedApplied = true;
            }
            PENDING_CROSS.put(player.getUUID(), new PendingCross(fromGroup, snapshot, moddedApplied));
        }

        /**
         * The tiny window where a travel event fired but the teleport aborted (entity removed):
         * roll the modded state back before vanilla saves the player.
         */
        @SubscribeEvent
        public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
            if (!(event.getEntity() instanceof ServerPlayer player)) {
                return;
            }
            PendingCross pending = PENDING_CROSS.remove(player.getUUID());
            if (pending != null && pending.moddedApplied()) {
                ModdedPlayerState.apply(player, pending.snapshot());
            }
            PENDING_MODDED.remove(player.getUUID());
        }

        @SubscribeEvent
        public void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
            if (!(event.getEntity() instanceof ServerPlayer player)) {
                return;
            }
            // Runs for every dimension change (incl. /ws): the target level's client-mirrored
            // gamerule flags are only ever sent at login otherwise.
            GameRuleHelper.syncClientRules(player);
            if (SWITCHING.contains(player.getUUID())) {
                PENDING_CROSS.remove(player.getUUID());
                return;
            }
            PendingCross pending = PENDING_CROSS.remove(player.getUUID());
            String fromGroup = pending != null ? pending.fromGroup() : WorldRegistry.groupOf(event.getFrom());
            String toGroup = WorldRegistry.groupOf(event.getTo());
            PlayerStateStore store = PlayerStateStore.get(player.server);
            if (fromGroup.equals(toGroup)) {
                return;
            }
            if (!Config.separateInventories()) {
                store.setCurrentGroup(player.getUUID(), toGroup);
                return;
            }
            // pending == null: a teleport that bypassed EntityTravelToDimensionEvent — the live
            // state still belongs to the old group, only the stored position will be off.
            CompoundTag snapshot = pending != null ? pending.snapshot() : PlayerSnapshot.capture(player);
            store.putSnapshot(player.getUUID(), fromGroup, snapshot);
            CompoundTag stored = store.getSnapshot(player.getUUID(), toGroup);
            if (stored != null) {
                PlayerSnapshot.apply(player, stored);
            } else {
                // Keep the portal exit position — only the state is fresh.
                PlayerSnapshot.applyFresh(player);
            }
            if ((pending == null || !pending.moddedApplied()) && ModdedPlayerState.anyEnabled()) {
                // Bypassed travel event: swap modded state post-join — server-correct, but
                // mod client views (e.g. Curios HUD) may lag until the next join/relog.
                applyModded(player, stored);
            }
            store.setCurrentGroup(player.getUUID(), toGroup);
        }

        /**
         * Login-reconciliation half of the modded swap: must run at the level join (before
         * mods' own join-syncs at NORMAL priority, e.g. Curios) — the LoggedIn event fires too
         * late. Login joins are identified by "already registered in the player list + never
         * ticked"; respawn joins happen before list registration, teleport joins have ticked.
         */
        @SubscribeEvent(priority = EventPriority.HIGHEST)
        public void onEntityJoinLevel(EntityJoinLevelEvent event) {
            if (!(event.getEntity() instanceof ServerPlayer player)
                    || !(event.getLevel() instanceof ServerLevel level)
                    || !ModdedPlayerState.anyEnabled()
                    || player.tickCount != 0
                    || player.server.getPlayerList().getPlayer(player.getUUID()) != player) {
                return;
            }
            PlayerStateStore store = PlayerStateStore.get(player.server);
            String actualGroup = WorldRegistry.groupOf(level.dimension());
            String trackedGroup = store.getCurrentGroup(player.getUUID());
            if (trackedGroup.isEmpty() || trackedGroup.equals(actualGroup)) {
                return;
            }
            CompoundTag oldModded = new CompoundTag();
            ModdedPlayerState.capture(player, oldModded);
            PENDING_MODDED.put(player.getUUID(), oldModded);
            applyModded(player, store.getSnapshot(player.getUUID(), actualGroup));
        }

        @SubscribeEvent
        public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
            if (!(event.getEntity() instanceof ServerPlayer player) || !Config.separateInventories()) {
                PENDING_MODDED.remove(event.getEntity().getUUID());
                return;
            }
            PlayerStateStore store = PlayerStateStore.get(player.server);
            String actualGroup = WorldRegistry.groupOf(player.level().dimension());
            String trackedGroup = store.getCurrentGroup(player.getUUID());
            if (trackedGroup.isEmpty()) {
                store.setCurrentGroup(player.getUUID(), actualGroup);
                return;
            }
            if (trackedGroup.equals(actualGroup)) {
                return;
            }
            // The world the player logged out in was unloaded/deleted — vanilla dropped them into
            // the overworld, but their live NBT still belongs to the old group.
            WorldSwitcherMod.LOGGER.info("Reconciling {}: tracked group '{}' but spawned in '{}'",
                    player.getGameProfile().getName(), trackedGroup, actualGroup);
            CompoundTag oldSnapshot = PlayerSnapshot.capture(player);
            CompoundTag pendingModded = PENDING_MODDED.remove(player.getUUID());
            CompoundTag stored = store.getSnapshot(player.getUUID(), actualGroup);
            if (pendingModded != null) {
                // Live modded state was already swapped at the level join — the old group's
                // modded portion is the one captured there.
                ModdedPlayerState.mergeModdedInto(pendingModded, oldSnapshot);
            } else if (ModdedPlayerState.anyEnabled()) {
                // Join handler missed (edge case) — swap modded now; client view may lag.
                applyModded(player, stored);
            }
            store.putSnapshot(player.getUUID(), trackedGroup, oldSnapshot);
            if (stored != null) {
                PlayerSnapshot.apply(player, stored);
            } else {
                PlayerSnapshot.applyFresh(player);
            }
            store.setCurrentGroup(player.getUUID(), actualGroup);
            // Vanilla dropped the player into the overworld at their OLD world's coordinates —
            // often inside terrain or over a cliff; dying here would immediately shred the just
            // reconciled state. Move them like /ws would: last stored position, else spawn.
            MinecraftServer server = player.server;
            PlayerSnapshot.StoredPosition lastPos = stored != null && Config.restoreLastPosition()
                    ? PlayerSnapshot.StoredPosition.from(stored) : null;
            ServerLevel lastPosLevel = lastPos != null ? resolveLevel(server, lastPos.dimension()) : null;
            if (lastPosLevel != null
                    && WorldRegistry.groupOf(lastPosLevel.dimension()).equals(actualGroup)) {
                teleportGuarded(player, lastPosLevel, lastPos.x(), lastPos.y(), lastPos.z(),
                        lastPos.yaw(), lastPos.pitch());
            } else {
                BlockPos spawn = server.overworld().getSharedSpawnPos();
                teleportGuarded(player, server.overworld(), spawn.getX() + 0.5, spawn.getY(),
                        spawn.getZ() + 0.5, 0.0F, 0.0F);
            }
        }

        /**
         * Cross-world death with differing keepInventory rules: vanilla drops loot by the DEATH
         * level's rule but restores the inventory by the RESPAWN level's rule. With per-world
         * rules these can disagree — keep=true at death + keep=false at respawn would silently
         * destroy the items (neither dropped nor restored), the reverse would duplicate XP.
         * The death world's rule is authoritative.
         */
        @SubscribeEvent
        public void onClone(PlayerEvent.Clone event) {
            if (!event.isWasDeath()
                    || !(event.getEntity() instanceof ServerPlayer player)
                    || !(event.getOriginal() instanceof ServerPlayer original)) {
                return;
            }
            var deathRules = original.serverLevel().getGameRules();
            var respawnRules = player.serverLevel().getGameRules();
            if (deathRules == respawnRules) {
                return;
            }
            boolean keepAtDeath = deathRules.getBoolean(net.minecraft.world.level.GameRules.RULE_KEEPINVENTORY);
            boolean keepAtRespawn = respawnRules.getBoolean(net.minecraft.world.level.GameRules.RULE_KEEPINVENTORY);
            if (keepAtDeath && !keepAtRespawn) {
                // Nothing was dropped, but vanilla skipped the restore — copy it over.
                player.getInventory().replaceWith(original.getInventory());
                player.setExperienceLevels(original.experienceLevel);
                player.experienceProgress = original.experienceProgress;
                player.totalExperience = original.totalExperience;
            } else if (!keepAtDeath && keepAtRespawn) {
                // Loot and XP orbs already dropped at the death spot, but vanilla restored the
                // XP counters on top — zero them (the inventory was already emptied by the drop).
                player.setExperienceLevels(0);
                player.experienceProgress = 0.0F;
                player.totalExperience = 0;
            }
        }

        /**
         * Death-respawn half of the modded swap. LOWEST: NeoForge's own attachment copy
         * (copyOnDeath types, e.g. Curios) runs at NORMAL on the same event and must have
         * finished before we capture the post-death state. Runs before the respawned player
         * joins its level, so mods' join-syncs already send the respawn group's data.
         */
        @SubscribeEvent(priority = EventPriority.LOWEST)
        public void onCloneModded(PlayerEvent.Clone event) {
            if (!event.isWasDeath()
                    || !(event.getEntity() instanceof ServerPlayer player)
                    || !Config.separateInventories()
                    || !ModdedPlayerState.anyEnabled()) {
                return;
            }
            PlayerStateStore store = PlayerStateStore.get(player.server);
            String respawnGroup = WorldRegistry.groupOf(player.serverLevel().dimension());
            String previousGroup = store.getCurrentGroup(player.getUUID());
            if (previousGroup.isEmpty() || previousGroup.equals(respawnGroup)) {
                return;
            }
            // Post-death modded state belongs to the old group; onPlayerRespawn merges it into
            // that group's snapshot. currentGroup is switched there, not here.
            CompoundTag postDeathModded = new CompoundTag();
            ModdedPlayerState.capture(player, postDeathModded);
            PENDING_MODDED.put(player.getUUID(), postDeathModded);
            applyModded(player, store.getSnapshot(player.getUUID(), respawnGroup));
        }

        @SubscribeEvent
        public void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
            if (!(event.getEntity() instanceof ServerPlayer player)) {
                return;
            }
            // Respawning can land in another level without a PlayerChangedDimensionEvent.
            GameRuleHelper.syncClientRules(player);
            if (event.isEndConquered() || !Config.separateInventories()) {
                return;
            }
            PlayerStateStore store = PlayerStateStore.get(player.server);
            String respawnGroup = WorldRegistry.groupOf(player.level().dimension());
            String previousGroup = store.getCurrentGroup(player.getUUID());
            if (previousGroup.isEmpty() || previousGroup.equals(respawnGroup)) {
                return;
            }
            // Death in world X, respawn in another group (no bed there). The old group's snapshot
            // must become the post-death state — otherwise pre-death items would resurrect on the
            // next visit. The respawn group gets its stored state back.
            CompoundTag oldSnapshot = PlayerSnapshot.capture(player);
            CompoundTag pendingModded = PENDING_MODDED.remove(player.getUUID());
            CompoundTag stored = store.getSnapshot(player.getUUID(), respawnGroup);
            if (pendingModded != null) {
                // Live modded state was already swapped in the Clone handler — the old group's
                // modded portion is the post-death state captured there.
                ModdedPlayerState.mergeModdedInto(pendingModded, oldSnapshot);
            } else if (ModdedPlayerState.anyEnabled()) {
                applyModded(player, stored);
            }
            store.putSnapshot(player.getUUID(), previousGroup, oldSnapshot);
            if (stored != null) {
                PlayerSnapshot.apply(player, stored);
            } else {
                PlayerSnapshot.applyFresh(player);
            }
            store.setCurrentGroup(player.getUUID(), respawnGroup);
        }
    };

    /** Switches a player to another world group, swapping player state if configured. */
    public static void switchPlayer(ServerPlayer player, ServerLevel target) {
        MinecraftServer server = target.getServer();
        String fromGroup = WorldRegistry.groupOf(player.level().dimension());
        String toGroup = WorldRegistry.groupOf(target.dimension());
        PlayerStateStore store = PlayerStateStore.get(server);
        boolean separate = Config.separateInventories();

        if (fromGroup.equals(toGroup)) {
            BlockPos spawn = targetSpawn(server, target);
            teleportGuarded(player, target, spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5,
                    targetSpawnAngle(server, target), 0.0F);
            return;
        }

        CompoundTag stored = store.getSnapshot(player.getUUID(), toGroup);
        CompoundTag oldSnapshot = null;
        if (separate) {
            oldSnapshot = PlayerSnapshot.capture(player);
            store.putSnapshot(player.getUUID(), fromGroup, oldSnapshot);
            if (ModdedPlayerState.anyEnabled()) {
                // Modded state must be live before the destination-level join — mods like
                // Curios sync their client view when the player joins the target level.
                applyModded(player, stored);
            }
        }

        // Destination: stored last position (if enabled and still valid), else the world spawn.
        ServerLevel destLevel = target;
        double x;
        double y;
        double z;
        float yaw;
        float pitch;
        PlayerSnapshot.StoredPosition lastPos = stored != null && Config.restoreLastPosition()
                ? PlayerSnapshot.StoredPosition.from(stored) : null;
        ServerLevel lastPosLevel = lastPos != null ? resolveLevel(server, lastPos.dimension()) : null;
        if (lastPosLevel != null && WorldRegistry.groupOf(lastPosLevel.dimension()).equals(toGroup)) {
            // For the default group this may be the nether/end — return exactly where they left.
            destLevel = lastPosLevel;
            x = lastPos.x();
            y = lastPos.y();
            z = lastPos.z();
            yaw = lastPos.yaw();
            pitch = lastPos.pitch();
        } else {
            BlockPos spawn = targetSpawn(server, target);
            x = spawn.getX() + 0.5;
            y = spawn.getY();
            z = spawn.getZ() + 0.5;
            yaw = targetSpawnAngle(server, target);
            pitch = 0.0F;
        }

        try {
            teleportGuarded(player, destLevel, x, y, z, yaw, pitch);
        } catch (RuntimeException e) {
            if (separate && oldSnapshot != null && ModdedPlayerState.anyEnabled()) {
                // The player never left — roll the pre-applied modded state back.
                ModdedPlayerState.apply(player, oldSnapshot);
            }
            throw e;
        }

        if (separate) {
            if (stored != null) {
                PlayerSnapshot.apply(player, stored);
            } else {
                PlayerSnapshot.applyFresh(player);
            }
        }
        store.setCurrentGroup(player.getUUID(), toGroup);
    }

    /** Applies the modded portion of a snapshot, or modded defaults when none is stored. */
    private static void applyModded(ServerPlayer player, @Nullable CompoundTag stored) {
        if (stored != null) {
            ModdedPlayerState.apply(player, stored);
        } else {
            ModdedPlayerState.applyFresh(player);
        }
    }

    private static void teleportGuarded(ServerPlayer player, ServerLevel level,
                                        double x, double y, double z, float yaw, float pitch) {
        SWITCHING.add(player.getUUID());
        try {
            player.teleportTo(level, x, y, z, yaw, pitch);
        } finally {
            SWITCHING.remove(player.getUUID());
        }
    }

    @Nullable
    private static ServerLevel resolveLevel(MinecraftServer server, String dimensionId) {
        ResourceLocation location = ResourceLocation.tryParse(dimensionId);
        if (location == null) {
            return null;
        }
        return server.getLevel(ResourceKey.create(Registries.DIMENSION, location));
    }

    private static BlockPos targetSpawn(MinecraftServer server, ServerLevel target) {
        String group = WorldRegistry.groupOf(target.dimension());
        if (WorldRegistry.DEFAULT_GROUP.equals(group)) {
            return server.overworld().getSharedSpawnPos();
        }
        WorldRegistry.WorldEntry entry = WorldRegistry.get(server).byId(group);
        return entry != null
                ? DynamicDimensionManager.spawnOf(target, entry)
                : target.getSharedSpawnPos();
    }

    private static float targetSpawnAngle(MinecraftServer server, ServerLevel target) {
        String group = WorldRegistry.groupOf(target.dimension());
        WorldRegistry.WorldEntry entry = WorldRegistry.get(server).byId(group);
        return entry != null ? entry.spawnAngle() : 0.0F;
    }
}
