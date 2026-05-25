package io.github.m3t4f1v3.util;

import io.github.m3t4f1v3.Snowstorm;
import io.github.m3t4f1v3.network.SnowstormNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;

import org.joml.Vector3dc;
import org.valkyrienskies.core.api.bodies.properties.BodyInertia;
import org.valkyrienskies.core.api.events.CollisionEvent;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;
import org.valkyrienskies.core.api.ships.LoadedServerShip;
import org.valkyrienskies.core.api.util.AerodynamicUtils;
import org.valkyrienskies.core.api.world.PhysLevel;
import org.valkyrienskies.core.api.world.ServerShipWorld;
import org.valkyrienskies.core.internal.world.VsiServerShipWorld;
import org.valkyrienskies.mod.api.ValkyrienSkies;
import org.valkyrienskies.mod.common.VSGameUtilsKt;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4dc;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Drains queued ValkyrienSkies CollisionEvent instances and forwards contact
 * points to the ice-cracking logic on the server thread.
 */
@Mod.EventBusSubscriber(modid = Snowstorm.MODID)
public final class VSCollisionEventProcessor {
    private static final double BLOCK_FACE_AREA_M2 = 1.0;
    private static final double MIN_CONTACT_PATCH_AREA_M2 = 0.25;
    private static final double ICE_FAILURE_STRESS_PA = 750_000.0;
    private static final double PENETRATION_FAILURE_DEPTH_M = 0.25;
    private static final double ICE_CONTACT_TOLERANCE = 0.35;
    private static final double ICE_MAX_PENETRATION = 0.5;
    private static final double MIN_STRESS_TO_CRACK = 0.5;
    private static final int MAX_EVENTS_PER_DRAIN = 64;
    private static final ConcurrentLinkedQueue<CollisionEvent> PENDING_EVENTS = new ConcurrentLinkedQueue<>();
    private static long lastDebugTick = Long.MIN_VALUE;

    public static void submitCollision(CollisionEvent ev) {
        PENDING_EVENTS.add(ev);
    }

    public static void ensureCollisionWorker() {
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent evt) {
        if (evt.phase != TickEvent.Phase.END) return;

        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        VSCollisionEvents.register();
        flushPendingEvents(server, LogUtils.getLogger());
    }

    private static void flushPendingEvents(MinecraftServer server, Logger LOGGER) {
        List<CollisionEvent> drained = new ArrayList<>();
        CollisionEvent polled;
        int processed = 0;
        while (processed < MAX_EVENTS_PER_DRAIN && (polled = PENDING_EVENTS.poll()) != null) {
            drained.add(polled);
            processed++;
        }

        if (drained.isEmpty()) return;

        if (isDebugEnabled()) {
            LOGGER.debug("VS ice: drainedEvents={} backlog={}", drained.size(), PENDING_EVENTS.size());
        }

        VsiServerShipWorld maybeShipWorld = VSGameUtilsKt.getShipObjectWorld(server);
        if (maybeShipWorld == null) return;

        for (CollisionEvent ev : drained) {
            processCollisionEvent(server, LOGGER, maybeShipWorld, ev);
        }
    }

    private static void processCollisionEvent(MinecraftServer server, Logger LOGGER, VsiServerShipWorld maybeShipWorld, CollisionEvent ev) {
        String dimensionId = ev.getDimensionId();
        ServerLevel level = resolveEventLevel(server, dimensionId);
        if (level == null) return;

        long shipIdA = ev.getShipIdA();
        long shipIdB = ev.getShipIdB();
        CollisionBodyInfo bodyInfo = resolveCollisionBodyInfo(maybeShipWorld, shipIdA, shipIdB, dimensionId);
        LoadedServerShip ship = bodyInfo.ship();
        if (ship == null) return;

        double shipMassToUse = bodyInfo.shipMass();
        if (!(shipMassToUse > 0.0)) return;

        List<BlockPos> fractureSeeds = new ArrayList<>();
        List<Vector3d> fractureWorldPositions = new ArrayList<>();
        double minX = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;
        double sumNormalSpeedSq = 0.0;
        double maxPenetration = 0.0;

        var contactPoints = ev.getContactPoints();
        if (contactPoints == null || contactPoints.isEmpty()) return;

        for (var cp : contactPoints) {
            var posVec = cp.getPosition();
            var normalVec = cp.getNormal();
            var velocityVec = cp.getVelocity();

            Vector3d worldPosition = new Vector3d(posVec);
            Vector3d worldNormal = new Vector3d(normalVec);

            BlockPos contactPos = resolveContactBlock(
                    level,
                    new BlockPos(
                            (int) Math.floor(worldPosition.x()),
                            (int) Math.floor(worldPosition.y()),
                            (int) Math.floor(worldPosition.z())
                    ),
                    worldNormal.x(),
                    worldNormal.y(),
                    worldNormal.z()
            );
            if (contactPos == null) continue;

            BlockState contactState = level.getBlockState(contactPos);
            if (!contactState.is(Blocks.ICE) && !contactState.is(Blocks.FROSTED_ICE)) continue;

            minX = Math.min(minX, worldPosition.x());
            maxX = Math.max(maxX, worldPosition.x());
            minZ = Math.min(minZ, worldPosition.z());
            maxZ = Math.max(maxZ, worldPosition.z());
            sumNormalSpeedSq += normalSpeedSquared(velocityVec, worldNormal);
            maxPenetration = Math.max(maxPenetration, Math.min(ICE_MAX_PENETRATION, Math.max(0.0, -cp.getSeparation())));
            fractureSeeds.add(contactPos.immutable());
            fractureWorldPositions.add(new Vector3d(worldPosition));
        }

        if (fractureSeeds.isEmpty()) return;

        float[] eventColor = randomContactColor();
        if (isDebugEnabled()) {
            for (Vector3d fractureWorldPosition : fractureWorldPositions) {
                SnowstormNetwork.sendContactQuad(
                        level,
                        fractureWorldPosition.x(),
                        fractureWorldPosition.y(),
                        fractureWorldPosition.z(),
                        eventColor[0],
                        eventColor[1],
                        eventColor[2],
                        0.95f,
                        0.45f + ThreadLocalRandom.current().nextFloat() * 0.25f,
                        12 + ThreadLocalRandom.current().nextInt(6)
                );
            }
        }

        double footprintWidth = Math.max(1.0, maxX - minX + 1.0);
        double footprintDepth = Math.max(1.0, maxZ - minZ + 1.0);
        double footprintArea = Math.max(MIN_CONTACT_PATCH_AREA_M2, footprintWidth * footprintDepth * BLOCK_FACE_AREA_M2);
        double averageNormalSpeed = Math.sqrt(sumNormalSpeedSq / Math.max(1, fractureSeeds.size()));
        double gravity = AerodynamicUtils.GRAVITATIONAL_ACCELERATION;
        double staticPressure = (shipMassToUse * gravity) / footprintArea;
        double stoppingDistance = Math.max(PENETRATION_FAILURE_DEPTH_M, ICE_CONTACT_TOLERANCE + maxPenetration);
        double impactPressure = (0.5 * shipMassToUse * averageNormalSpeed * averageNormalSpeed) / (footprintArea * stoppingDistance);
        double stress = (staticPressure + impactPressure) / ICE_FAILURE_STRESS_PA + Math.min(4.0, maxPenetration / PENETRATION_FAILURE_DEPTH_M);

        if (stress < MIN_STRESS_TO_CRACK) return;

        double baseRadius = contactRadiusForFootprint(footprintArea);
        double stressScale = 1.0 + Math.min(2.0, stress);
        double fractureRadius = Math.max(1.0, Math.min(6.0, baseRadius * stressScale));

        double weightSum = fractureSeeds.size();

        if (isDebugEnabled()) {
            LOGGER.debug("VS ice: singleEvent dim={} shipA={} shipB={} contacts={} footprintArea={} fractureRadius={} mass={} staticPa={} impactPa={} penetration={} stressRatio={}",
                    dimensionId, shipIdA, shipIdB, fractureSeeds.size(), footprintArea, fractureRadius, shipMassToUse, staticPressure, impactPressure, maxPenetration, stress);
        }

        for (int i = 0; i < fractureSeeds.size(); i++) {
            double normalized = 1.0 / weightSum;
            double perSeedStress = stress * normalized * fractureSeeds.size();
            if (perSeedStress < MIN_STRESS_TO_CRACK) continue;

            double perSeedRadius = fractureRadius * (0.5 + normalized);
            if (isDebugEnabled()) {
                LOGGER.debug("VS ice: applying seedIdx={} seedPos={} perSeedStress={} perSeedRadius={} worldPos={}", i, fractureSeeds.get(i), perSeedStress, perSeedRadius, fractureWorldPositions.get(i));
            }
            IceWalkingHandler.applyIceCrackingForBlock(level, fractureSeeds.get(i), perSeedStress);
        }
    }

    private static ServerLevel resolveEventLevel(MinecraftServer server, String dimensionId) {
        if (dimensionId == null || dimensionId.isEmpty()) return null;
        String normalizedDimensionId = normalizeDimensionId(dimensionId);
        for (var key : server.levelKeys()) {
            ServerLevel level = server.getLevel(key);
            if (level != null && key.location().toString().equals(normalizedDimensionId)) {
                return level;
            }
        }
        return null;
    }

    private static String normalizeDimensionId(String dimensionId) {
        String marker = ":dimension:";
        int markerIndex = dimensionId.indexOf(marker);
        if (markerIndex >= 0) {
            return dimensionId.substring(markerIndex + marker.length());
        }
        return dimensionId;
    }

    private static String buildCollisionKey(String dimensionId, long shipIdA, long shipIdB) {
        long min = Math.min(shipIdA, shipIdB);
        long max = Math.max(shipIdA, shipIdB);
        return normalizeDimensionId(dimensionId) + ":" + min + ":" + max;
    }

    private static BlockPos resolveContactBlock(ServerLevel level, BlockPos pos, double normalX, double normalY, double normalZ) {
        // Prefer the exact block, then search a symmetric neighborhood around the
        // contact position to find the nearest support block. This avoids biasing
        // the lookup along the contact normal (which produced consistent +1/+1
        // offsets). We check y offset 0 first, then below (-1).
        for (int dy = 0; dy >= -1; dy--) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    BlockPos candidate = pos.offset(dx, dy, dz);
                    if (!level.isLoaded(candidate)) continue;
                    if (isSupportBlock(level, candidate)) return candidate;
                }
            }
        }

        return null;
    }

    private static int oppositeNormalStep(double component) {
        if (component > 0.25) return -1;
        if (component < -0.25) return 1;
        return 0;
    }

    private static double contactRadiusForFootprint(double footprintArea) {
        return Math.max(1.0, Math.min(4.0, Math.sqrt(footprintArea / Math.PI)));
    }

    private static double normalSpeedSquared(org.joml.Vector3dc velocityVec, Vector3d worldNormal) {
        double normalSpeed = Math.abs(
                velocityVec.x() * worldNormal.x() +
                        velocityVec.y() * worldNormal.y() +
                        velocityVec.z() * worldNormal.z()
        );
        return normalSpeed * normalSpeed;
    }

    private static boolean isSupportBlock(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return !state.isAir() && !state.getCollisionShape(level, pos).isEmpty();
    }

    private static double penetrationStress(double maxPenetration) {
        if (maxPenetration <= 0.0) return 0.0;
        return Math.min(4.0, maxPenetration / PENETRATION_FAILURE_DEPTH_M);
    }

    private static double resolveShipMass(LoadedServerShip ship) {
        BodyInertia inertia = ship.getInertiaData();
        return inertia.getMass();
    }

    private static CollisionBodyInfo resolveCollisionBodyInfo(VsiServerShipWorld shipWorld, long shipIdA, long shipIdB, String dimensionId) {
        long groundId = shipWorld.getDimensionToGroundBodyIdImmutable().get(dimensionId);

        boolean aIsWorldGround = shipIdA == groundId;
        boolean bIsWorldGround = shipIdB == groundId;
        if (aIsWorldGround && bIsWorldGround) {
            throw new RuntimeException("what the helly why did ground collide with ground");
        }
        LoadedServerShip body = aIsWorldGround ? getLoadedShip(shipWorld, shipIdB) : getLoadedShip(shipWorld, shipIdA);
        if (body == null) {
            body = aIsWorldGround ? getLoadedShip(shipWorld, shipIdA) : getLoadedShip(shipWorld, shipIdB);
        }
        if (body == null) {
            return new CollisionBodyInfo(aIsWorldGround, Double.NaN, null);
        }
        double shipMass = resolveShipMass(body);

        return new CollisionBodyInfo(aIsWorldGround, shipMass, body);
    }

    private static LoadedServerShip getLoadedShip(ServerShipWorld shipWorld, long shipId) {
        if (shipWorld == null) return null;
        return shipWorld.getLoadedShips().getById(shipId);
    }

    private static boolean isDebugEnabled() {
        return IceWalkingHandler.DEBUG_MODE;
    }

    private static float[] randomContactColor() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        return new float[] {
                0.25f + random.nextFloat() * 0.75f,
                0.25f + random.nextFloat() * 0.75f,
                0.25f + random.nextFloat() * 0.75f
        };
    }

    private record CollisionBodyInfo(boolean isShipAGround, double shipMass, LoadedServerShip ship) {
    }
}
