package arsqz.mod.client;

import arsqz.mod.client.galaxy.Discoveries;
import arsqz.mod.client.galaxy.GalaxyMapScreen;
import arsqz.mod.client.galaxy.ServerPlayers;
import arsqz.mod.client.galaxy.SpacePings;
import arsqz.mod.item.ModItems;
import arsqz.mod.net.DiscoveryInfoPacket;
import arsqz.mod.net.PingListPacket;
import arsqz.mod.net.PlayerLocationsPacket;

import com.mojang.brigadier.arguments.LongArgumentType;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.event.player.UseItemCallback;

import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionResult;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class TwoSixTwo_ArskizClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		// Right-clicking the Galaxy Map opens the universe map GUI (client only).
		UseItemCallback.EVENT.register((player, world, hand) -> {
			if (world.isClientSide() && player.getItemInHand(hand).is(ModItems.GALAXY_MAP)) {
				Minecraft.getInstance().setScreenAndShow(new GalaxyMapScreen());
				return InteractionResult.SUCCESS;
			}
			return InteractionResult.PASS;
		});

		// Cache the server's player roster for the map's Players tab.
		ClientPlayNetworking.registerGlobalReceiver(PlayerLocationsPacket.TYPE, (payload, context) -> {
			List<ServerPlayers.Entry> list = new ArrayList<>(payload.entries().size());
			for (PlayerLocationsPacket.Entry e : payload.entries()) {
				try {
					list.add(new ServerPlayers.Entry(UUID.fromString(e.uuid()), e.name(), e.dimKey(), e.location()));
				} catch (IllegalArgumentException ignored) {
					// skip a malformed uuid rather than dropping the whole list
				}
			}
			ServerPlayers.set(list);
		});

		// Cache planet discovery status ("" = uncharted) for the info panel.
		ClientPlayNetworking.registerGlobalReceiver(DiscoveryInfoPacket.TYPE, (payload, context) ->
				Discoveries.put(payload.planetWorldSeed(), payload.discoverer()));

		// Cache incoming space pings, routed by scope (universe vs. cluster).
		ClientPlayNetworking.registerGlobalReceiver(PingListPacket.TYPE, (payload, context) -> {
			if (payload.scope() == PingListPacket.SCOPE_UNIVERSE) {
				List<SpacePings.ClusterPing> list = new ArrayList<>(payload.entries().size());
				for (PingListPacket.Entry e : payload.entries()) {
					list.add(new SpacePings.ClusterPing(e.name(), e.value()));
				}
				SpacePings.setUniverse(list);
			} else {
				List<SpacePings.GalaxyPing> list = new ArrayList<>(payload.entries().size());
				for (PingListPacket.Entry e : payload.entries()) {
					list.add(new SpacePings.GalaxyPing(e.name(), e.value()));
				}
				SpacePings.setCluster(payload.contextCluster(), list);
			}
		});

		// Discovery claims and pings are per-server, so forget them when leaving.
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			Discoveries.clear();
			SpacePings.clear();
		});

		// Client command behind shared space beacons: a chat click runs
		// "/verse-goto <seed>" locally, opening the map at that planet.
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
				dispatcher.register(ClientCommands.literal("verse-goto")
						.then(ClientCommands.argument("seed", LongArgumentType.longArg())
								.executes(ctx -> {
									long seed = LongArgumentType.getLong(ctx, "seed");
									Minecraft mc = Minecraft.getInstance();
									GalaxyMapScreen screen = new GalaxyMapScreen();
									mc.setScreenAndShow(screen);
									screen.goToPlanetSeed(seed);
									return 1;
								}))));
	}
}
