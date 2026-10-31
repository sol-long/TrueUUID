package cn.alini.trueuuid.mixin.server;

import cn.alini.trueuuid.config.TrueuuidConfig;
import cn.alini.trueuuid.net.AuthAnswerPayload;
import cn.alini.trueuuid.net.AuthPayload;
import cn.alini.trueuuid.net.AuthQueryTracker;
import cn.alini.trueuuid.protocol.AuthWireCodec;
import cn.alini.trueuuid.protocol.ClientModRequirement;
import cn.alini.trueuuid.protocol.LoginStateMachine;
import cn.alini.trueuuid.protocol.MigrationTransaction;
import cn.alini.trueuuid.protocol.VerifiedProfile;
import cn.alini.trueuuid.server.AdapterRuntime;
import cn.alini.trueuuid.server.LoginAttempt;
import cn.alini.trueuuid.server.PlayerDataMigration;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.login.ClientboundCustomQueryPacket;
import net.minecraft.network.protocol.login.ServerboundCustomQueryAnswerPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.net.InetSocketAddress;
import java.util.UUID;
import java.security.SecureRandom;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

@Mixin(ServerLoginPacketListenerImpl.class)
abstract class ServerLoginMixin {
    @Unique private static final SecureRandom TRUEUUID$TRANSACTIONS = new SecureRandom();
    @Shadow private GameProfile authenticatedProfile;
    @Shadow public abstract void disconnect(Component reason);
    @Accessor("server")
    abstract MinecraftServer trueuuid$server();
    @Accessor("connection")
    abstract Connection trueuuid$connection();

    @Unique private final LoginAttempt trueuuid$attempt = new LoginAttempt();
    @Unique private int trueuuid$transaction;
    @Unique private VerifiedProfile trueuuid$pendingVerifiedProfile;
    @Unique private PlayerDataMigration.OfflineData trueuuid$pendingOfflineData;
    @Unique private String trueuuid$pendingIp;
    @Unique private String trueuuid$pendingEndpoint;
    @Unique private int trueuuid$diagTick;

    // Install the authentication gate before vanilla exposes VERIFYING to the
    // server tick. Waiting for handleHello TAIL permits a native-login race.
    @Inject(method = "startClientVerification", at = @At("HEAD"))
    private void trueuuid$begin(GameProfile profile, CallbackInfo callback) {
        MinecraftServer server = trueuuid$server();
        Connection connection = trueuuid$connection();
        if (server.usesAuthentication() || profile == null) return;
        if (AdapterRuntime.isMigrationPending(profile.getName())) {
            disconnect(Component.translatable("trueuuid.disconnect.migration_pending"));
            return;
        }
        trueuuid$transaction = trueuuid$newTransaction();
        cn.alini.trueuuid.Trueuuid.LOGGER.info("[diag] login gate armed tx={} player={}", trueuuid$transaction, profile.getName());
        byte[] query = trueuuid$attempt.begin(trueuuid$transaction, UUID.randomUUID().toString().replace("-", ""),
                System.currentTimeMillis());
        cn.alini.trueuuid.Trueuuid.acceptance("phase=auth_query_sent player={}", profile.getName());
        connection.send(new ClientboundCustomQueryPacket(trueuuid$transaction,
                new AuthPayload(AuthWireCodec.decodeQuery(query))));
    }

    // Holds the vanilla login while the client answers, and never finalises the login itself.
    // The finaliser (verifyLoginAndFinishConnectionSetup) is only ever reached through the vanilla
    // tick: other login-phase mods hold the very same tick to run their own login queries (e.g.
    // AutoModpack 5.x wraps tick() and refuses to call the original until its modpack verdict is in).
    // Invoking the finaliser directly from this handler would jump over those holds, so the player
    // would enter the world before the modpack check could run. Clearing the attempt returns the
    // phase to IDLE, which lets the held tick proceed, and vanilla finalises with
    // authenticatedProfile - the profile set by this mod.
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void trueuuid$pauseVanillaLogin(CallbackInfo callback) {
        if ((trueuuid$diagTick++ % 20) == 0) {
            cn.alini.trueuuid.Trueuuid.LOGGER.info("[diag] login tick reached, phase={}", trueuuid$attempt.phase());
        }
        if (!trueuuid$attempt.phase().equals(cn.alini.trueuuid.protocol.LoginStateMachine.Phase.IDLE)) {
            callback.cancel();
            boolean migration = trueuuid$attempt.phase() == LoginStateMachine.Phase.AWAITING_MIGRATION
                    || trueuuid$attempt.phase() == LoginStateMachine.Phase.MIGRATING;
            long timeoutMs = migration ? Math.max(TrueuuidConfig.timeoutMs(), 180_000L) : TrueuuidConfig.timeoutMs();
            if (trueuuid$attempt.timeout(System.currentTimeMillis(), timeoutMs) != LoginAttempt.Result.TIMEOUT) return;
            String name = authenticatedProfile == null ? null : authenticatedProfile.getName();
            if (migration) {
                cn.alini.trueuuid.Trueuuid.acceptance("result=migration_timeout player={}", name == null ? "<unknown>" : name);
                disconnect(Component.translatable("trueuuid.disconnect.migration_confirm_timeout"));
            } else {
                disconnect(Component.translatable("trueuuid.disconnect.timeout"));
            }
            trueuuid$clear();
        }
    }

    @Inject(method = "handleCustomQueryPacket", at = @At("HEAD"), cancellable = true)
    private void trueuuid$verify(ServerboundCustomQueryAnswerPacket packet, CallbackInfo callback) {
        if (packet.transactionId() != trueuuid$transaction || authenticatedProfile == null) return;
        cn.alini.trueuuid.Trueuuid.LOGGER.info("[diag] answer received tx={} phase={} payload={}", packet.transactionId(),
                trueuuid$attempt.phase(), packet.payload() == null ? "null" : packet.payload().getClass().getSimpleName());
        callback.cancel();
        if (packet.payload() == null) {
            cn.alini.trueuuid.Trueuuid.acceptance("result=client_mod_missing player={}", authenticatedProfile.getName());
            disconnect(Component.literal(ClientModRequirement.MISSING_CLIENT_MESSAGE));
            trueuuid$clear();
            return;
        }
        if (!(packet.payload() instanceof AuthAnswerPayload answer)) return;
        cn.alini.trueuuid.Trueuuid.debug("TrueUUID received authentication response: player={}", authenticatedProfile.getName());
        cn.alini.trueuuid.Trueuuid.acceptance("phase=auth_answer_received player={} migrationPhase={}",
                authenticatedProfile.getName(), trueuuid$attempt.phase() == LoginStateMachine.Phase.AWAITING_MIGRATION);
        MinecraftServer server = trueuuid$server();
        Connection connection = trueuuid$connection();
        String name = authenticatedProfile.getName();
        String ip = connection.getRemoteAddress() instanceof InetSocketAddress address && address.getAddress() != null
                ? address.getAddress().getHostAddress() : "";
        if (trueuuid$attempt.phase() == LoginStateMachine.Phase.AWAITING_MIGRATION) {
            trueuuid$handleMigrationAnswer(packet, answer, ip);
            return;
        }
        trueuuid$attempt.answer(packet.transactionId(), AuthWireCodec.encodeAnswer(answer.message()), name, ip, AdapterRuntime.verifier())
                .thenCompose(outcome -> {
                    if (outcome.result() != LoginAttempt.Result.VERIFIED || outcome.profile().isEmpty()) {
                        return CompletableFuture.completedFuture(new VerifiedLookup(outcome, null));
                    }
                    return AdapterRuntime.migrations().find(server, outcome.profile().get().name())
                            .thenApply(data -> new VerifiedLookup(outcome, data));
                })
                .whenComplete((lookup, failure) -> server.execute(() -> {
                    try {
                        if (!connection.isConnected()) return;
                        if (failure != null) {
                            cn.alini.trueuuid.Trueuuid.LOGGER.warn(
                                    "TrueUUID premium verification failed during migration lookup: player={}", name);
                            disconnect(Component.translatable("trueuuid.disconnect.auth_denied"));
                            return;
                        }
                        LoginAttempt.Outcome outcome = lookup.outcome();
                        if (outcome.result() != LoginAttempt.Result.VERIFIED || outcome.profile().isEmpty()) {
                            if (outcome.result() != LoginAttempt.Result.OFFLINE_REQUESTED) {
                                disconnect(Component.translatable("trueuuid.disconnect.auth_denied"));
                            } else if (!AdapterRuntime.canUseOfflineFallback(name)) {
                                cn.alini.trueuuid.Trueuuid.LOGGER.warn(
                                        "TrueUUID offline fallback denied for previously verified name: player={}", name);
                                cn.alini.trueuuid.Trueuuid.acceptance("result=known_deny player={}", name);
                                disconnect(Component.translatable("trueuuid.disconnect.bound_premium"));
                            } else {
                                cn.alini.trueuuid.Trueuuid.acceptance("result=offline_fallback player={}", name);
                                AdapterRuntime.recordOfflineFallback(authenticatedProfile);
                                // Login finalisation is left to the vanilla VERIFYING tick (see the comment on
                                // trueuuid$pauseVanillaLogin): the attempt is cleared below, which stops the
                                // tick pause, and the next tick finalises with authenticatedProfile.
                            }
                            return;
                        }
                        VerifiedProfile verified = outcome.profile().get();
                        if (lookup.offlineData() != null && !lookup.offlineData().offlineUuid().equals(verified.uuid())) {
                            cn.alini.trueuuid.Trueuuid.acceptance("phase=migration_needed player={} offlineUuid={} verifiedUuid={}",
                                    verified.name(), lookup.offlineData().offlineUuid(), verified.uuid());
                            trueuuid$requestMigrationConfirmation(verified, lookup.offlineData(), ip, answer.message().customEndpoint());
                            return;
                        }
                        authenticatedProfile = trueuuid$profile(verified);
                        AdapterRuntime.recordVerifiedProfile(verified, ip, answer.message().customEndpoint());
                        cn.alini.trueuuid.Trueuuid.acceptance("result=premium_ready player={} uuid={}", verified.name(), verified.uuid());
                        // No direct finaliser call: the vanilla VERIFYING tick does it once the attempt is
                        // cleared, so login-phase holds from other mods (e.g. AutoModpack's modpack check)
                        // keep their turn instead of being jumped over.
                    } finally {
                        if (trueuuid$attempt.phase() != LoginStateMachine.Phase.AWAITING_MIGRATION) trueuuid$clear();
                    }
                }));
    }

    @Inject(method = "onDisconnect", at = @At("HEAD"))
    private void trueuuid$disconnect(net.minecraft.network.DisconnectionDetails details, CallbackInfo callback) {
        trueuuid$clear();
    }

    @Unique private GameProfile trueuuid$profile(VerifiedProfile profile) {
        GameProfile nativeProfile = new GameProfile(profile.uuid(), profile.name());
        for (VerifiedProfile.Property property : profile.properties()) {
            nativeProfile.getProperties().put(property.name(), property.signature() == null
                    ? new Property(property.name(), property.value())
                    : new Property(property.name(), property.value(), property.signature()));
        }
        return nativeProfile;
    }

    @Unique private void trueuuid$clear() {
        if (trueuuid$pendingVerifiedProfile != null) {
            AdapterRuntime.clearMigrationPending(trueuuid$pendingVerifiedProfile.name());
        }
        AuthQueryTracker.clear(trueuuid$transaction);
        trueuuid$transaction = 0;
        trueuuid$pendingVerifiedProfile = null;
        trueuuid$pendingOfflineData = null;
        trueuuid$pendingIp = null;
        trueuuid$pendingEndpoint = null;
        trueuuid$attempt.disconnect();
        cn.alini.trueuuid.Trueuuid.LOGGER.info("[diag] attempt cleared, phase={}", trueuuid$attempt.phase());
    }

    @Unique private int trueuuid$newTransaction() {
        int transaction;
        do {
            transaction = TRUEUUID$TRANSACTIONS.nextInt(1, Integer.MAX_VALUE);
        } while (!AuthQueryTracker.mark(transaction));
        return transaction;
    }

    @Unique private void trueuuid$requestMigrationConfirmation(VerifiedProfile verified,
                                                               PlayerDataMigration.OfflineData offlineData,
                                                               String ip, String endpoint) {
        AuthQueryTracker.clear(trueuuid$transaction);
        trueuuid$transaction = trueuuid$newTransaction();
        trueuuid$pendingVerifiedProfile = verified;
        trueuuid$pendingOfflineData = offlineData;
        trueuuid$pendingIp = ip;
        trueuuid$pendingEndpoint = endpoint;
        byte[] wire = trueuuid$attempt.migrationQuery(trueuuid$transaction,
                new MigrationTransaction.Offer(offlineData.offlineUuid(), offlineData.summary()),
                System.currentTimeMillis());
        cn.alini.trueuuid.Trueuuid.acceptance("phase=migration_query_sent player={} offlineUuid={} verifiedUuid={}",
                verified.name(), offlineData.offlineUuid(), verified.uuid());
        trueuuid$connection().send(new ClientboundCustomQueryPacket(trueuuid$transaction,
                new AuthPayload(AuthWireCodec.decodeQuery(wire))));
    }

    @Unique private void trueuuid$handleMigrationAnswer(ServerboundCustomQueryAnswerPacket packet,
                                                        AuthAnswerPayload answer, String ip) {
        if (!trueuuid$attempt.acceptMigration(packet.transactionId(), AuthWireCodec.encodeAnswer(answer.message()))
                || trueuuid$pendingVerifiedProfile == null || trueuuid$pendingOfflineData == null) {
            cn.alini.trueuuid.Trueuuid.acceptance("result=migration_rejected player={}",
                    authenticatedProfile == null ? "<unknown>" : authenticatedProfile.getName());
            trueuuid$sendDuplicateUuidDisconnect();
            trueuuid$clear();
            return;
        }
        VerifiedProfile verified = trueuuid$pendingVerifiedProfile;
        PlayerDataMigration.OfflineData offlineData = trueuuid$pendingOfflineData;
        String migrationName = verified.name();
        cn.alini.trueuuid.Trueuuid.acceptance("phase=migration_answer_accepted player={} uuid={}", migrationName, verified.uuid());
        AdapterRuntime.markMigrationPending(migrationName);
        AdapterRuntime.migrations().migrate(trueuuid$server(), migrationName, verified.uuid())
                .whenComplete((ignored, failure) -> trueuuid$server().execute(() -> {
                    try {
                        if (!trueuuid$connection().isConnected()) return;
                        if (failure != null) {
                            cn.alini.trueuuid.Trueuuid.acceptance("result=migration_failed player={}", migrationName);
                            disconnect(Component.translatable("trueuuid.disconnect.migration_failed",
                                    migrationName, offlineData.offlineUuid(), verified.uuid(),
                                    Component.translatable("trueuuid.error.internal")));
                            return;
                        }
                        authenticatedProfile = trueuuid$profile(verified);
                        AdapterRuntime.recordVerifiedProfile(verified,
                                trueuuid$pendingIp == null ? ip : trueuuid$pendingIp, trueuuid$pendingEndpoint);
                        cn.alini.trueuuid.Trueuuid.acceptance("result=migration_complete player={} uuid={}", migrationName, verified.uuid());
                        // Same as the premium path: hand the login back to the vanilla tick.
                    } finally {
                        AdapterRuntime.clearMigrationPending(migrationName);
                        trueuuid$clear();
                    }
                }));
    }

    @Unique private void trueuuid$sendDuplicateUuidDisconnect() {
        VerifiedProfile verified = trueuuid$pendingVerifiedProfile;
        PlayerDataMigration.OfflineData data = trueuuid$pendingOfflineData;
        if (verified == null || data == null) {
            disconnect(Component.translatable("trueuuid.disconnect.auth_denied"));
            return;
        }
        Component sourceName = trueuuid$pendingEndpoint == null || trueuuid$pendingEndpoint.isBlank()
                ? Component.translatable("trueuuid.auth_source.premium")
                : Component.translatable("trueuuid.auth_source.skin_site.with_name", trueuuid$pendingEndpoint);
        disconnect(Component.translatable("trueuuid.disconnect.duplicate_uuid",
                sourceName, data.offlineUuid(), verified.uuid(), verified.name()));
    }

    @Unique private record VerifiedLookup(LoginAttempt.Outcome outcome, PlayerDataMigration.OfflineData offlineData) {}
}
