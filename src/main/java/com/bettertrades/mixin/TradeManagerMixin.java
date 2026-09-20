package com.bettertrades.mixin;

import com.cobblemon.mod.common.trade.TradeManager;
import com.bettertrades.BetterTrades;
import com.bettertrades.trade.TradeSessions;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A full replacement of the native trade: the hook goes in where Cobblemon accepts the request and
 * cancels before TradeStartedPacket goes out, so the client's TradeGUI never opens.
 * The descriptor contains Cobblemon types only, so the mixin does not depend on Minecraft's
 * mappings.
 */
@Mixin(value = TradeManager.class, remap = false)
public abstract class TradeManagerMixin {

    /**
     * Cobblemon refuses the request before it even reaches onAccept when either side has an empty
     * party: canAccept reads the party of both sender and receiver and answers false, with a message
     * of its own that never mentions BetterTrades.
     *
     * That check was written when the only tradeable thing was Pokemon. Here items and money are
     * traded too, so two advertised features of the mod were unreachable for anyone keeping
     * everything in the PC or just starting out. Who may open a trade is decided by
     * TradeSessions.open, which has its own checks: here the answer is always yes and the decision
     * is left to it.
     */
    @Inject(method = "canAccept(Lcom/cobblemon/mod/common/trade/TradeManager$TradeRequest;)Z",
            at = @At("HEAD"), cancellable = true, remap = false)
    private void bettertrades$allowEmptyParty(TradeManager.TradeRequest request,
                                              CallbackInfoReturnable<Boolean> info) {
        info.setReturnValue(true);
    }

    @Inject(method = "onAccept(Lcom/cobblemon/mod/common/trade/TradeManager$TradeRequest;)V",
            at = @At("HEAD"), cancellable = true, remap = false)
    private void bettertrades$openOwnTrade(TradeManager.TradeRequest request, CallbackInfo info) {
        // It is ALWAYS cancelled, even when BetterTrades refuses to open the session.
        //
        // Cancelling only on success made a refusal fall back to Cobblemon's native trade, which
        // builds an ActiveTrade and sends the two TradeStartedPackets: blacklist not applied, no row
        // in the history, no escrow, no API event. The easiest way to get there was to refuse the
        // server resource pack, which makes Icons.ready false and therefore TradeSessions.open null.
        // The correct fallback for a refusal is "no trade", not "a trade outside the mod".
        info.cancel();

        ServerPlayerEntity sender = request.getSender();
        ServerPlayerEntity receiver = request.getReceiver();
        if (sender == null || receiver == null || sender.getServer() == null) return;

        try {
            TradeSessions.open(sender.getServer(), sender, receiver);
        } catch (RuntimeException e) {
            // Without this, the exception would travel back into Cobblemon's packet handling.
            // The native trade is already cancelled above, so the worst that remains is two players
            // with no screen - not a broken network channel.
            BetterTrades.LOGGER.error("Opening the trade between {} and {} failed: no screen",
                    sender.getGameProfile().getName(), receiver.getGameProfile().getName(), e);
        }
    }
}
