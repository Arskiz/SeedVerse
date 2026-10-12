package arsqz.mod.world;

import arsqz.mod.ModConfig;
import arsqz.mod.TwoSixTwo_Arskiz;
import arsqz.mod.mixin.MinecraftServerAccessor;
import arsqz.mod.net.TravelToPlanetPacket;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.DerivedLevelData;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.ServerLevelData;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Resolves (or lazily creates) the {@link ServerLevel} for a planet seed and
 * teleports players there, preserving inventory, XP and everything else that
 * native dimension transfer keeps.
 *
 * <p>Each planet is its own dynamically-registered dimension. We reuse the
 * overworld's dimension <em>type</em> (so the client, which already has it from
 * login, can render the world without a fresh registry sync).
 */
public final class PlanetDimensionManager {

    private PlanetDimensionManager() {}

    /** Handle a travel request: create/resolve the dimension and move the player. */
    public static void travel(ServerPlayer player, TravelToPlanetPacket pkt) {
        MinecraftServer server = player.level().getServer();
        if (server == null) return;

        long seed = pkt.planetWorldSeed();

        // Enforce the server's per-player planet-visit limit. Revisiting a planet
        // you've already been to is always allowed; only brand-new planets count.
        int limit = ModConfig.maxPlanetsPerPlayer();
        if (limit > 0 && !PlayerTravelData.hasVisited(player, seed)
                && PlayerTravelData.visitedCount(player) >= limit) {
            player.sendSystemMessage(Component.literal(
                    "\u00A7cYou've reached this server's limit of \u00A7e" + limit
                            + "\u00A7c planets and can't travel to new ones. "
                            + "You can still return to planets you've already visited."));
            return;
        }

        ServerLevel level = getOrCreate(server, seed);
        if (level == null) {
            player.sendSystemMessage(Component.literal(
                    "\u00A7cCouldn't open a dimension for Planet " + pkt.planetName() + "."));
            return;
        }

        PlayerTravelData.markVisited(player, seed);

        // Claim the planet for whoever gets here first (server-wide record).
        server.overworld().getDataStorage().computeIfAbsent(DiscoveryData.TYPE)
                .recordIfAbsent(seed, player.getName().getString());

        moveTo(player, level, () -> findGroundSpawn(level));

        String suffix = "";
        if (limit > 0) {
            suffix = " \u00A77[" + PlayerTravelData.visitedCount(player) + "/" + limit + " planets]";
        }
        player.sendSystemMessage(Component.literal(
                "\u00A7bArrived at \u00A7ePlanet " + pkt.planetName()
                        + "\u00A7b (seed \u00A7f" + seed + "\u00A7b)." + suffix));
    }

    /**
     * Teleport {@code requester} to wherever {@code targetId} currently is (same
     * dimension, same position). Respects the per-player planet-visit limit for
     * brand-new planets, just like normal travel.
     */
    public static void joinPlayer(ServerPlayer requester, java.util.UUID targetId) {
        MinecraftServer server = requester.level().getServer();
        if (server == null) return;

        ServerPlayer target = server.getPlayerList().getPlayer(targetId);
        if (target == null || target == requester) return;
        if (!(target.level() instanceof ServerLevel level)) return;

        // If the destination is a brand-new planet, enforce the visit limit.
        TwoSixTwo_Arskiz.PlanetLocation loc = TwoSixTwo_Arskiz.parsePlanet(level.dimension().identifier());
        if (loc != null && loc.kind() == TwoSixTwo_Arskiz.PlanetKind.OVERWORLD) {
            long seed = loc.seed();
            int limit = ModConfig.maxPlanetsPerPlayer();
            if (limit > 0 && !PlayerTravelData.hasVisited(requester, seed)
                    && PlayerTravelData.visitedCount(requester) >= limit) {
                requester.sendSystemMessage(Component.literal(
                        "\u00A7cYou've reached this server's limit of \u00A7e" + limit
                                + "\u00A7c planets and can't travel to new ones."));
                return;
            }
            PlayerTravelData.markVisited(requester, seed);
        }

        PlayerTravelData.remember(requester, requester.level().dimension(), requester.position());
        Vec3 dest = target.position();
        level.getChunk((int) Math.floor(dest.x) >> 4, (int) Math.floor(dest.z) >> 4);
        requester.teleportTo(level, dest.x, dest.y, dest.z, Set.of(),
                target.getYRot(), target.getXRot(), false);
        requester.sendSystemMessage(Component.literal(
                "\u00A7bJoined \u00A7e" + target.getName().getString() + "\u00A7b."));
    }

    /** Send the player back to their home (overworld) world. */
    public static void returnHome(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        if (server == null) return;

        ServerLevel overworld = server.overworld();
        if (player.level() == overworld) {
            player.sendSystemMessage(Component.literal("\u00A7eYou're already on your home world."));
            return;
        }

        moveTo(player, overworld, () -> defaultSpawn(overworld));
        player.sendSystemMessage(Component.literal("\u00A7bReturned to your home world."));
    }

    /**
     * Record where the player is leaving from, then teleport them to the target
     * level - resuming their last position there, or {@code fallback} on a first
     * visit. Preserves inventory/XP via native dimension transfer.
     */
    private static void moveTo(ServerPlayer player, ServerLevel target, Supplier<Vec3> fallback) {
        PlayerTravelData.remember(player, player.level().dimension(), player.position());

        Vec3 dest = PlayerTravelData.recall(player, target.dimension());
        if (dest == null) dest = fallback.get();

        // Make sure the destination chunk is present before we place the player.
        target.getChunk((int) Math.floor(dest.x) >> 4, (int) Math.floor(dest.z) >> 4);
        player.teleportTo(target, dest.x, dest.y, dest.z, Set.of(),
                player.getYRot(), player.getXRot(), false);
    }

    /**
     * Default first-visit spawn, using Minecraft's own world-spawn algorithm:
     * the climate sampler picks a suitable land region (deterministic per planet
     * seed), then we drop onto solid, non-liquid ground there instead of a fixed
     * (0,0) column - so you no longer spawn in an ocean.
     */
    public static Vec3 findGroundSpawn(ServerLevel level) {
        BlockPos region = level.getChunkSource().randomState().sampler().findSpawnPosition();
        int ox = region.getX(), oz = region.getZ();

        // Expand outward from the chosen region looking for dry, solid ground.
        for (int r = 0; r <= 64; r += 8) {
            for (int dx = -r; dx <= r; dx += 8) {
                for (int dz = -r; dz <= r; dz += 8) {
                    if (r > 0 && Math.abs(dx) != r && Math.abs(dz) != r) continue; // ring edge only
                    Vec3 v = dryColumn(level, ox + dx, oz + dz);
                    if (v != null) return v;
                }
            }
        }
        // Fallback: the surface of the chosen column, whatever it is.
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, ox, oz);
        return new Vec3(ox + 0.5, y, oz + 0.5);
    }

    /** A standing position at (x,z) if the surface there is solid and dry, else null. */
    private static Vec3 dryColumn(ServerLevel level, int x, int z) {
        level.getChunk(x >> 4, z >> 4);
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
        BlockPos feet = new BlockPos(x, y, z);
        BlockState ground = level.getBlockState(feet.below());
        if (ground.blocksMotion()
                && ground.getFluidState().isEmpty()
                && level.getBlockState(feet).getFluidState().isEmpty()) {
            return new Vec3(x + 0.5, y, z + 0.5);
        }
        return null;
    }

    /** Default home spawn: the overworld's world spawn point. */
    private static Vec3 defaultSpawn(ServerLevel level) {
        var pos = level.getRespawnData().pos();
        return new Vec3(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
    }

    /**
     * Re-register every planet dimension that already exists on disk. Called at
     * server startup so a player who logged off on a planet finds it still there
     * on rejoin (and resumes at their exact position) instead of being dumped
     * into the overworld at the planet's coordinates - which suffocated them.
     */
    public static void reloadExisting(MinecraftServer server) {
        Path sample = ((MinecraftServerAccessor) server).getStorageSource()
                .getDimensionPath(ResourceKey.create(Registries.DIMENSION, TwoSixTwo_Arskiz.planetDimensionId(0L)));
        Path base = sample.getParent(); // .../dimensions/universemod
        if (base == null || !Files.isDirectory(base)) return;

        try (Stream<Path> children = Files.list(base)) {
            children.filter(Files::isDirectory).forEach(p -> restore(server, p.getFileName().toString()));
        } catch (IOException e) {
            TwoSixTwo_Arskiz.LOGGER.error("Failed to scan existing planet dimensions.", e);
        }
    }

    private static void restore(MinecraftServer server, String folderName) {
        TwoSixTwo_Arskiz.PlanetLocation loc;
        try {
            loc = TwoSixTwo_Arskiz.parsePlanet(TwoSixTwo_Arskiz.id(folderName));
        } catch (Exception ignored) {
            return; // not one of ours / invalid name
        }
        if (loc == null) return;
        switch (loc.kind()) {
            case OVERWORLD -> getOrCreate(server, loc.seed());
            case NETHER -> getOrCreateNether(server, loc.seed());
            case END -> getOrCreateEnd(server, loc.seed());
        }
    }

    /**
     * Route a portal so it stays within a planet's own dimension set: from a
     * planet's overworld a Nether/End portal goes to that planet's Nether/End,
     * and from a planet's Nether/End it returns to that planet's overworld.
     * Returns null for non-planet dimensions (vanilla behaviour is kept).
     */
    public static ServerLevel routePortal(MinecraftServer server, ServerLevel source,
                                          TwoSixTwo_Arskiz.PlanetKind portalKind) {
        TwoSixTwo_Arskiz.PlanetLocation loc = TwoSixTwo_Arskiz.parsePlanet(source.dimension().identifier());
        if (loc == null) return null;
        if (loc.kind() != TwoSixTwo_Arskiz.PlanetKind.OVERWORLD) {
            return getOrCreate(server, loc.seed());          // back to the planet
        }
        return portalKind == TwoSixTwo_Arskiz.PlanetKind.END
                ? getOrCreateEnd(server, loc.seed())
                : getOrCreateNether(server, loc.seed());
    }

    /**
     * Safety net on join: if the player loaded inside solid blocks (e.g. an old
     * save where they were bumped to the overworld at a planet's coordinates),
     * lift them to the surface so they don't suffocate.
     */
    public static void ensureSafeSpawn(ServerPlayer player) {
        if (!(player.level() instanceof ServerLevel level)) return;
        BlockPos pos = player.blockPosition();
        if (!isStuck(level, pos)) return;

        int y = level.getChunk(pos.getX() >> 4, pos.getZ() >> 4)
                .getHeight(Heightmap.Types.MOTION_BLOCKING, pos.getX(), pos.getZ()) + 1;
        player.teleportTo(level, pos.getX() + 0.5, y, pos.getZ() + 0.5, Set.of(),
                player.getYRot(), player.getXRot(), false);
        player.sendSystemMessage(Component.literal(
                "\u00A7eYou were stuck inside blocks - lifted you to the surface."));
    }

    private static boolean isStuck(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos).isSuffocating(level, pos)
                || level.getBlockState(pos.above()).isSuffocating(level, pos.above());
    }

    /** The planet's overworld level, creating it on first visit. */
    public static ServerLevel getOrCreate(MinecraftServer server, long seed) {
        return create(server, ResourceKey.create(Registries.DIMENSION, TwoSixTwo_Arskiz.planetDimensionId(seed)),
                server.overworld());
    }

    /** The planet's own Nether, generated from the vanilla Nether template. */
    public static ServerLevel getOrCreateNether(MinecraftServer server, long seed) {
        ServerLevel template = server.getLevel(Level.NETHER);
        if (template == null) return null;
        return create(server, ResourceKey.create(Registries.DIMENSION, TwoSixTwo_Arskiz.planetNetherId(seed)),
                template);
    }

    /** The planet's own End, generated from the vanilla End template. */
    public static ServerLevel getOrCreateEnd(MinecraftServer server, long seed) {
        ServerLevel template = server.getLevel(Level.END);
        if (template == null) return null;
        return create(server, ResourceKey.create(Registries.DIMENSION, TwoSixTwo_Arskiz.planetEndId(seed)),
                template);
    }

    /**
     * Create and register a dynamic level, copying the dimension type and chunk
     * generator from {@code template} (overworld/nether/end). Its generation seed
     * comes from the dimension id via the ChunkMap mixin, so each planet's
     * overworld/nether/end are all deterministic and unique.
     */
    private static ServerLevel create(MinecraftServer server, ResourceKey<Level> key, ServerLevel template) {
        ServerLevel existing = server.getLevel(key);
        if (existing != null) return existing;

        try {
            Holder<DimensionType> dimType = template.dimensionTypeRegistration();
            ChunkGenerator generator = template.getChunkSource().getGenerator();
            LevelStem stem = new LevelStem(dimType, generator);

            ServerLevelData overworldData = server.getWorldData().overworldData();
            DerivedLevelData data = new DerivedLevelData(server.getWorldData(), overworldData);

            MinecraftServerAccessor acc = (MinecraftServerAccessor) server;
            Executor executor = acc.getExecutor();
            LevelStorageSource.LevelStorageAccess storage = acc.getStorageSource();

            Long genSeed = TwoSixTwo_Arskiz.planetSeedFromId(key.identifier());
            long biomeSeed = BiomeManager.obfuscateSeed(genSeed != null ? genSeed : 0L);

            ServerLevel level = new ServerLevel(
                    server, executor, storage, data, key, stem,
                    /* isDebug */ false, biomeSeed, List.of(), /* tickTime */ false);

            acc.getLevels().put(key, level);
            TwoSixTwo_Arskiz.LOGGER.info("Created planet dimension {}.", key.identifier());
            return level;
        } catch (Throwable t) {
            TwoSixTwo_Arskiz.LOGGER.error("Failed to create planet dimension {}.", key.identifier(), t);
            return null;
        }
    }
}
