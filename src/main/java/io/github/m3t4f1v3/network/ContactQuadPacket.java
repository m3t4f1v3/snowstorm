package io.github.m3t4f1v3.network;

import io.github.m3t4f1v3.client.ContactQuadRenderer;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public final class ContactQuadPacket {
    private final double x;
    private final double y;
    private final double z;
    private final float red;
    private final float green;
    private final float blue;
    private final float alpha;
    private final float size;
    private final int lifetime;

    public ContactQuadPacket(double x, double y, double z, float red, float green, float blue, float alpha, float size, int lifetime) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.red = red;
        this.green = green;
        this.blue = blue;
        this.alpha = alpha;
        this.size = size;
        this.lifetime = lifetime;
    }

    public static void encode(ContactQuadPacket packet, FriendlyByteBuf buffer) {
        buffer.writeDouble(packet.x);
        buffer.writeDouble(packet.y);
        buffer.writeDouble(packet.z);
        buffer.writeFloat(packet.red);
        buffer.writeFloat(packet.green);
        buffer.writeFloat(packet.blue);
        buffer.writeFloat(packet.alpha);
        buffer.writeFloat(packet.size);
        buffer.writeVarInt(packet.lifetime);
    }

    public static ContactQuadPacket decode(FriendlyByteBuf buffer) {
        return new ContactQuadPacket(
                buffer.readDouble(),
                buffer.readDouble(),
                buffer.readDouble(),
                buffer.readFloat(),
                buffer.readFloat(),
                buffer.readFloat(),
                buffer.readFloat(),
                buffer.readFloat(),
                buffer.readVarInt()
        );
    }

    public static void handle(ContactQuadPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> ContactQuadRenderer.add(packet.x, packet.y, packet.z, packet.red, packet.green, packet.blue, packet.alpha, packet.size, packet.lifetime));
        context.setPacketHandled(true);
    }
}