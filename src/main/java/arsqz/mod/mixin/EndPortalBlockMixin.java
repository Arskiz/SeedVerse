package arsqz.mod.mixin;

import arsqz.mod.TwoSixTwo_Arskiz;
import arsqz.mod.world.PlanetDimensionManager;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.EndPortalBlock;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.Inject;

/**
 * Keeps End portals inside a planet's own dimension set.
 *
 * <ul>
 *   <li><b>Entering</b> the End from a planet: the redirect below sends the
 *       target to that planet's End. The obsidian platform is built there
 *       automatically (it's created on the target level inside the method).</li>
 *   <li><b>Leaving</b> a planet's End: vanilla's direction check is keyed to the
 *       literal {@code the_end}, so it would try to send us to the End again. We
 *       intercept that case and return a transition back to the planet's surface.</li>
 * </ul>
 */
@Mixin(EndPortalBlock.class)
public class EndPortalBlockMixin {

    @Inject(method = "getPortalDestination", at = @At("HEAD"), cancellable = true)
    private void universemod$endExit(ServerLevel source, Entity entity, BlockPos pos,
                                     CallbackInfoReturnable<TeleportTransition> cir) {
        TwoSixTwo_Arskiz.PlanetLocation loc = TwoSixTwo_Arskiz.parsePlanet(source.dimension().identifier());
        if (loc == null || loc.kind() != TwoSixTwo_Arskiz.PlanetKind.END) return;

        ServerLevel planet = PlanetDimensionManager.getOrCreate(source.getServer(), loc.seed());
        if (planet == null) return;

        Vec3 dest = PlanetDimensionManager.findGroundSpawn(planet);
        cir.setReturnValue(new TeleportTransition(planet, dest, Vec3.ZERO,
                entity.getYRot(), entity.getXRot(), TeleportTransition.DO_NOTHING));
    }

    @Redirect(
            method = "getPortalDestination",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/MinecraftServer;getLevel(Lnet/minecraft/resources/ResourceKey;)Lnet/minecraft/server/level/ServerLevel;"))
    private ServerLevel universemod$routeEnd(MinecraftServer server, ResourceKey<Level> requested,
                                             ServerLevel source, Entity entity, BlockPos pos) {
        ServerLevel routed = PlanetDimensionManager.routePortal(server, source, TwoSixTwo_Arskiz.PlanetKind.END);
        return routed != null ? routed : server.getLevel(requested);
    }
}
