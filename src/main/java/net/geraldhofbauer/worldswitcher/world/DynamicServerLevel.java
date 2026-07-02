package net.geraldhofbauer.worldswitcher.world;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.progress.ChunkProgressListener;
import net.minecraft.world.level.CustomSpawner;
import net.minecraft.world.level.Level;
import net.minecraft.world.RandomSequences;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.ServerLevelData;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * A runtime-created world with its own seed.
 *
 * <p>{@link ServerLevel#getSeed()} normally returns the global server seed, which {@code ChunkMap}
 * bakes into its {@code RandomState} — new chunks in an imported world would generate with the
 * wrong seed. {@code ChunkMap} is constructed inside the {@code super(...)} call, before any
 * instance field of this subclass is assigned, so the override reads from {@link #PENDING_SEEDS}
 * during construction (keyed by dimension, which the base {@code Level} constructor sets early)
 * and from the instance field afterwards.</p>
 */
public class DynamicServerLevel extends ServerLevel {

    static final Map<ResourceKey<Level>, Long> PENDING_SEEDS = new ConcurrentHashMap<>();

    private final long seed;

    @SuppressWarnings("this-escape")
    public DynamicServerLevel(MinecraftServer server, Executor dispatcher,
                              LevelStorageSource.LevelStorageAccess levelStorageAccess,
                              ServerLevelData serverLevelData, ResourceKey<Level> dimension,
                              LevelStem levelStem, ChunkProgressListener progressListener,
                              long biomeZoomSeed, List<CustomSpawner> customSpawners,
                              @Nullable RandomSequences randomSequences) {
        super(server, dispatcher, levelStorageAccess, serverLevelData, dimension, levelStem,
                progressListener, false, biomeZoomSeed, customSpawners, false, randomSequences);
        this.seed = PENDING_SEEDS.remove(dimension);
    }

    @Override
    public long getSeed() {
        Long pending = PENDING_SEEDS.get(this.dimension());
        return pending != null ? pending : this.seed;
    }
}
