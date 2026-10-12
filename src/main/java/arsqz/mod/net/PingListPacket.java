package arsqz.mod.net;

import arsqz.mod.TwoSixTwo_Arskiz;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.ArrayList;
import java.util.List;

/**
 * Server -> Client: the pings to draw, tailored to the recipient's current view.
 *
 * <ul>
 *   <li>{@link #SCOPE_UNIVERSE}: other players are shown over the cluster they're
 *       in - each entry's {@code value} is that cluster index.</li>
 *   <li>{@link #SCOPE_CLUSTER}: other players in {@code contextCluster} inspecting
 *       a galaxy - each entry's {@code value} is the local galaxy index.</li>
 * </ul>
 */
public record PingListPacket(int scope, long contextCluster, List<Entry> entries) implements CustomPacketPayload {

    public static final int SCOPE_UNIVERSE = 0;
    public static final int SCOPE_CLUSTER = 1;

    public record Entry(String name, long value) {}

    public static final CustomPacketPayload.Type<PingListPacket> TYPE =
            new CustomPacketPayload.Type<>(TwoSixTwo_Arskiz.id("ping_list"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PingListPacket> CODEC =
            StreamCodec.of(
                    (buf, pkt) -> {
                        buf.writeVarInt(pkt.scope());
                        buf.writeVarLong(pkt.contextCluster());
                        buf.writeVarInt(pkt.entries().size());
                        for (Entry e : pkt.entries()) {
                            buf.writeUtf(e.name());
                            buf.writeVarLong(e.value());
                        }
                    },
                    buf -> {
                        int scope = buf.readVarInt();
                        long contextCluster = buf.readVarLong();
                        int n = buf.readVarInt();
                        List<Entry> list = new ArrayList<>(n);
                        for (int i = 0; i < n; i++) {
                            list.add(new Entry(buf.readUtf(), buf.readVarLong()));
                        }
                        return new PingListPacket(scope, contextCluster, list);
                    });

    @Override
    public Type<PingListPacket> type() {
        return TYPE;
    }
}
