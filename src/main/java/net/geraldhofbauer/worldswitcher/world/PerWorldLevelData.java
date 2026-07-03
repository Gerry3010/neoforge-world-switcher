package net.geraldhofbauer.worldswitcher.world;

import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.storage.DerivedLevelData;
import net.minecraft.world.level.storage.ServerLevelData;
import net.minecraft.world.level.storage.WorldData;

import javax.annotation.Nullable;

/**
 * Level data for managed worlds: like the vanilla nether/end it derives most state from the
 * overworld, but (depending on config) owns its game rules, day time and weather.
 *
 * <p>Owning these here is all it takes for per-world behavior: {@code Level.getGameRules()} and
 * every weather/time read go through this object, weather already ticks per level
 * ({@code ServerLevel.advanceWeatherCycle} — the setters are just no-ops on a plain
 * {@code DerivedLevelData}), rain and time packets are already sent per dimension, and sleeping
 * writes back through {@code setDayTime}. Day time itself is advanced by
 * {@link DynamicDimensionManager#tickTime} (never via the {@code tickTime} constructor flag,
 * which would double-tick the shared game time and scheduled events).</p>
 */
public class PerWorldLevelData extends DerivedLevelData {

    /** Non-null = this world owns its rules; null = share the overworld's (config off). */
    @Nullable
    private final GameRules gameRules;
    private final boolean ownTimeAndWeather;

    private long dayTime;
    private int clearWeatherTime;
    private int rainTime;
    private int thunderTime;
    private boolean raining;
    private boolean thundering;

    public PerWorldLevelData(WorldData worldData, ServerLevelData wrapped,
                             @Nullable GameRules gameRules, boolean ownTimeAndWeather,
                             long dayTime, int clearWeatherTime, int rainTime, int thunderTime,
                             boolean raining, boolean thundering) {
        super(worldData, wrapped);
        this.gameRules = gameRules;
        this.ownTimeAndWeather = ownTimeAndWeather;
        this.dayTime = dayTime;
        this.clearWeatherTime = clearWeatherTime;
        this.rainTime = rainTime;
        this.thunderTime = thunderTime;
        this.raining = raining;
        this.thundering = thundering;
    }

    public boolean ownGameRules() {
        return gameRules != null;
    }

    public boolean ownTimeAndWeather() {
        return ownTimeAndWeather;
    }

    @Override
    public GameRules getGameRules() {
        return gameRules != null ? gameRules : super.getGameRules();
    }

    @Override
    public long getDayTime() {
        return ownTimeAndWeather ? dayTime : super.getDayTime();
    }

    @Override
    public void setDayTime(long time) {
        if (ownTimeAndWeather) {
            this.dayTime = time;
        }
    }

    @Override
    public int getClearWeatherTime() {
        return ownTimeAndWeather ? clearWeatherTime : super.getClearWeatherTime();
    }

    @Override
    public void setClearWeatherTime(int time) {
        if (ownTimeAndWeather) {
            this.clearWeatherTime = time;
        }
    }

    @Override
    public int getRainTime() {
        return ownTimeAndWeather ? rainTime : super.getRainTime();
    }

    @Override
    public void setRainTime(int time) {
        if (ownTimeAndWeather) {
            this.rainTime = time;
        }
    }

    @Override
    public boolean isRaining() {
        return ownTimeAndWeather ? raining : super.isRaining();
    }

    @Override
    public void setRaining(boolean isRaining) {
        if (ownTimeAndWeather) {
            this.raining = isRaining;
        }
    }

    @Override
    public int getThunderTime() {
        return ownTimeAndWeather ? thunderTime : super.getThunderTime();
    }

    @Override
    public void setThunderTime(int time) {
        if (ownTimeAndWeather) {
            this.thunderTime = time;
        }
    }

    @Override
    public boolean isThundering() {
        return ownTimeAndWeather ? thundering : super.isThundering();
    }

    @Override
    public void setThundering(boolean thundering) {
        if (ownTimeAndWeather) {
            this.thundering = thundering;
        }
    }
}
