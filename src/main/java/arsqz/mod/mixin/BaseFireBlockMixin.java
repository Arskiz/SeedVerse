package arsqz.mod.mixin;

import arsqz.mod.TwoSixTwo_Arskiz;

import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseFireBlock;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Vanilla only lets Nether portals ignite in the literal overworld/nether (a
 * key-based check). Our planet overworlds and planet nethers have different
 * dimension keys, so portals wouldn't light there. Allow ignition in a planet's
 * overworld and nether (but not its End, matching vanilla).
 */
@Mixin(BaseFireBlock.class)
public class BaseFireBlockMixin {

    @Inject(method = "inPortalDimension", at = @At("HEAD"), cancellable = true)
    private static void universemod$allowPlanetPortals(Level level, CallbackInfoReturnable<Boolean> cir) {
        TwoSixTwo_Arskiz.PlanetLocation loc = TwoSixTwo_Arskiz.parsePlanet(level.dimension().identifier());
        if (loc != null
                && (loc.kind() == TwoSixTwo_Arskiz.PlanetKind.OVERWORLD
                || loc.kind() == TwoSixTwo_Arskiz.PlanetKind.NETHER)) {
            cir.setReturnValue(true);
        }
    }
}
