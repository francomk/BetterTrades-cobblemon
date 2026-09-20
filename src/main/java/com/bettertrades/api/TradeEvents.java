package com.bettertrades.api;

import com.bettertrades.BetterTrades;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The level 2 events. The pre-commit one can cancel the trade by returning a reason, which is
 * shown to both players; the post one arrives once the trade has happened.
 *
 * Listeners run on the server thread inside the commit: no slow calls here.
 * A listener that throws must not be able to break the trade, so exceptions end up in the log
 * and the event carries on.
 */
public final class TradeEvents {

    private record PreListener(String modid, Function<TradeView.Pending, Optional<String>> handler) {}

    private record PostListener(String modid, Consumer<TradeView.Pending> handler) {}

    private static final List<PreListener> PRE = new CopyOnWriteArrayList<>();
    private static final List<PostListener> POST = new CopyOnWriteArrayList<>();

    private TradeEvents() {}

    static void addPre(String modid, Function<TradeView.Pending, Optional<String>> handler) {
        PRE.add(new PreListener(modid, handler));
    }

    static void addPost(String modid, Consumer<TradeView.Pending> handler) {
        POST.add(new PostListener(modid, handler));
    }

    /**
     * Server shutdown: registered listeners do not outlive the server that saw them register.
     *
     * The lists are static and live as long as the JVM: several worlds open in the same one, and a
     * mod registering on every server start would find itself running twice in the second world,
     * with the veto evaluated twice. It is the same reason Database.close() clears its own recovery
     * listeners. Anyone integrating BetterTrades registers on every server start, not once at their
     * own mod initialisation.
     */
    public static void clear() {
        PRE.clear();
        POST.clear();
    }

    public static void removeAll(String modid) {
        PRE.removeIf(listener -> listener.modid().equals(modid));
        POST.removeIf(listener -> listener.modid().equals(modid));
    }

    /** The reason of the first listener that blocks, or empty when the trade may go ahead. */
    public static Optional<String> firePre(TradeView.Pending pending) {
        for (PreListener listener : PRE) {
            try {
                Optional<String> veto = listener.handler().apply(pending);
                if (veto != null && veto.isPresent()) {
                    BetterTrades.LOGGER.info("Trade {} blocked by {}: {}",
                            pending.tradeId(), listener.modid(), veto.get());
                    return veto;
                }
            } catch (RuntimeException e) {
                BetterTrades.LOGGER.error("Pre-trade listener of {} failed: ignored",
                        listener.modid(), e);
            }
        }
        return Optional.empty();
    }

    public static void firePost(TradeView.Pending pending) {
        for (PostListener listener : POST) {
            try {
                listener.handler().accept(pending);
            } catch (RuntimeException e) {
                BetterTrades.LOGGER.error("Post-trade listener of {} failed: ignored",
                        listener.modid(), e);
            }
        }
    }
}
