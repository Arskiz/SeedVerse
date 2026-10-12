package arsqz.mod.net;

import arsqz.mod.TwoSixTwo_Arskiz;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Client -> Server: what the player is currently looking at on their map.
 *
 * <p>Sent event-driven (only when it changes, lightly debounced), not on a timer.
 * <ul>
 *   <li>{@link #LEVEL_NONE}: map closed / not viewing - clear any ping.</li>
 *   <li>{@link #LEVEL_UNIVERSE}: viewing the cluster-selection screen.</li>
 *   <li>{@link #LEVEL_CLUSTER}: inside {@code cluster}; {@code galaxyLocal} is the
 *       inspected galaxy, or {@code -1} when just idling in the cluster.</li>
 * </ul>
 */
public record PingFocusPacket(int level, long cluster, long galaxyLocal) implements CustomPacketPayload {

    public static final int LEVEL_NONE = 0;
    public static final int LEVEL_UNIVERSE = 1;
    public static final int LEVEL_CLUSTER = 2;

    public static final CustomPacketPayload.Type<PingFocusPacket> TYPE =
            new CustomPacketPayload.Type<>(TwoSixTwo_Arskiz.id("ping_focus"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PingFocusPacket> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, PingFocusPacket::level,
                    ByteBufCodecs.VAR_LONG, PingFocusPacket::cluster,
                    ByteBufCodecs.VAR_LONG, PingFocusPacket::galaxyLocal,
                    PingFocusPacket::new);

    @Override
    public Type<PingFocusPacket> type() {
        return TYPE;
    }
}
