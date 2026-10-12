package arsqz.mod.mixin;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelStorageSource;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;
import java.util.concurrent.Executor;

/**
 * Exposes the internals needed to spin up a {@link ServerLevel} at runtime:
 * the level map to register into, the server's worker executor, and the save
 * storage handle.
 */
@Mixin(MinecraftServer.class)
public interface MinecraftServerAccessor {

    @Accessor("levels")
    Map<ResourceKey<Level>, ServerLevel> getLevels();

    @Accessor("executor")
    Executor getExecutor();

    @Accessor("storageSource")
    LevelStorageSource.LevelStorageAccess getStorageSource();
}
