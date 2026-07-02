package net.geraldhofbauer.worldswitcher.world;

import net.geraldhofbauer.worldswitcher.WorldSwitcherMod;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.util.RandomSource;

import javax.annotation.Nullable;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Best-effort metadata from an imported world's {@code level.dat}. Parse failures never block an
 * import — they just fall back to a random seed and a computed spawn.
 */
public record LevelDatInfo(long seed, @Nullable BlockPos spawnPos, float spawnAngle, int dataVersion) {

    public static LevelDatInfo read(Path worldFolder) {
        Path levelDat = worldFolder.resolve("level.dat");
        long fallbackSeed = RandomSource.create().nextLong();
        if (!Files.isRegularFile(levelDat)) {
            return new LevelDatInfo(fallbackSeed, null, 0.0F, -1);
        }
        try {
            CompoundTag root = NbtIo.readCompressed(levelDat, NbtAccounter.unlimitedHeap());
            CompoundTag data = root.getCompound("Data");

            long seed;
            if (data.getCompound("WorldGenSettings").contains("seed")) {
                seed = data.getCompound("WorldGenSettings").getLong("seed");
            } else if (data.contains("RandomSeed")) {
                // pre-1.16 layout
                seed = data.getLong("RandomSeed");
            } else {
                seed = fallbackSeed;
            }

            BlockPos spawn = data.contains("SpawnX")
                    ? new BlockPos(data.getInt("SpawnX"), data.getInt("SpawnY"), data.getInt("SpawnZ"))
                    : null;
            return new LevelDatInfo(seed, spawn, data.getFloat("SpawnAngle"), data.getInt("DataVersion"));
        } catch (Exception e) {
            WorldSwitcherMod.LOGGER.warn("Could not parse {} — importing with a random seed", levelDat, e);
            return new LevelDatInfo(fallbackSeed, null, 0.0F, -1);
        }
    }
}
