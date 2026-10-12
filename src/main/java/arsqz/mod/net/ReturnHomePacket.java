package arsqz.mod.net;

import arsqz.mod.TwoSixTwo_Arskiz;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client -> Server: "send me back to my home (overworld) world." */
public record ReturnHomePacket() implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<ReturnHomePacket> TYPE =
            new CustomPacketPayload.Type<>(TwoSixTwo_Arskiz.id("return_home"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ReturnHomePacket> CODEC =
            StreamCodec.unit(new ReturnHomePacket());

    @Override
    public Type<ReturnHomePacket> type() {
        return TYPE;
    }
}
