package arsqz.mod.item;

import arsqz.mod.TwoSixTwo_Arskiz;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;

import java.util.function.Function;

/**
 * Registers the mod's items.
 *
 * <p>Currently just the Galaxy Map: the item players use to open the
 * universe map GUI. Opening the screen is handled client-side (see the
 * client initializer) so this stays a plain item on the server.
 */
public final class ModItems {

    private ModItems() {}

    public static final Item GALAXY_MAP = register("galaxy_map", Item::new);

    private static Item register(String path, Function<Item.Properties, Item> factory) {
        ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, TwoSixTwo_Arskiz.id(path));
        Item item = factory.apply(new Item.Properties().setId(key));
        return Registry.register(BuiltInRegistries.ITEM, key, item);
    }

    /** Called from the mod initializer to force class-loading / registration. */
    public static void init() {
        TwoSixTwo_Arskiz.LOGGER.info("Registered mod items.");
    }
}
