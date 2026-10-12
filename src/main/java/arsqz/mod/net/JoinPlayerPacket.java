package arsqz.mod.net;

import arsqz.mod.TwoSixTwo_Arskiz;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client -> Server: "teleport me to this player's current location." */
public record JoinPlayerPacket(String targetUuid) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<JoinPlayerPacket> TYPE =
            new CustomPacketPayload.Type<>(TwoSixTwo_Arskiz.id("join_player"));

    public static final StreamCodec<RegistryFriendlyByteBuf, JoinPlayerPacket> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8, JoinPlayerPacket::targetUuid,
                    JoinPlayerPacket::new);

    @Override
    public Type<JoinPlayerPacket> type() {
        return TYPE;
    }
}
