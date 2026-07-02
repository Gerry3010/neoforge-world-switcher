package net.geraldhofbauer.worldswitcher.command;

import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.geraldhofbauer.worldswitcher.world.WorldRegistry;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;

import java.util.ArrayList;
import java.util.List;

public final class WorldSuggestions {

    /** All registered world names plus the "default" group — for /ws and /wsc tp. */
    public static final SuggestionProvider<CommandSourceStack> SWITCH_TARGETS = (context, builder) -> {
        List<String> names = new ArrayList<>();
        names.add(WorldRegistry.DEFAULT_GROUP);
        for (WorldRegistry.WorldEntry entry : WorldRegistry.get(context.getSource().getServer()).entries()) {
            names.add(entry.name());
        }
        return SharedSuggestionProvider.suggest(names, builder);
    };

    /** All registered world names — for /wsc info/unload/delete/rename. */
    public static final SuggestionProvider<CommandSourceStack> REGISTERED_WORLDS = (context, builder) ->
            SharedSuggestionProvider.suggest(
                    WorldRegistry.get(context.getSource().getServer()).entries().stream()
                            .map(WorldRegistry.WorldEntry::name),
                    builder);

    /** Only unloaded registered worlds — for /wsc load. */
    public static final SuggestionProvider<CommandSourceStack> UNLOADED_WORLDS = (context, builder) ->
            SharedSuggestionProvider.suggest(
                    WorldRegistry.get(context.getSource().getServer()).entries().stream()
                            .filter(WorldRegistry.WorldEntry::unloaded)
                            .map(WorldRegistry.WorldEntry::name),
                    builder);

    private WorldSuggestions() {
    }
}
