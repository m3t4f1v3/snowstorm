package io.github.m3t4f1v3.util;

import io.github.m3t4f1v3.Snowstorm;
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
    private static long lastDebugTick = Long.MIN_VALUE;

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent evt) {
        if (evt.phase != TickEvent.Phase.END) return;

        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        VSCollisionEvents.register();
        final Logger LOGGER = LogUtils.getLogger();

        VsiServerShipWorld maybeShipWorld = VSGameUtilsKt.getShipObjectWorld(server);

        // Drain once per tick
        CollisionEvent ev;
        while ((ev = VSCollisionEvents.QUEUE.poll()) != null) {
            long shipIdA = ev.getShipIdA();
            long shipIdB = ev.getShipIdB();

            CollisionBodyInfo bodyInfo = resolveCollisionBodyInfo(maybeShipWorld, shipIdA, shipIdB, ev.getDimensionId());

            ServerLevel level = resolveEventLevel(server, ev.getDimensionId());
            if (level == null) {
                if (isDebugEnabled()) {
                    LOGGER.info("VS ice: dropped event because dimension '{}' did not match any server level", ev.getDimensionId());
                }
                continue;
            }

            LoadedServerShip ship = bodyInfo.ship();
            if (ship == null) {
                if (isDebugEnabled()) {
                    LOGGER.info("VS ice: ignored collision dim={} shipA={} shipB={} because the ship could not be resolved from the collision ids",
                            ev.getDimensionId(), shipIdA, shipIdB);
                }
                continue;
            }

            double shipMassToUse = bodyInfo.shipMass();
            if (!(shipMassToUse > 0.0)) {
                if (isDebugEnabled()) {
                    LOGGER.info("VS ice: ignored collision dim={} shipA={} shipB={} because resolved ship mass was invalid",
                            ev.getDimensionId(), shipIdA, shipIdB);
                }
                continue;
            }

            var contactPoints = ev.getContactPoints();
            if (contactPoints.isEmpty()) {
                if (isDebugEnabled()) {
                    LOGGER.info("VS ice: ignored ground collision dim={} shipA={} shipB={} groundSide={} because it had no contacts",
                            ev.getDimensionId(), shipIdA, shipIdB, bodyInfo.isShipAGround ? "A" : "B");
                }
                continue;
            }

            Matrix4dc shipToWorld = ship.getShipToWorld();

            java.util.List<BlockPos> fractureSeeds = new java.util.ArrayList<>();
            java.util.List<Vector3d> fractureWorldPositions = new java.util.ArrayList<>();
            double minX = Double.POSITIVE_INFINITY;
            double maxX = Double.NEGATIVE_INFINITY;
            double minZ = Double.POSITIVE_INFINITY;
            double maxZ = Double.NEGATIVE_INFINITY;
            double sumNormalSpeedSq = 0.0;
            double maxPenetration = 0.0;
            int iceContactCount = 0;

            for (var cp : contactPoints) {
                var posVec = cp.getPosition();
                var normalVec = cp.getNormal();
                var velocityVec = cp.getVelocity();

                // CollisionEvent contact points are already provided in world coordinates
                Vector3d worldPosition = new Vector3d(posVec);
                Vector3d worldNormal = new Vector3d(normalVec);

                if (isDebugEnabled()) {
                    LOGGER.info(
                            "VS ice: contact dim={} shipA={} shipB={} rawPos={} worldPos={} rawNormal={} worldNormal={} separation={} velocity={}",
                            ev.getDimensionId(), shipIdA, shipIdB, posVec, worldPosition, normalVec, worldNormal,
                            cp.getSeparation(), velocityVec
                    );
                }

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
                if (contactPos == null) {
                    if (isDebugEnabled()) {
                        LOGGER.info("VS ice: dropped contact dim={} shipA={} shipB={} because no support block matched rawBlockPos={} worldPos={} worldNormal={}",
                                ev.getDimensionId(), shipIdA, shipIdB,
                                new BlockPos((int) Math.floor(worldPosition.x()), (int) Math.floor(worldPosition.y()), (int) Math.floor(worldPosition.z())),
                                worldPosition, worldNormal);
                    }
                    continue;
                }

                BlockState contactState = level.getBlockState(contactPos);
                if (!contactState.is(Blocks.ICE) && !contactState.is(Blocks.FROSTED_ICE)) {
                    if (isDebugEnabled()) {
                        LOGGER.info("VS ice: dropped contact dim={} shipA={} shipB={} contactPos={} state={} worldPos={} worldNormal={}",
                                ev.getDimensionId(), shipIdA, shipIdB, contactPos, contactState, worldPosition, worldNormal);
                    }
                    continue;
                }

                minX = Math.min(minX, worldPosition.x());
                maxX = Math.max(maxX, worldPosition.x());
                minZ = Math.min(minZ, worldPosition.z());
                maxZ = Math.max(maxZ, worldPosition.z());
                sumNormalSpeedSq += normalSpeedSquared(velocityVec, worldNormal);
                maxPenetration = Math.max(maxPenetration, Math.min(ICE_MAX_PENETRATION, Math.max(0.0, -cp.getSeparation())));
                fractureSeeds.add(contactPos.immutable());
                fractureWorldPositions.add(new Vector3d(worldPosition));
                iceContactCount++;
            }

            if (iceContactCount == 0) {
                if (isDebugEnabled()) {
                    LOGGER.info("VS ice: no ice contacts survived filtering dim={} shipA={} shipB={} contacts={} shipMass={}",
                            ev.getDimensionId(), shipIdA, shipIdB, contactPoints.size(), shipMassToUse);
                }
                continue;
            }

            double footprintWidth = Math.max(1.0, maxX - minX + 1.0);
            double footprintDepth = Math.max(1.0, maxZ - minZ + 1.0);
            double footprintArea = Math.max(MIN_CONTACT_PATCH_AREA_M2, footprintWidth * footprintDepth * BLOCK_FACE_AREA_M2);
            double averageNormalSpeed = Math.sqrt(sumNormalSpeedSq / iceContactCount);
            //todo: make this level specific
            double gravity = AerodynamicUtils.GRAVITATIONAL_ACCELERATION;
            double staticPressure = (shipMassToUse * gravity) / footprintArea;
            double stoppingDistance = Math.max(PENETRATION_FAILURE_DEPTH_M, ICE_CONTACT_TOLERANCE + maxPenetration);
            double impactPressure = (0.5 * shipMassToUse * averageNormalSpeed * averageNormalSpeed) / (footprintArea * stoppingDistance);
            double stress = (staticPressure + impactPressure) / ICE_FAILURE_STRESS_PA + Math.min(4.0, maxPenetration / PENETRATION_FAILURE_DEPTH_M);

            if (stress < MIN_STRESS_TO_CRACK) {
                if (isDebugEnabled()) {
                    LOGGER.info("VS ice: stress below threshold dim={} shipA={} shipB={} stressRatio={} footprintArea={} penetration={} averageNormalSpeed={}",
                            ev.getDimensionId(), shipIdA, shipIdB, stress, footprintArea, maxPenetration, averageNormalSpeed);
                }
                continue;
            }

            // Base radius from contact footprint, scaled by stress so higher-pressure
            // collisions produce larger fracture zones. Clamp to reasonable bounds.
            double baseRadius = contactRadiusForFootprint(footprintArea);
            double stressScale = 1.0 + Math.min(2.0, stress); // up to 3x size
            double fractureRadius = Math.max(1.0, Math.min(6.0, baseRadius * stressScale));

            Vector3dc shipCoMShip = ship.getInertiaData().getCenterOfMass();

            Vector3d shipCoMWorld = ship.getShipToWorld().transformPosition(new Vector3d(shipCoMShip));

            double footprintRadius = Math.sqrt(footprintArea / Math.PI);

            double sigma = Math.max(1.5, footprintRadius * 0.6);

            // Compute per-seed stress weights biased by proximity to ship center-of-mass.
            double[] weights = new double[fractureSeeds.size()];
            double weightSum = 0.0;
            for (int i = 0; i < fractureSeeds.size(); i++) {
                Vector3d p = fractureWorldPositions.get(i);
                double dx = p.x() - shipCoMWorld.x();
                double dz = p.z() - shipCoMWorld.z();
                double distSqr = 
                    dx * dx + 
                    dz * dz;
                
                double w = 1.0 / (distSqr + 0.01); // avoid divide-by-zero and overly large weights for very close seeds
                weights[i] = w;
                weightSum += w;
            }

            if (isDebugEnabled()) {
                LOGGER.info("VS ice: dim={} shipA={} shipB={} groundSide={} crackPos={} footprintArea={} fractureRadius={} mass={} staticPa={} impactPa={} penetration={} stressRatio={}",
                        ev.getDimensionId(), shipIdA, shipIdB, bodyInfo.isShipAGround ? "A" : "B",
                        fractureSeeds.get(0), footprintArea, fractureRadius, shipMassToUse,
                        staticPressure, impactPressure, maxPenetration, stress);
            }

            if (isDebugEnabled()) {
                LOGGER.info("VS ice: shipCoMWorld={} seeds={} footprintArea={} stress={}", shipCoMWorld, fractureSeeds.size(), footprintArea, stress);
                for (int i = 0; i < fractureSeeds.size(); i++) {
                    Vector3d p = fractureWorldPositions.get(i);
                    double dx = p.x() - (shipCoMWorld == null ? 0.0 : shipCoMWorld.x());
                    double dz = p.z() - (shipCoMWorld == null ? 0.0 : shipCoMWorld.z());
                    double distSq = dx * dx + dz * dz;
                    double normalized = (weightSum > 0.0) ? (weights[i] / weightSum) : 0.0;
                    LOGGER.info("VS ice: seedIdx={} seedPos={} worldPos={} distSq={} weight={} normalized={}", i, fractureSeeds.get(i), p, distSq, weights[i], normalized);
                }
            }

            int seedCount = fractureSeeds.size();
            for (int i = 0; i < seedCount; i++) {
                double w = weights[i];
                double normalized = (weightSum > 0.0) ? (w / weightSum) : (1.0 / seedCount);
                // Scale per-seed stress so the average across seeds equals the computed
                // `stress` but seeds nearer the CoM get proportionally more.
                double perSeedStress = stress * normalized * seedCount;
                if (perSeedStress < MIN_STRESS_TO_CRACK) {
                    if (isDebugEnabled()) {
                        LOGGER.info("VS ice: skipping seed {} perSeedStress={} (below min)", fractureSeeds.get(i), perSeedStress);
                    }
                    continue;
                }
                if (isDebugEnabled()) {
                    LOGGER.info("VS ice: applying seedIdx={} seedPos={} perSeedStress={} perSeedRadius={}", i, fractureSeeds.get(i), perSeedStress, (fractureRadius * (0.5 + (weights[i] / weightSum))));
                }
                double perSeedRadius = fractureRadius * (0.5 + normalized);
                BlockPos fractureSeed = fractureSeeds.get(i);
                IceWalkingHandler.applyIceCrackingFromContactPoint(
                        level,
                        new Vec3(fractureSeed.getX(), fractureSeed.getY(), fractureSeed.getZ()),
                        perSeedRadius,
                        perSeedStress
                );
            }
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

    private record CollisionBodyInfo(boolean isShipAGround, double shipMass, LoadedServerShip ship) {
    }
}
