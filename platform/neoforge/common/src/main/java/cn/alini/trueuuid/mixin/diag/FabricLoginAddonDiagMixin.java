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

/**
 * Diagnostic only (optional target): Fabric API redirects the vanilla login finaliser call in
 * ServerLoginPacketListenerImpl#tick behind its own addon's queryTick(). Report whether that gate
 * opens, and what it still considers pending.
 */
@Pseudo
@Mixin(targets = "net.fabricmc.fabric.impl.networking.server.ServerLoginNetworkAddon", remap = false)
public abstract class FabricLoginAddonDiagMixin {
    private static Field trueuuid$channels;
    private static Field trueuuid$waits;
    private static String trueuuid$lastSignature;

    @Inject(method = "queryTick", at = @At("RETURN"), remap = false, require = 0)
    private void trueuuid$diagQueryTick(CallbackInfoReturnable<Boolean> cir) {
        boolean released = Boolean.TRUE.equals(cir.getReturnValue());
        try {
            if (trueuuid$channels == null) {
                trueuuid$channels = Class.forName("net.fabricmc.fabric.impl.networking.server.ServerLoginNetworkAddon")
                        .getDeclaredField("channels");
                trueuuid$channels.setAccessible(true);
                trueuuid$waits = Class.forName("net.fabricmc.fabric.impl.networking.server.ServerLoginNetworkAddon")
                        .getDeclaredField("waits");
                trueuuid$waits.setAccessible(true);
            }
            Map<?, ?> channels = (Map<?, ?>) trueuuid$channels.get(this);
            Collection<?> waits = (Collection<?>) trueuuid$waits.get(this);
            String signature = released + "|" + channels.size() + "|" + channels.keySet() + "|" + waits.size();
            if (signature.equals(trueuuid$lastSignature)) return;
            trueuuid$lastSignature = signature;
            Trueuuid.LOGGER.info("[diag] fabric addon queryTick={} channels={} keys={} waits={}",
                    released, channels.size(), channels.keySet(), waits.size());
        } catch (Throwable failure) {
            Trueuuid.LOGGER.warn("[diag] fabric addon diag failed", failure);
        }
    }
}
