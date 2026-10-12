package arsqz.mod.net;

import arsqz.mod.TwoSixTwo_Arskiz;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client -> Server: "who discovered this planet?" */
public record RequestDiscoveryPacket(long planetWorldSeed) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<RequestDiscoveryPacket> TYPE =
            new CustomPacketPayload.Type<>(TwoSixTwo_Arskiz.id("request_discovery"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RequestDiscoveryPacket> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_LONG, RequestDiscoveryPacket::planetWorldSeed,
                    RequestDiscoveryPacket::new);

    @Override
    public Type<RequestDiscoveryPacket> type() {
        return TYPE;
    }
}
