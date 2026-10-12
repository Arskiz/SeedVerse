package arsqz.mod.net;

import arsqz.mod.TwoSixTwo_Arskiz;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Client -> Server: "take me to this planet."
 *
 * <p>Carries the planet's deterministic world seed (which the server turns into
 * a dynamic dimension), its display name, and the resolved dimension key string.
 */
public record TravelToPlanetPacket(long planetWorldSeed, String planetName, String dimensionKey)
        implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<TravelToPlanetPacket> TYPE =
            new CustomPacketPayload.Type<>(TwoSixTwo_Arskiz.id("travel_to_planet"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TravelToPlanetPacket> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_LONG, TravelToPlanetPacket::planetWorldSeed,
                    ByteBufCodecs.STRING_UTF8, TravelToPlanetPacket::planetName,
                    ByteBufCodecs.STRING_UTF8, TravelToPlanetPacket::dimensionKey,
                    TravelToPlanetPacket::new);

    @Override
    public Type<TravelToPlanetPacket> type() {
        return TYPE;
    }
}
