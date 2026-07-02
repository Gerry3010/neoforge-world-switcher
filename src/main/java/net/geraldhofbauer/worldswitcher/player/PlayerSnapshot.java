package net.geraldhofbauer.worldswitcher.player;

import net.geraldhofbauer.worldswitcher.Config;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.game.ClientboundSetCarriedItemPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.level.GameType;

/**
 * Pure NBT capture/apply of the per-world player state: inventory (incl. armor/offhand/selected
 * slot), ender chest, XP, health, food, effects, fire/air/fall state, last position and optionally
 * game mode. Position is informational — teleporting is the caller's job.
 */
public final class PlayerSnapshot {

    private PlayerSnapshot() {
    }

    public static CompoundTag capture(ServerPlayer player) {
        CompoundTag tag = new CompoundTag();

        tag.put("inventory", player.getInventory().save(new ListTag()));
        tag.putInt("selectedSlot", player.getInventory().selected);
        tag.put("enderChest", player.getEnderChestInventory().createTag(player.registryAccess()));

        tag.putInt("xpLevel", player.experienceLevel);
        tag.putFloat("xpProgress", player.experienceProgress);
        tag.putInt("xpTotal", player.totalExperience);

        tag.putFloat("health", player.getHealth());
        CompoundTag foodTag = new CompoundTag();
        player.getFoodData().addAdditionalSaveData(foodTag);
        tag.put("food", foodTag);

        ListTag effects = new ListTag();
        for (MobEffectInstance effect : player.getActiveEffects()) {
            effects.add(effect.save());
        }
        tag.put("effects", effects);

        tag.putFloat("fallDistance", player.fallDistance);
        tag.putInt("fire", player.getRemainingFireTicks());
        tag.putInt("air", player.getAirSupply());
        tag.putInt("gamemode", player.gameMode.getGameModeForPlayer().getId());

        tag.putString("dimension", player.level().dimension().location().toString());
        tag.putDouble("posX", player.getX());
        tag.putDouble("posY", player.getY());
        tag.putDouble("posZ", player.getZ());
        tag.putFloat("yaw", player.getYRot());
        tag.putFloat("pitch", player.getXRot());

        return tag;
    }

    /** Applies a stored snapshot (everything except position). */
    public static void apply(ServerPlayer player, CompoundTag tag) {
        player.getInventory().load(tag.getList("inventory", Tag.TAG_COMPOUND));
        player.getInventory().selected = clampHotbarSlot(tag.getInt("selectedSlot"));
        player.getEnderChestInventory().fromTag(tag.getList("enderChest", Tag.TAG_COMPOUND),
                player.registryAccess());

        player.setExperienceLevels(tag.getInt("xpLevel"));
        player.experienceProgress = tag.getFloat("xpProgress");
        player.totalExperience = tag.getInt("xpTotal");

        player.setHealth(Math.min(Math.max(tag.getFloat("health"), 1.0F), player.getMaxHealth()));
        player.getFoodData().readAdditionalSaveData(tag.getCompound("food"));

        player.removeAllEffects();
        ListTag effects = tag.getList("effects", Tag.TAG_COMPOUND);
        for (int i = 0; i < effects.size(); i++) {
            MobEffectInstance effect = MobEffectInstance.load(effects.getCompound(i));
            if (effect != null) {
                player.forceAddEffect(effect, null);
            }
        }

        player.fallDistance = tag.getFloat("fallDistance");
        player.setRemainingFireTicks(tag.getInt("fire"));
        player.setAirSupply(tag.getInt("air"));
        if (Config.swapGamemode() && tag.contains("gamemode")) {
            player.setGameMode(GameType.byId(tag.getInt("gamemode")));
        }

        sync(player);
    }

    /** First visit of a world group: empty inventory, full health and hunger, no XP or effects. */
    public static void applyFresh(ServerPlayer player) {
        player.getInventory().clearContent();
        player.getEnderChestInventory().clearContent();

        player.setExperienceLevels(0);
        player.experienceProgress = 0.0F;
        player.totalExperience = 0;

        player.setHealth(player.getMaxHealth());
        CompoundTag foodTag = new CompoundTag();
        foodTag.putInt("foodLevel", 20);
        foodTag.putInt("foodTickTimer", 0);
        foodTag.putFloat("foodSaturationLevel", 5.0F);
        foodTag.putFloat("foodExhaustionLevel", 0.0F);
        player.getFoodData().readAdditionalSaveData(foodTag);

        player.removeAllEffects();
        player.fallDistance = 0.0F;
        player.setRemainingFireTicks(0);
        player.setAirSupply(player.getMaxAirSupply());

        sync(player);
    }

    private static void sync(ServerPlayer player) {
        player.inventoryMenu.broadcastFullState();
        if (player.containerMenu != player.inventoryMenu) {
            player.containerMenu.broadcastFullState();
        }
        player.connection.send(new ClientboundSetCarriedItemPacket(player.getInventory().selected));
    }

    private static int clampHotbarSlot(int slot) {
        return slot < 0 || slot > 8 ? 0 : slot;
    }

    /** Stored last-position accessors (used by the switch logic). */
    public record StoredPosition(String dimension, double x, double y, double z, float yaw, float pitch) {

        public static StoredPosition from(CompoundTag tag) {
            if (!tag.contains("dimension")) {
                return null;
            }
            return new StoredPosition(tag.getString("dimension"),
                    tag.getDouble("posX"), tag.getDouble("posY"), tag.getDouble("posZ"),
                    tag.getFloat("yaw"), tag.getFloat("pitch"));
        }
    }
}
