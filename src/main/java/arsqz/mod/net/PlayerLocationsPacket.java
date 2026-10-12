package arsqz.mod.net;

import arsqz.mod.TwoSixTwo_Arskiz;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.ArrayList;
import java.util.List;

/**
 * Server -> Client: the online players and where each one currently is.
 *
 * <p>Each entry carries the player's UUID (as a string, for skin lookup), display
 * name, the dimension id they're in, and a human-readable location label such as
 * "Planet Fostruum Prime" or "Home world".
 */
public record PlayerLocationsPacket(List<Entry> entries) implements CustomPacketPayload {

    public record Entry(String uuid, String name, String dimKey, String location) {}

    public static final CustomPacketPayload.Type<PlayerLocationsPacket> TYPE =
            new CustomPacketPayload.Type<>(TwoSixTwo_Arskiz.id("player_locations"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PlayerLocationsPacket> CODEC =
            StreamCodec.of(
                    (buf, pkt) -> {
                        buf.writeVarInt(pkt.entries().size());
                        for (Entry e : pkt.entries()) {
                            buf.writeUtf(e.uuid());
                            buf.writeUtf(e.name());
                            buf.writeUtf(e.dimKey());
                            buf.writeUtf(e.location());
                        }
                    },
                    buf -> {
                        int n = buf.readVarInt();
                        List<Entry> list = new ArrayList<>(n);
                        for (int i = 0; i < n; i++) {
                            list.add(new Entry(buf.readUtf(), buf.readUtf(), buf.readUtf(), buf.readUtf()));
                        }
                        return new PlayerLocationsPacket(list);
                    });

    @Override
    public Type<PlayerLocationsPacket> type() {
        return TYPE;
    }
}
