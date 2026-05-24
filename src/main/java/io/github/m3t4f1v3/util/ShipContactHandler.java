package io.github.m3t4f1v3.util;

import io.github.m3t4f1v3.Snowstorm;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;

import org.valkyrienskies.physics_api.simevents.ContactEvent;
import org.valkyrienskies.physics_api.simevents.CollisionEventInfo;
import org.valkyrienskies.mod.common.IShipObjectWorldServerProvider;
import org.valkyrienskies.mod.common.VSGameUtilsKt;
import org.valkyrienskies.core.internal.world.VsiServerShipWorld;
import org.valkyrienskies.core.internal.ships.VsiQueryableShipData;
import org.valkyrienskies.core.impl.api.LoadedServerShipInternal;
import org.valkyrienskies.core.impl.game.ships.ShipData;
import org.valkyrienskies.core.impl.game.ships.ShipInertiaDataImpl;
import net.minecraft.resources.ResourceKey;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Listens for ValkyrienSkies contact events and applies ice cracking to contacted blocks.
 *
 * Note: This implementation distributes an estimated ship-mass baseline across contacted
 * blocks proportionally to contact counts. It uses ShipWeightUtil.WEIGHT_THRESHOLD as a
 * conservative baseline when ship mass lookup is unavailable.
 */
public class ShipContactHandler {

    public static void onContactEvent(ContactEvent event) {
        List<CollisionEventInfo> contacts = event.getContacts();
        if (contacts == null || contacts.isEmpty()) return;

        Map<BlockPos, Integer> contactCounts = new HashMap<>();
        for (CollisionEventInfo info : contacts) {
            org.joml.Vector3dc point = info.getPoint();
            if (point == null) {
                continue;
            }

            int x = (int) Math.floor(point.x());
            int y = (int) Math.floor(point.y());
            int z = (int) Math.floor(point.z());
            BlockPos pos = new BlockPos(x, y, z);
            contactCounts.put(pos, contactCounts.getOrDefault(pos, 0) + 1);
        }

        if (contactCounts.isEmpty()) return;

        int totalContacts = contactCounts.values().stream().mapToInt(Integer::intValue).sum();
        if (totalContacts <= 0) return;

        net.minecraft.server.MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;

        server.execute(() -> {
            for (Map.Entry<BlockPos, Integer> entry : contactCounts.entrySet()) {
                BlockPos pos = entry.getKey();
                int count = entry.getValue();

                for (ResourceKey<net.minecraft.world.level.Level> key : server.levelKeys()) {
                    ServerLevel level = server.getLevel(key);
                    if (level == null) {
                        continue;
                    }

                    if (!level.isLoaded(pos)) {
                        continue;
                    }

                    if (!level.getBlockState(pos).is(Blocks.ICE) && !level.getBlockState(pos).is(Blocks.FROSTED_ICE)) {
                        continue;
                    }

                    double shipMass = resolveShipMass(level, pos);
                    if (!(shipMass > 0.0)) {
                        continue;
                    }

                    double stress = shipMass * ((double) count / (double) totalContacts) / 1000.0;
                    IceWalkingHandler.applyIceCrackingForBlock(level, pos, stress);
                }
            }
        });
    }

    private static double resolveShipMass(ServerLevel level, BlockPos pos) {
        if (!(level instanceof IShipObjectWorldServerProvider provider)) {
            return Double.NaN;
        }

        VsiServerShipWorld shipWorld = provider.getShipObjectWorld();
        if (shipWorld == null) {
            return Double.NaN;
        }

        VsiQueryableShipData<?> loaded = shipWorld.getLoadedShips();
        if (loaded == null) {
            return Double.NaN;
        }

        int chunkX = pos.getX() >> 4;
        int chunkZ = pos.getZ() >> 4;
        Object ship = loaded.getByChunkPos(chunkX, chunkZ, VSGameUtilsKt.getDimensionId(level));
        if (!(ship instanceof LoadedServerShipInternal loadedInternal)) {
            return Double.NaN;
        }

        Object shipDataCommon = loadedInternal.asShipDataCommon();
        if (!(shipDataCommon instanceof ShipData sd)) {
            return Double.NaN;
        }

        ShipInertiaDataImpl inertia = sd.getInertiaData();
        if (inertia == null) {
            return Double.NaN;
        }

        return inertia.getMass();
    }
}
