package arsqz.mod.net;

import arsqz.mod.TwoSixTwo_Arskiz;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Client -> Server: "broadcast a space beacon to this planet."
 *
 * <p>The server announces it in chat with the planet name made clickable, so any
 * other player can jump straight to it on their own galaxy map.
 */
public record ShareBeaconPacket(long planetWorldSeed, String planetName) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<ShareBeaconPacket> TYPE =
            new CustomPacketPayload.Type<>(TwoSixTwo_Arskiz.id("share_beacon"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ShareBeaconPacket> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_LONG, ShareBeaconPacket::planetWorldSeed,
                    ByteBufCodecs.STRING_UTF8, ShareBeaconPacket::planetName,
                    ShareBeaconPacket::new);

    @Override
    public Type<ShareBeaconPacket> type() {
        return TYPE;
    }
}
