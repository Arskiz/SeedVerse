package arsqz.mod.net;

import arsqz.mod.TwoSixTwo_Arskiz;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client -> Server: "send me the current list of online players and where they are." */
public record RequestPlayersPacket() implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<RequestPlayersPacket> TYPE =
            new CustomPacketPayload.Type<>(TwoSixTwo_Arskiz.id("request_players"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RequestPlayersPacket> CODEC =
            StreamCodec.unit(new RequestPlayersPacket());

    @Override
    public Type<RequestPlayersPacket> type() {
        return TYPE;
    }
}
