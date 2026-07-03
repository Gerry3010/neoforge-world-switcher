package net.geraldhofbauer.worldswitcher.world;

import com.mojang.serialization.Dynamic;
import net.geraldhofbauer.worldswitcher.Config;
import net.geraldhofbauer.worldswitcher.WorldSwitcherMod;
import net.minecraft.Util;
import net.minecraft.nbt.NbtOps;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.progress.ChunkProgressListener;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.border.BorderChangeListener;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.LevelEvent;

import javax.annotation.Nullable;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Creates, unloads and deletes runtime {@link ServerLevel}s for managed worlds.
 *
 * <p>Levels are inserted into the server's world map via the public NeoForge hooks
 * {@code forgeGetWorldMap()}/{@code markWorldsDirty()} — no registry modification. Every managed
 * world uses the vanilla {@code minecraft:overworld} dimension type, which is already known to
 * vanilla clients from login registry sync; the dimension key itself is never validated
 * client-side. All methods must be called on the server thread.</p>
 */
public final class DynamicDimensionManager {

    /** WorldBorder listeners we attached to the overworld border, so unload can detach them. */
    private static final Map<ResourceKey<Level>, BorderChangeListener> BORDER_LISTENERS = new HashMap<>();

    private static final ChunkProgressListener NO_OP_PROGRESS = new ChunkProgressListener() {
        @Override
        public void updateSpawnPos(ChunkPos center) {
        }

        @Override
        public void onStatusChange(ChunkPos chunkPos, @Nullable ChunkStatus chunkStatus) {
        }

        @Override
        public void start() {
        }

        @Override
        public void stop() {
        }
    };

    @Nullable
    private static Field storageSourceField;

    private DynamicDimensionManager() {
    }

    @Nullable
    public static ServerLevel getLoadedLevel(MinecraftServer server, WorldRegistry.WorldEntry entry) {
        return server.getLevel(entry.dimensionKey());
    }

    public static ServerLevel getOrCreateLevel(MinecraftServer server, WorldRegistry.WorldEntry entry) {
        ResourceKey<Level> key = entry.dimensionKey();
        ServerLevel existing = server.getLevel(key);
        if (existing != null) {
            return existing;
        }

        Holder<DimensionType> dimensionType = server.registryAccess()
                .registryOrThrow(Registries.DIMENSION_TYPE)
                .getHolderOrThrow(BuiltinDimensionTypes.OVERWORLD);
        // The generator instance is seed-independent: the seed enters through ChunkMap's
        // RandomState, built from level.getSeed(), which DynamicServerLevel overrides.
        ChunkGenerator generator = server.overworld().getChunkSource().getGenerator();
        LevelStem stem = new LevelStem(dimensionType, generator);

        // Spawn point, border, difficulty etc. derive from the overworld (like vanilla
        // nether/end); game rules, day time and weather are per-world when configured.
        boolean ownRules = Config.perWorldGameRules();
        boolean ownTimeWeather = Config.perWorldTimeAndWeather();
        GameRules rules = null;
        if (ownRules) {
            rules = entry.gameRules() != null
                    ? new GameRules(new Dynamic<>(NbtOps.INSTANCE, entry.gameRules()))
                    : server.getGameRules().copy();
        }
        long dayTime = entry.dayTime() >= 0 ? entry.dayTime() : server.overworld().getDayTime();
        boolean ownDifficulty = Config.perWorldDifficulty();
        Difficulty difficulty = ownDifficulty
                ? (entry.difficulty() != null ? entry.difficulty() : server.getWorldData().getDifficulty())
                : null;
        PerWorldLevelData levelData = new PerWorldLevelData(server.getWorldData(),
                server.getWorldData().overworldData(), rules, ownTimeWeather,
                dayTime, entry.clearWeatherTime(), entry.rainTime(), entry.thunderTime(),
                entry.raining(), entry.thundering(), difficulty);

        DynamicServerLevel.PENDING_SEEDS.put(key, entry.seed());
        DynamicServerLevel level;
        try {
            level = new DynamicServerLevel(server, Util.backgroundExecutor(), storageSource(server),
                    levelData, key, stem, NO_OP_PROGRESS,
                    BiomeManager.obfuscateSeed(entry.seed()), List.of(), null);
        } finally {
            DynamicServerLevel.PENDING_SEEDS.remove(key);
        }

        BorderChangeListener borderListener =
                new BorderChangeListener.DelegateBorderChangeListener(level.getWorldBorder());
        server.overworld().getWorldBorder().addListener(borderListener);
        BORDER_LISTENERS.put(key, borderListener);

        server.forgeGetWorldMap().put(key, level);
        server.markWorldsDirty();
        NeoForge.EVENT_BUS.post(new LevelEvent.Load(level));

        WorldRegistry registry = WorldRegistry.get(server);
        entry.attachLiveData(levelData);
        if (ownRules && entry.gameRules() == null) {
            // Freeze the inherited global rules as this world's own — later global changes
            // must not retroactively alter it.
            registry.setGameRules(entry.id(), rules.createTag());
        }
        if (ownDifficulty) {
            if (entry.difficulty() == null) {
                registry.setDifficulty(entry.id(), difficulty);
            }
            applySpawnSettings(server, level, difficulty);
        }
        if (entry.spawnPos() == null) {
            BlockPos spawn = computeSpawn(level);
            registry.setSpawn(entry.id(), spawn, 0.0F);
        }
        registry.setUnloaded(entry.id(), false);

        WorldSwitcherMod.LOGGER.info("Loaded world '{}' as dimension {} (seed {})",
                entry.name(), key.location(), entry.seed());
        return level;
    }

    public static void unloadLevel(MinecraftServer server, WorldRegistry.WorldEntry entry) {
        ResourceKey<Level> key = entry.dimensionKey();
        ServerLevel level = server.getLevel(key);
        if (level == null) {
            WorldRegistry.get(server).setUnloaded(entry.id(), true);
            return;
        }

        // Move all players out through the regular switch path (correct inventory swap);
        // copy the list — teleporting mutates it.
        var players = List.copyOf(level.players());
        ServerLevel overworld = server.overworld();
        for (var player : players) {
            net.geraldhofbauer.worldswitcher.player.PlayerStateManager.switchPlayer(player, overworld);
        }

        try {
            level.save(null, true, false);
        } catch (Exception e) {
            WorldSwitcherMod.LOGGER.error("Error saving world '{}' during unload", entry.name(), e);
        }
        entry.detachLiveData();
        NeoForge.EVENT_BUS.post(new LevelEvent.Unload(level));

        BorderChangeListener borderListener = BORDER_LISTENERS.remove(key);
        if (borderListener != null) {
            server.overworld().getWorldBorder().removeListener(borderListener);
        }

        server.forgeGetWorldMap().remove(key);
        server.markWorldsDirty();
        try {
            level.close();
        } catch (IOException e) {
            WorldSwitcherMod.LOGGER.error("Error closing world '{}'", entry.name(), e);
        }

        WorldRegistry.get(server).setUnloaded(entry.id(), true);
        WorldSwitcherMod.LOGGER.info("Unloaded world '{}' ({})", entry.name(), key.location());
    }

    public static void deleteWorld(MinecraftServer server, WorldRegistry.WorldEntry entry) throws IOException {
        unloadLevel(server, entry);

        Path dimensionPath = storageSource(server).getDimensionPath(entry.dimensionKey()).normalize();
        Path managedRoot = storageSource(server)
                .getDimensionPath(ResourceKey.create(Registries.DIMENSION,
                        net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(WorldRegistry.NAMESPACE, "x")))
                .getParent().normalize();
        if (!dimensionPath.startsWith(managedRoot)) {
            throw new IOException("Refusing to delete path outside managed dimensions: " + dimensionPath);
        }
        if (Files.exists(dimensionPath)) {
            try (Stream<Path> walk = Files.walk(dimensionPath)) {
                for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
        }

        WorldRegistry.get(server).remove(entry.id());
        net.geraldhofbauer.worldswitcher.player.PlayerStateStore.get(server).removeGroup(entry.id());
        WorldSwitcherMod.LOGGER.info("Deleted world '{}' ({})", entry.name(), entry.id());
    }

    public static void loadPersistedWorlds(MinecraftServer server) {
        if (!Config.autoLoadOnStartup()) {
            return;
        }
        for (WorldRegistry.WorldEntry entry : List.copyOf(WorldRegistry.get(server).entries())) {
            if (!entry.unloaded()) {
                try {
                    getOrCreateLevel(server, entry);
                } catch (Exception e) {
                    WorldSwitcherMod.LOGGER.error("Failed to load world '{}' at startup", entry.name(), e);
                }
            }
        }
    }

    /**
     * Advances a managed world's own day time, called from {@code LevelTickEvent.Post}. Weather
     * needs no equivalent — {@code ServerLevel.advanceWeatherCycle} already ticks per level and
     * writes through the (now real) {@link PerWorldLevelData} setters. We deliberately do not
     * use the {@code tickTime} constructor flag: it would also tick the shared game time and
     * scheduled events, which stay owned by the overworld.
     */
    public static void tickTime(DynamicServerLevel level) {
        PerWorldLevelData data = level.perWorldData();
        if (data == null || !data.ownTimeAndWeather()) {
            return;
        }
        if (level.getGameRules().getBoolean(GameRules.RULE_DAYLIGHT)) {
            data.setDayTime(data.getDayTime() + 1L);
        }
        // Time/weather mutate continuously while loaded; the registry pulls live values on save.
        WorldRegistry.get(level.getServer()).setDirty();
    }

    /**
     * Mirrors {@code MinecraftServer.updateMobSpawningFlags} but with the world's own
     * difficulty. When the GLOBAL difficulty is peaceful, {@code isSpawningMonsters()} cannot
     * distinguish "peaceful" from a spawn-monsters=false server property — we then allow
     * hostile spawns for non-peaceful managed worlds (documented edge).
     */
    public static void applySpawnSettings(MinecraftServer server, ServerLevel level, Difficulty difficulty) {
        boolean hostileAllowed = server.isSpawningMonsters()
                || server.getWorldData().getDifficulty() == Difficulty.PEACEFUL;
        level.setSpawnSettings(difficulty != Difficulty.PEACEFUL && hostileAllowed,
                server.isSpawningAnimals());
    }

    public static BlockPos spawnOf(ServerLevel level, WorldRegistry.WorldEntry entry) {
        return entry.spawnPos() != null ? entry.spawnPos() : computeSpawn(level);
    }

    private static BlockPos computeSpawn(ServerLevel level) {
        int y = level.getChunk(0, 0)
                .getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, 8, 8) + 1;
        if (y <= level.getMinBuildHeight() + 1) {
            y = level.getSeaLevel() + 1;
        }
        return new BlockPos(8, y, 8);
    }

    /**
     * {@code MinecraftServer.storageSource} is protected; NeoForge runs with official Mojang
     * mappings at runtime, so the field name is stable in dev and production.
     */
    public static LevelStorageSource.LevelStorageAccess storageSource(MinecraftServer server) {
        try {
            if (storageSourceField == null) {
                Field field = MinecraftServer.class.getDeclaredField("storageSource");
                field.setAccessible(true);
                storageSourceField = field;
            }
            return (LevelStorageSource.LevelStorageAccess) storageSourceField.get(server);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot access MinecraftServer.storageSource", e);
        }
    }
}
