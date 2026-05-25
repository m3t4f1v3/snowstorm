package io.github.m3t4f1v3.util;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;
import org.valkyrienskies.core.api.events.CollisionEvent;
import org.valkyrienskies.mod.common.ValkyrienSkiesMod;

/**
 * Minimal registrar that subscribes to ValkyrienSkies collision events and
 * enqueues them for processing on the Forge server thread.
 */
public final class VSCollisionEvents {

    private VSCollisionEvents() {
    }

    private static volatile boolean registered = false;
    private static volatile boolean apiUnavailableLogged = false;

    public static void register() {
        if (registered) return;
        final Logger LOGGER = LogUtils.getLogger();
        var api = ValkyrienSkiesMod.getApi();
        if (api == null) {
            if (!apiUnavailableLogged) {
                LOGGER.info("VSCollisionEvents: ValkyrienSkies API not available yet; will retry on server ticks");
                apiUnavailableLogged = true;
            }
            return;
        }

        boolean debug = isDebugEnabled();
        api.getCollisionPersistEvent().on(ev -> {
            if (debug) {
                LOGGER.info("VS enqueue persist: shipA={} shipB={}", ev.getShipIdA(), ev.getShipIdB());
            }
            VSCollisionEventProcessor.submitCollision(ev);
        });
        registered = true;
        VSCollisionEventProcessor.ensureCollisionWorker();
        LOGGER.info("VSCollisionEvents: registered collision listeners");
    }

    public static boolean isRegistered() {
        return registered;
    }

    private static boolean isDebugEnabled() {
        return IceWalkingHandler.DEBUG_MODE;
    }
}
