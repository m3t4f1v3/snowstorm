package io.github.m3t4f1v3.network;

import io.github.m3t4f1v3.Snowstorm;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.concurrent.atomic.AtomicBoolean;

public final class SnowstormNetwork {
    private static final String PROTOCOL_VERSION = "1";
    private static final ResourceLocation CHANNEL_NAME = new ResourceLocation(Snowstorm.MODID, "main");
    private static final SimpleChannel CHANNEL = NetworkRegistry.ChannelBuilder
            .named(CHANNEL_NAME)
            .networkProtocolVersion(() -> PROTOCOL_VERSION)
            .clientAcceptedVersions(PROTOCOL_VERSION::equals)
            .serverAcceptedVersions(PROTOCOL_VERSION::equals)
            .simpleChannel();
    private static final AtomicBoolean REGISTERED = new AtomicBoolean(false);

    private SnowstormNetwork() {
    }

    public static void register() {
        if (!REGISTERED.compareAndSet(false, true)) return;
        int id = 0;
        CHANNEL.messageBuilder(ContactQuadPacket.class, id++)
                .encoder(ContactQuadPacket::encode)
                .decoder(ContactQuadPacket::decode)
                .consumerMainThread(ContactQuadPacket::handle)
                .add();
    }

    public static void sendContactQuad(ServerLevel level, double x, double y, double z, float red, float green, float blue, float alpha, float size, int lifetime) {
        CHANNEL.send(PacketDistributor.ALL.noArg(), new ContactQuadPacket(x, y, z, red, green, blue, alpha, size, lifetime));
    }

    public static SimpleChannel channel() {
        return CHANNEL;
    }
}