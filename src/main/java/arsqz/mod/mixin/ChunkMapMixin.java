package arsqz.mod.mixin;

import arsqz.mod.TwoSixTwo_Arskiz;

import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Makes each planet dimension generate deterministically from its own planet
 * world seed instead of the global world seed.
 *
 * <p>{@link ChunkMap}'s constructor calls {@link ServerLevel#getSeed()} once to
 * build the {@code RandomState} and structure state that drive all terrain,
 * biome and structure generation. For our {@code universemod:planet_*}
 * dimensions we swap in the seed encoded in the dimension id; every other level
 * keeps the real world seed.
 */
@Mixin(ChunkMap.class)
public class ChunkMapMixin {

    @Redirect(
            method = "<init>",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerLevel;getSeed()J"))
    private long universemod$planetSeed(ServerLevel level) {
        Long planetSeed = TwoSixTwo_Arskiz.planetSeedFromId(level.dimension().identifier());
        return planetSeed != null ? planetSeed : level.getSeed();
    }
}
