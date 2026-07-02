package net.geraldhofbauer.worldswitcher.world;

import net.geraldhofbauer.worldswitcher.WorldSwitcherMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Persistent registry of all managed worlds, stored in {@code world/data/worldswitcher_worlds.dat}.
 *
 * <p>Each world has a fixed {@code id} (dimension key {@code worldswitcher:<id>}, inventory group)
 * and a user-facing {@code name} that can be renamed freely without touching stored player state
 * or bed spawns.</p>
 */
public class WorldRegistry extends SavedData {

    public static final String NAMESPACE = WorldSwitcherMod.MODID;
    public static final String DEFAULT_GROUP = "default";
    public static final Pattern ID_PATTERN = Pattern.compile("[a-z0-9_-]{1,32}");

    private static final String DATA_NAME = "worldswitcher_worlds";

    /** id → entry, insertion-ordered for stable /wsc list output. */
    private final Map<String, WorldEntry> entries = new LinkedHashMap<>();

    public static final class WorldEntry {
        private final String id;
        private String name;
        private long seed;
        @Nullable
        private BlockPos spawnPos;
        private float spawnAngle;
        private boolean unloaded;
        private final long importedAt;
        private final String sourcePath;

        public WorldEntry(String id, String name, long seed, @Nullable BlockPos spawnPos, float spawnAngle,
                          boolean unloaded, long importedAt, String sourcePath) {
            this.id = id;
            this.name = name;
            this.seed = seed;
            this.spawnPos = spawnPos;
            this.spawnAngle = spawnAngle;
            this.unloaded = unloaded;
            this.importedAt = importedAt;
            this.sourcePath = sourcePath;
        }

        public String id() {
            return id;
        }

        public String name() {
            return name;
        }

        public long seed() {
            return seed;
        }

        @Nullable
        public BlockPos spawnPos() {
            return spawnPos;
        }

        public float spawnAngle() {
            return spawnAngle;
        }

        public boolean unloaded() {
            return unloaded;
        }

        public long importedAt() {
            return importedAt;
        }

        public String sourcePath() {
            return sourcePath;
        }

        public ResourceKey<Level> dimensionKey() {
            return ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
                    ResourceLocation.fromNamespaceAndPath(NAMESPACE, id));
        }
    }

    public static WorldRegistry get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(WorldRegistry::new, WorldRegistry::load), DATA_NAME);
    }

    public static WorldRegistry load(CompoundTag tag, HolderLookup.Provider registries) {
        WorldRegistry registry = new WorldRegistry();
        ListTag list = tag.getList("worlds", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entryTag = list.getCompound(i);
            BlockPos spawn = entryTag.contains("spawnX")
                    ? new BlockPos(entryTag.getInt("spawnX"), entryTag.getInt("spawnY"), entryTag.getInt("spawnZ"))
                    : null;
            WorldEntry entry = new WorldEntry(
                    entryTag.getString("id"),
                    entryTag.getString("name"),
                    entryTag.getLong("seed"),
                    spawn,
                    entryTag.getFloat("spawnAngle"),
                    entryTag.getBoolean("unloaded"),
                    entryTag.getLong("importedAt"),
                    entryTag.getString("sourcePath"));
            registry.entries.put(entry.id(), entry);
        }
        return registry;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (WorldEntry entry : entries.values()) {
            CompoundTag entryTag = new CompoundTag();
            entryTag.putString("id", entry.id);
            entryTag.putString("name", entry.name);
            entryTag.putLong("seed", entry.seed);
            if (entry.spawnPos != null) {
                entryTag.putInt("spawnX", entry.spawnPos.getX());
                entryTag.putInt("spawnY", entry.spawnPos.getY());
                entryTag.putInt("spawnZ", entry.spawnPos.getZ());
            }
            entryTag.putFloat("spawnAngle", entry.spawnAngle);
            entryTag.putBoolean("unloaded", entry.unloaded);
            entryTag.putLong("importedAt", entry.importedAt);
            entryTag.putString("sourcePath", entry.sourcePath);
            list.add(entryTag);
        }
        tag.put("worlds", list);
        return tag;
    }

    public Collection<WorldEntry> entries() {
        return entries.values();
    }

    @Nullable
    public WorldEntry byId(String id) {
        return entries.get(id);
    }

    /** Resolves a user-facing name (falls back to id lookup so both always work). */
    @Nullable
    public WorldEntry byName(String name) {
        for (WorldEntry entry : entries.values()) {
            if (entry.name.equalsIgnoreCase(name)) {
                return entry;
            }
        }
        return entries.get(name);
    }

    public boolean nameTaken(String name) {
        return byName(name) != null;
    }

    public void put(WorldEntry entry) {
        entries.put(entry.id(), entry);
        setDirty();
    }

    public void remove(String id) {
        if (entries.remove(id) != null) {
            setDirty();
        }
    }

    public void rename(String id, String newName) {
        WorldEntry entry = entries.get(id);
        if (entry != null) {
            entry.name = newName;
            setDirty();
        }
    }

    public void setUnloaded(String id, boolean unloaded) {
        WorldEntry entry = entries.get(id);
        if (entry != null && entry.unloaded != unloaded) {
            entry.unloaded = unloaded;
            setDirty();
        }
    }

    public void setSpawn(String id, BlockPos pos, float angle) {
        WorldEntry entry = entries.get(id);
        if (entry != null) {
            entry.spawnPos = pos;
            entry.spawnAngle = angle;
            setDirty();
        }
    }

    /**
     * Inventory group of a dimension: all vanilla dimensions share the {@value #DEFAULT_GROUP}
     * group, every managed world is its own group (keyed by its fixed id).
     */
    public static String groupOf(ResourceKey<Level> dimension) {
        ResourceLocation location = dimension.location();
        return NAMESPACE.equals(location.getNamespace()) ? location.getPath() : DEFAULT_GROUP;
    }
}
