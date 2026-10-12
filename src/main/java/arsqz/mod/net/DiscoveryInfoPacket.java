package arsqz.mod.net;

import arsqz.mod.TwoSixTwo_Arskiz;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server -> Client: discovery status for a planet. An empty {@code discoverer}
 * string means the planet is still uncharted.
 */
public record DiscoveryInfoPacket(long planetWorldSeed, String discoverer) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<DiscoveryInfoPacket> TYPE =
            new CustomPacketPayload.Type<>(TwoSixTwo_Arskiz.id("discovery_info"));

    public static final StreamCodec<RegistryFriendlyByteBuf, DiscoveryInfoPacket> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_LONG, DiscoveryInfoPacket::planetWorldSeed,
                    ByteBufCodecs.STRING_UTF8, DiscoveryInfoPacket::discoverer,
                    DiscoveryInfoPacket::new);

    @Override
    public Type<DiscoveryInfoPacket> type() {
        return TYPE;
    }
}
