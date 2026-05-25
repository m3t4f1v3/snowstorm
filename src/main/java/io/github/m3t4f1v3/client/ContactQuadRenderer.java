package io.github.m3t4f1v3.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Axis;
import com.mojang.blaze3d.vertex.PoseStack;
import io.github.m3t4f1v3.Snowstorm;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

@Mod.EventBusSubscriber(modid = Snowstorm.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ContactQuadRenderer {
    private static final List<ActiveQuad> ACTIVE_QUADS = new ArrayList<>();

    private ContactQuadRenderer() {
    }

    public static void add(double x, double y, double z, float red, float green, float blue, float alpha, float size, int lifetime) {
        synchronized (ACTIVE_QUADS) {
            ACTIVE_QUADS.add(new ActiveQuad(x, y, z, red, green, blue, alpha, size, 0, lifetime));
        }
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        synchronized (ACTIVE_QUADS) {
            Iterator<ActiveQuad> iterator = ACTIVE_QUADS.iterator();
            while (iterator.hasNext()) {
                ActiveQuad quad = iterator.next();
                quad.age++;
                if (quad.age >= quad.lifetime) {
                    iterator.remove();
                }
            }
        }
    }

    @SubscribeEvent
    public static void onRenderWorld(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;

        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null) return;

        List<ActiveQuad> quads;
        synchronized (ACTIVE_QUADS) {
            if (ACTIVE_QUADS.isEmpty()) return;
            quads = new ArrayList<>(ACTIVE_QUADS);
        }

        Camera camera = event.getCamera();
        Vec3 cameraPos = camera.getPosition();
        PoseStack poseStack = event.getPoseStack();

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        BufferBuilder buffer = Tesselator.getInstance().getBuilder();
        buffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

        for (ActiveQuad quad : quads) {
            float ageFactor = 1.0f - Math.min(1.0f, quad.age / (float) quad.lifetime);
            float alpha = quad.alpha * ageFactor;
            float halfSize = quad.size * 0.5f;

            poseStack.pushPose();
            poseStack.translate(quad.x - cameraPos.x, quad.y - cameraPos.y, quad.z - cameraPos.z);
            poseStack.mulPose(camera.rotation());

            var pose = poseStack.last().pose();
            buffer.vertex(pose, -halfSize, -halfSize, 0.0f).color(quad.red, quad.green, quad.blue, alpha).endVertex();
            buffer.vertex(pose, halfSize, -halfSize, 0.0f).color(quad.red, quad.green, quad.blue, alpha).endVertex();
            buffer.vertex(pose, halfSize, halfSize, 0.0f).color(quad.red, quad.green, quad.blue, alpha).endVertex();
            buffer.vertex(pose, -halfSize, halfSize, 0.0f).color(quad.red, quad.green, quad.blue, alpha).endVertex();
            poseStack.popPose();
        }

        BufferUploader.drawWithShader(buffer.end());
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }

    private static final class ActiveQuad {
        private final double x;
        private final double y;
        private final double z;
        private final float red;
        private final float green;
        private final float blue;
        private final float alpha;
        private final float size;
        private int age;
        private final int lifetime;

        private ActiveQuad(double x, double y, double z, float red, float green, float blue, float alpha, float size, int age, int lifetime) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.red = red;
            this.green = green;
            this.blue = blue;
            this.alpha = alpha;
            this.size = size;
            this.age = age;
            this.lifetime = lifetime;
        }
    }
}