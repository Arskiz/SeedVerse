package arsqz.mod;

import arsqz.mod.item.ModItems;
import arsqz.mod.net.JoinPlayerPacket;
import arsqz.mod.net.PlayerLocationsPacket;
import arsqz.mod.net.RequestPlayersPacket;
import arsqz.mod.net.DiscoveryInfoPacket;
import arsqz.mod.net.PingFocusPacket;
import arsqz.mod.net.PingListPacket;
import arsqz.mod.net.RequestDiscoveryPacket;
import arsqz.mod.net.ReturnHomePacket;
import arsqz.mod.net.ShareBeaconPacket;
import arsqz.mod.net.TravelToPlanetPacket;
import arsqz.mod.universe.NameGenerator;
import arsqz.mod.universe.UniverseMath;
import arsqz.mod.world.DiscoveryData;
import arsqz.mod.world.PlanetDimensionManager;
import arsqz.mod.world.PlayerTravelData;
import arsqz.mod.world.SpacePingTracker;

import com.mojang.brigadier.arguments.IntegerArgumentType;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class TwoSixTwo_Arskiz implements ModInitializer {
	public static final String MOD_ID = "universemod";

	// Scoreboard tag used to remember we already handed out the starter map,
	// so the player only gets it once per world (on first join / new world).
	private static final String GOT_MAP_TAG = "arsqz_got_galaxy_map";

	// This logger is used to write text to the console and the log file.
	// It is considered best practice to use your mod id as the logger's name.
	// That way, it's clear which mod wrote info, warnings, and errors.
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		// This code runs as soon as Minecraft is in a mod-load-ready state.
		ModItems.init();
		PlayerTravelData.init();
		ModConfig.load();

		// Re-register any planet dimensions that exist on disk before players join,
		// so logging off on a planet resumes there instead of suffocating you.
		ServerLifecycleEvents.SERVER_STARTED.register(PlanetDimensionManager::reloadExisting);

		// Give every player the Galaxy Map the first time they enter a world, and
		// rescue anyone who loaded inside solid blocks.
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			ServerPlayer player = handler.player;
			if (!player.entityTags().contains(GOT_MAP_TAG)) {
				player.addTag(GOT_MAP_TAG);
				ItemStack map = new ItemStack(ModItems.GALAXY_MAP);
				if (!player.addItem(map)) {
					player.drop(map, false);
				}
				LOGGER.info("Gave the Galaxy Map to {}.", player.getName().getString());
			}
			server.execute(() -> PlanetDimensionManager.ensureSafeSpawn(player));
		});

		// Commands: "/locate-verse" reports your position; "/return-home" sends
		// you back to the overworld (at your last position there).
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			dispatcher.register(Commands.literal("locate-verse").executes(ctx -> {
				CommandSourceStack src = ctx.getSource();
				ServerPlayer player = src.getPlayerOrException();
				ServerLevel level = (ServerLevel) player.level();
				// On a planet dimension the level's generation seed isn't the planet
				// seed (Nether/End are derived, and getSeed() reports the home world),
				// so resolve the planet identity straight from the dimension id.
				PlanetLocation here = parsePlanet(level.dimension().identifier());
				long seed = (here != null) ? here.seed() : level.getSeed();
				String[] loc = NameGenerator.locate(seed);

				// Plain-text version (no colour codes) for the copy-to-clipboard button.
				String plain = "Cluster: " + loc[0]
						+ "\nGalaxy: " + loc[1]
						+ "\nSystem: " + loc[2]
						+ "\nPlanet: " + loc[3]
						+ "\nSeed: " + seed;

				Component copyButton = Component.literal("[Copy to clipboard]")
						.withStyle(s -> s
								.withColor(net.minecraft.ChatFormatting.GREEN)
								.withBold(true)
								.withClickEvent(new net.minecraft.network.chat.ClickEvent.CopyToClipboard(plain))
								.withHoverEvent(new net.minecraft.network.chat.HoverEvent.ShowText(
										Component.literal("Copy your location and seed"))));

				Component msg = Component.literal(
						"\u00A7b\u00A7lYou are in the universe at:"
						+ "\n\u00A77Cluster: \u00A7f" + loc[0]
						+ "\n\u00A77Galaxy: \u00A7f" + loc[1]
						+ "\n\u00A77System: \u00A7f" + loc[2]
						+ "\n\u00A77Planet: \u00A7f" + loc[3]
						+ "\n\u00A77Seed: \u00A7f" + seed + "\n")
						.append(copyButton);
				src.sendSuccess(() -> msg, false);
				return 1;
			}));
			dispatcher.register(Commands.literal("return-home").executes(ctx -> {
				ServerPlayer player = ctx.getSource().getPlayerOrException();
				PlanetDimensionManager.returnHome(player);
				return 1;
			}));
			// "/universe-config planet-limit [<count>]" - view or (op) change how many
			// distinct planets each player may visit. 0 means unlimited.
			dispatcher.register(Commands.literal("universe-config")
					.then(Commands.literal("planet-limit")
							.executes(ctx -> {
								int limit = ModConfig.maxPlanetsPerPlayer();
								String msg = limit > 0
										? "\u00A7bPlanet-visit limit: \u00A7e" + limit + "\u00A7b per player."
										: "\u00A7bPlanet-visit limit: \u00A7eunlimited\u00A7b.";
								ctx.getSource().sendSuccess(() -> Component.literal(msg), false);
								return 1;
							})
							.then(Commands.argument("count", IntegerArgumentType.integer(0))
									.requires(src -> src.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
									.executes(ctx -> {
										int count = IntegerArgumentType.getInteger(ctx, "count");
										ModConfig.setMaxPlanetsPerPlayer(count);
										String msg = count > 0
												? "\u00A7aPlanet-visit limit set to \u00A7e" + count + "\u00A7a per player."
												: "\u00A7aPlanet-visit limit set to \u00A7eunlimited\u00A7a.";
										ctx.getSource().sendSuccess(() -> Component.literal(msg), true);
										return 1;
									}))));
		});

		// Travel networking: client asks to jump to a planet or go home; we
		// build/resolve the dimension and teleport on the server thread.
		PayloadTypeRegistry.serverboundPlay().register(TravelToPlanetPacket.TYPE, TravelToPlanetPacket.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(TravelToPlanetPacket.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			context.server().execute(() -> PlanetDimensionManager.travel(player, payload));
		});
		PayloadTypeRegistry.serverboundPlay().register(ReturnHomePacket.TYPE, ReturnHomePacket.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(ReturnHomePacket.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			context.server().execute(() -> PlanetDimensionManager.returnHome(player));
		});

		// Players tab: client asks for the roster + locations, and can request to
		// be teleported to another player. The locations packet flows S2C.
		PayloadTypeRegistry.clientboundPlay().register(PlayerLocationsPacket.TYPE, PlayerLocationsPacket.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(RequestPlayersPacket.TYPE, RequestPlayersPacket.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(RequestPlayersPacket.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			context.server().execute(() -> sendPlayerLocations(player));
		});
		PayloadTypeRegistry.serverboundPlay().register(JoinPlayerPacket.TYPE, JoinPlayerPacket.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(JoinPlayerPacket.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			String uuid = payload.targetUuid();
			context.server().execute(() -> {
				try {
					PlanetDimensionManager.joinPlayer(player, UUID.fromString(uuid));
				} catch (IllegalArgumentException ignored) {
					// malformed uuid from a bad client - ignore
				}
			});
		});

		// Space beacons: a player shares a planet, and everyone gets a clickable
		// chat message that jumps their galaxy map straight to it.
		PayloadTypeRegistry.serverboundPlay().register(ShareBeaconPacket.TYPE, ShareBeaconPacket.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(ShareBeaconPacket.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			long seed = payload.planetWorldSeed();
			String planetName = payload.planetName();
			context.server().execute(() -> broadcastBeacon(player, seed, planetName));
		});

		// Exploration claims: the client asks who discovered a planet; we answer
		// from the server-wide DiscoveryData ("" = still uncharted).
		PayloadTypeRegistry.clientboundPlay().register(DiscoveryInfoPacket.TYPE, DiscoveryInfoPacket.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(RequestDiscoveryPacket.TYPE, RequestDiscoveryPacket.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(RequestDiscoveryPacket.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			long seed = payload.planetWorldSeed();
			context.server().execute(() -> {
				ServerLevel overworld = context.server().overworld();
				String who = overworld.getDataStorage().computeIfAbsent(DiscoveryData.TYPE).discoverer(seed);
				ServerPlayNetworking.send(player, new DiscoveryInfoPacket(seed, who == null ? "" : who));
			});
		});

		// Space pings: clients report (event-driven) which galaxy they're inspecting;
		// we relay it to other players viewing the same cluster.
		PayloadTypeRegistry.clientboundPlay().register(PingListPacket.TYPE, PingListPacket.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(PingFocusPacket.TYPE, PingFocusPacket.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(PingFocusPacket.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			int level = payload.level();
			long cluster = payload.cluster();
			long galaxyLocal = payload.galaxyLocal();
			context.server().execute(() -> SpacePingTracker.update(player, level, cluster, galaxyLocal));
		});
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
				server.execute(() -> SpacePingTracker.remove(handler.player)));

		LOGGER.info("UniverseMod initialized.");
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}

	/** Announce a shared planet in chat, with the planet name clickable to view it. */
	private static void broadcastBeacon(ServerPlayer sharer, long seed, String planetName) {
		net.minecraft.server.MinecraftServer server = sharer.level().getServer();
		if (server == null) return;

		Component name = Component.literal(planetName).withStyle(s -> s
				.withColor(net.minecraft.ChatFormatting.AQUA)
				.withUnderlined(true)
				.withClickEvent(new net.minecraft.network.chat.ClickEvent.RunCommand("/verse-goto " + seed))
				.withHoverEvent(new net.minecraft.network.chat.HoverEvent.ShowText(
						Component.literal("Click to view this planet on your galaxy map"))));

		Component msg = Component.literal("\u00A7e\u2605 \u00A76" + sharer.getName().getString()
						+ "\u00A7r shared a space beacon to planet ")
				.append(name)
				.append(Component.literal("\u00A7r!"));
		server.getPlayerList().broadcastSystemMessage(msg, false);
	}

	/** Collect every online player's location and send it to the requester. */
	private static void sendPlayerLocations(ServerPlayer requester) {
		net.minecraft.server.MinecraftServer server = requester.level().getServer();
		if (server == null) return;
		List<PlayerLocationsPacket.Entry> entries = new ArrayList<>();
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			net.minecraft.resources.ResourceKey<Level> dim = p.level().dimension();
			entries.add(new PlayerLocationsPacket.Entry(
					p.getUUID().toString(),
					p.getName().getString(),
					dim.identifier().toString(),
					locationLabel(dim)));
		}
		ServerPlayNetworking.send(requester, new PlayerLocationsPacket(entries));
	}

	/** A human-readable name for the dimension a player is standing in. */
	private static String locationLabel(net.minecraft.resources.ResourceKey<Level> dim) {
		PlanetLocation loc = parsePlanet(dim.identifier());
		if (loc != null) {
			String planet = NameGenerator.locate(loc.seed())[3];
			return switch (loc.kind()) {
				case OVERWORLD -> "Planet " + planet;
				case NETHER -> "Planet " + planet + " (Nether)";
				case END -> "Planet " + planet + " (End)";
			};
		}
		if (dim == Level.OVERWORLD) return "Home world";
		if (dim == Level.NETHER) return "The Nether";
		if (dim == Level.END) return "The End";
		return dim.identifier().getPath();
	}

	/**
	 * The dimension id for a planet's world seed. Negative seeds are encoded with
	 * an {@code n} prefix since resource paths can't carry a leading sign cleanly.
	 * Shared by client (display) and server (dimension creation) so they agree.
	 */
	/** The three sub-dimensions a planet can have. */
	public enum PlanetKind { OVERWORLD, NETHER, END }

	/** A parsed planet dimension id: which planet, and which of its sub-worlds. */
	public record PlanetLocation(long seed, PlanetKind kind) {}

	private static final long NETHER_SALT = 0x4E45544845L; // distinct gen seed per kind
	private static final long END_SALT = 0x454E44L;

	private static String enc(long seed) {
		return seed < 0 ? "n" + Long.toUnsignedString(-seed) : Long.toUnsignedString(seed);
	}

	/** Dimension id for a planet's overworld. */
	public static Identifier planetDimensionId(long seed) {
		return id("planet_" + enc(seed));
	}

	/** Dimension id for a planet's own Nether. */
	public static Identifier planetNetherId(long seed) {
		return id("planet_" + enc(seed) + "_nether");
	}

	/** Dimension id for a planet's own End. */
	public static Identifier planetEndId(long seed) {
		return id("planet_" + enc(seed) + "_end");
	}

	/** Parse any of our planet dimension ids back into (seed, kind), or null. */
	public static PlanetLocation parsePlanet(Identifier dimensionId) {
		if (!MOD_ID.equals(dimensionId.getNamespace())) return null;
		String path = dimensionId.getPath();
		if (!path.startsWith("planet_")) return null;
		String rest = path.substring("planet_".length());
		PlanetKind kind = PlanetKind.OVERWORLD;
		if (rest.endsWith("_nether")) {
			kind = PlanetKind.NETHER;
			rest = rest.substring(0, rest.length() - "_nether".length());
		} else if (rest.endsWith("_end")) {
			kind = PlanetKind.END;
			rest = rest.substring(0, rest.length() - "_end".length());
		}
		try {
			boolean negative = rest.startsWith("n");
			if (negative) rest = rest.substring(1);
			long v = Long.parseUnsignedLong(rest);
			return new PlanetLocation(negative ? -v : v, kind);
		} catch (NumberFormatException e) {
			return null;
		}
	}

	/**
	 * The world-generation seed for a planet dimension id. The overworld uses the
	 * planet seed directly; the Nether and End derive their own deterministic
	 * seeds so each planet's sub-worlds are unique. Used by the ChunkMap mixin.
	 */
	public static Long planetSeedFromId(Identifier dimensionId) {
		PlanetLocation loc = parsePlanet(dimensionId);
		if (loc == null) return null;
		return switch (loc.kind()) {
			case OVERWORLD -> loc.seed();
			case NETHER -> UniverseMath.mix64(loc.seed() ^ NETHER_SALT);
			case END -> UniverseMath.mix64(loc.seed() ^ END_SALT);
		};
	}
}
