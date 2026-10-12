package arsqz.mod.mixin;

import arsqz.mod.TwoSixTwo_Arskiz;
import arsqz.mod.world.PlanetDimensionManager;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.NetherPortalBlock;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Keeps Nether portals inside a planet's own dimension set: a portal on a planet
 * leads to that planet's Nether, and returning comes back to that same planet -
 * instead of everyone sharing the single vanilla Nether.
 */
@Mixin(NetherPortalBlock.class)
public class NetherPortalBlockMixin {

    @Redirect(
            method = "getPortalDestination",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/MinecraftServer;getLevel(Lnet/minecraft/resources/ResourceKey;)Lnet/minecraft/server/level/ServerLevel;"))
    private ServerLevel universemod$routeNether(MinecraftServer server, ResourceKey<Level> requested,
                                                ServerLevel source, Entity entity, BlockPos pos) {
        ServerLevel routed = PlanetDimensionManager.routePortal(server, source, TwoSixTwo_Arskiz.PlanetKind.NETHER);
        return routed != null ? routed : server.getLevel(requested);
    }
}
