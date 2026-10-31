package cn.alini.trueuuid.mixin.diag;

import cn.alini.trueuuid.Trueuuid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Field;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Diagnostic only (optional target): reports what AutoModpack 5.x's server-side login addon still
 * considers pending every time it refuses to release the vanilla login tick.
 */
@Pseudo
@Mixin(targets = "pl.skidam.automodpack.networking.server.ServerLoginNetworkAddon", remap = false)
public abstract class AutoModpackAddonDiagMixin {
    private static Field trueuuid$channels;
    private static Field trueuuid$synchronizers;
    private static Field trueuuid$processing;
    private static long trueuuid$lastLog;

    @Inject(method = "queryTick", at = @At("RETURN"), remap = false, require = 0)
    private void trueuuid$diagQueryTick(CallbackInfoReturnable<Boolean> cir) {
        boolean released = Boolean.TRUE.equals(cir.getReturnValue());
        long now = System.currentTimeMillis();
        if (released) {
            if (now - trueuuid$lastLog < 1000) return;
            trueuuid$lastLog = now;
            Trueuuid.LOGGER.info("[diag] amp addon queryTick=true (login released)");
            return;
        }
        if (now - trueuuid$lastLog < 1000) return;
        trueuuid$lastLog = now;
        try {
            if (trueuuid$channels == null) {
                trueuuid$channels = field("channels");
                trueuuid$synchronizers = field("synchronizers");
                trueuuid$processing = field("processingCount");
            }
            Map<?, ?> channels = (Map<?, ?>) trueuuid$channels.get(this);
            Collection<?> synchronizers = (Collection<?>) trueuuid$synchronizers.get(this);
            AtomicInteger processing = (AtomicInteger) trueuuid$processing.get(this);
            Trueuuid.LOGGER.info("[diag] amp addon holds login: channels={} keys={} synchronizers={} processing={}",
                    channels.size(), channels.keySet(), synchronizers.size(), processing.get());
        } catch (Throwable failure) {
            Trueuuid.LOGGER.warn("[diag] amp addon diag failed", failure);
        }
    }

    private static Field field(String name) throws NoSuchFieldException {
        Field field = Class.forName("pl.skidam.automodpack.networking.server.ServerLoginNetworkAddon")
                .getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
