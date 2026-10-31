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
 * Diagnostic only (optional target): reports AutoModpack 5.x's server-side login addon state.
 * Logs on change so a whole join produces a handful of lines instead of one per tick.
 */
@Pseudo
@Mixin(targets = "pl.skidam.automodpack.networking.server.ServerLoginNetworkAddon", remap = false)
public abstract class AutoModpackAddonDiagMixin {
    private static Field trueuuid$channels;
    private static Field trueuuid$synchronizers;
    private static Field trueuuid$processing;
    private static String trueuuid$lastSignature;

    @Inject(method = "queryTick", at = @At("RETURN"), remap = false, require = 0)
    private void trueuuid$diagQueryTick(CallbackInfoReturnable<Boolean> cir) {
        boolean released = Boolean.TRUE.equals(cir.getReturnValue());
        try {
            if (trueuuid$channels == null) {
                trueuuid$channels = field("channels");
                trueuuid$synchronizers = field("synchronizers");
                trueuuid$processing = field("processingCount");
            }
            Map<?, ?> channels = (Map<?, ?>) trueuuid$channels.get(this);
            Collection<?> synchronizers = (Collection<?>) trueuuid$synchronizers.get(this);
            AtomicInteger processing = (AtomicInteger) trueuuid$processing.get(this);
            String signature = released + "|" + channels.size() + "|" + channels.keySet() + "|"
                    + synchronizers.size() + "|" + processing.get();
            if (signature.equals(trueuuid$lastSignature)) return;
            trueuuid$lastSignature = signature;
            Trueuuid.LOGGER.info("[diag] amp addon queryTick={} channels={} keys={} synchronizers={} processing={}",
                    released, channels.size(), channels.keySet(), synchronizers.size(), processing.get());
        } catch (Throwable failure) {
            Trueuuid.LOGGER.warn("[diag] amp addon diag failed", failure);
        }
    }

    private static Field field(String name) throws Exception {
        Field field = Class.forName("pl.skidam.automodpack.networking.server.ServerLoginNetworkAddon")
                .getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
