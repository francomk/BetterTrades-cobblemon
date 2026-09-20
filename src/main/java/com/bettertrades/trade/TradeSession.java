package com.bettertrades.trade;

import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.api.storage.party.PartyStore;
import com.cobblemon.mod.common.api.storage.pc.PCBox;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.bettertrades.api.TradeEvents;
import com.bettertrades.api.TradeView;
import com.bettertrades.blacklist.Blacklist;
import com.bettertrades.BetterTrades;
import com.bettertrades.config.BetterTradesConfig;
import com.bettertrades.db.Database;
import com.bettertrades.db.Ulid;
import com.bettertrades.economy.MoneyService;
import com.bettertrades.escrow.EscrowService;
import com.bettertrades.history.TradeHistory;
import com.bettertrades.lang.Lang;
import com.bettertrades.util.Sounds;
import com.bettertrades.util.Texts;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * A trade session between two players. Every method runs on the server thread: database writes
 * start here but are never waited on.
 *
 * The flow: offers are made, both players confirm, a three second window opens in which either of
 * them can cancel, then it commits. Any change to an offer clears both confirmations, and during
 * the window the offer cannot be touched any more.
 */
public final class TradeSession {

    public static final int SLOTS = 8;

    /** The cancel window between the second confirm and the trade going through. */
    public static final int LOCK_TICKS = 60;

    /** How many frames the cancel button fades through over that window. */
    public static final int LOCK_FRAMES = 8;

    /**
     * Past this many ticks in COMMITTING the session unblocks itself.
     *
     * It has to expire AFTER the economy's limit, not before. At a fixed 200 ticks - ten seconds -
     * against MoneyService's twenty, the watchdog always won, so the "the payment came back to an
     * already closed session" branch was not the rare case but the NORMAL one for every payment
     * over ten seconds: items already returned and money still in flight. Derived from that limit
     * instead of chosen separately, so the two can no longer drift apart.
     */
    private static final int COMMIT_TIMEOUT_TICKS =
            (int) ((MoneyService.operationTimeoutSeconds() + 5) * 20);

    /** How long escrow is waited for before the reserved slot is freed anyway. */
    private static final long PENDING_RELEASE_TIMEOUT_SECONDS = 5;

    /** Ticks during which a button that has just become "cancel" ignores clicks: a quarter of a second. */
    private static final int CONFIRM_GRACE_TICKS = 5;

    public enum Stage { OFFERING, LOCKED, COMMITTING, CLOSED }

    public static final class Side {
        private final UUID playerId;
        private final String playerName;
        private final List<OfferEntry> entries = new ArrayList<>(SLOTS);
        /**
         * Offers sent and not yet landed in {@link #entries}.
         *
         * The entry is only added when the escrow write comes back, which goes through another
         * thread. In the meantime sgui delivers every click of the same tick, and a drag produces
         * one per slot crossed: without this counter they all read the old size, the limit of eight
         * breaks, and the entries past the eighth stay invisible in the grid and can no longer be
         * removed.
         */
        private int pending;
        private boolean ready;
        private long money;

        private Side(ServerPlayerEntity player) {
            this.playerId = player.getUuid();
            this.playerName = player.getGameProfile().getName();
        }

        public UUID playerId() { return playerId; }
        public String playerName() { return playerName; }
        public List<OfferEntry> entries() { return List.copyOf(entries); }
        public boolean ready() { return ready; }
        public long money() { return money; }
        public boolean full() { return entries.size() + pending >= SLOTS; }
        public boolean empty() { return entries.isEmpty() && money == 0; }
    }

    private final MinecraftServer server;
    private final String tradeId = Ulid.next();
    private final long startedAt = System.currentTimeMillis();
    private final Side left;
    private final Side right;
    private final String world;
    private Stage stage = Stage.OFFERING;
    private int lockTicks;
    /** Ticks spent in COMMITTING, to notice that the payment is never coming back. */
    private int commitTicks;
    /** Grace ticks after the window opens, during which the button does not cancel. */
    private int graceTicks;
    /** Who was paying when COMMITTING was entered: the watchdog has to name them. */
    private String committingPayerName;
    /** The last state of the "inventory full" panel, to redraw only when it changes. */
    private boolean warned;
    /** True while the last checks are running: the offer must not move under them. */
    private boolean verifying;
    private CancelReason pendingCancel;
    private String pendingCancelName;
    private Runnable onChanged = () -> {};
    private java.util.function.Consumer<CancelReason> onClosed = reason -> {};

    TradeSession(MinecraftServer server, ServerPlayerEntity a, ServerPlayerEntity b) {
        this.server = server;
        this.left = new Side(a);
        this.right = new Side(b);
        this.world = a.getServerWorld().getRegistryKey().getValue().toString();
    }

    public String tradeId() { return tradeId; }
    public Stage stage() { return stage; }
    public Side left() { return left; }
    public Side right() { return right; }

    /** True while nothing may be added, removed or un-confirmed. */
    public boolean frozen() { return verifying || stage != Stage.OFFERING; }

    /**
     * Which of the cancel button's fade frames to draw, 0 while the window has just opened.
     * Only meaningful during {@link Stage#LOCKED}.
     */
    public int countdownFrame() {
        int elapsed = LOCK_TICKS - lockTicks;
        return Math.clamp(elapsed * LOCK_FRAMES / LOCK_TICKS, 0, LOCK_FRAMES - 1);
    }

    /** The GUI redraws here: one session, two mirrored screens. */
    public void onChanged(Runnable listener) { this.onChanged = listener; }

    public void onClosed(java.util.function.Consumer<CancelReason> listener) { this.onClosed = listener; }

    public Side sideOf(UUID playerId) {
        if (left.playerId.equals(playerId)) return left;
        if (right.playerId.equals(playerId)) return right;
        throw new IllegalArgumentException(playerId + " is not part of this trade");
    }

    public Side otherSide(UUID playerId) {
        return sideOf(playerId) == left ? right : left;
    }

    public ServerPlayerEntity playerOf(Side side) {
        return server.getPlayerManager().getPlayer(side.playerId);
    }

    // ------------------------------------------------------------------ offer

    /**
     * The item must ALREADY have been taken out of the inventory by the caller: here it is only
     * put into escrow. Passing a stack that is still in the inventory would duplicate it.
     */
    public void offerItem(ServerPlayerEntity player, ItemStack stack) {
        Side side = sideOf(player.getUuid());
        if (frozen() || side.full()) {
            // The caller already took it out of the inventory: refusing it without giving it back
            // would delete it.
            EscrowService.give(player, stack);
            return;
        }
        if (stack.isEmpty()) return;

        Optional<String> blocked = Blacklist.check(stack);
        if (blocked.isPresent()) {
            EscrowService.give(player, stack);
            Texts.denied(player, "chat.error.item_blacklisted", blocked.get());
            return;
        }

        ItemStack held = stack.copy();
        // The slot is reserved now, on the server thread: the next click arrives before the write
        // comes back, and it has to see this place already taken.
        side.pending++;

        CompletableFuture<String> writing;
        try {
            writing = EscrowService.hold(server, player, held, tradeId);
        } catch (RuntimeException e) {
            // hold() can throw BEFORE producing the future: encoding the stack, or a rejected
            // executor once the server has stopped. The item is already out of the inventory, so
            // without this branch it would vanish, and with no escrow row describing it, it would
            // not even come back at the next login.
            side.pending--;
            BetterTrades.LOGGER.error("Escrow not started for {}", side.playerName, e);
            EscrowService.give(player, held);
            Texts.denied(player, "chat.error.item_escrow");
            return;
        }

        // Releasing the reservation has a limit of its own, separate from the write.
        //
        // pending++ is on the server thread, pending-- used to be inside the callback only: an
        // escrow future that never completes - a stalled disk, an SQLite lock past busy_timeout -
        // left the counter high for the rest of the session, and the player read "offer full" with
        // fewer than eight entries in the grid. The limit sits on a COPY of the future, so the
        // write carries on regardless (see MoneyService: a timeout on the original future would
        // skip its body), and the callback below refuses the entry if the places really have run
        // out.
        writing.copy()
                .orTimeout(PENDING_RELEASE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .whenComplete((ignored, ignoredError) -> server.execute(() -> side.pending--));

        writing.whenComplete((escrowId, error) -> server.execute(() -> {
            // The player is resolved NOW, not taken from the one captured at click time: at least
            // one tick passes between the escrow write and this callback, and they may have
            // disconnected in between. Delivering to an already removed entity writes into an
            // inventory that will never be saved - PlayerManager.remove saves the file BEFORE
            // removing the player - and the item vanishes.
            ServerPlayerEntity owner = playerOf(side);
            boolean online = owner != null && !owner.isRemoved();
            if (error != null) {
                BetterTrades.LOGGER.error("Escrow failed for {}", side.playerName, error);
                if (!online) {
                    // Here the escrow row was never written: at the next login there is nothing to
                    // give back. It is the one point where the item is not automatically
                    // recoverable, and the staff has to know.
                    BetterTrades.LOGGER.error("Trade {}: {} disconnected while escrow was"
                                    + " failing. {} x{} NOT automatically recoverable",
                            tradeId, side.playerName, Registries.ITEM.getId(held.getItem()),
                            held.getCount());
                    Texts.staff(server, "chat.staff.item_lost", tradeId, side.playerName,
                            Registries.ITEM.getId(held.getItem()).toString(), held.getCount());
                    return;
                }
                EscrowService.give(owner, held);
                Texts.denied(owner, "chat.error.item_escrow");
                return;
            }
            if (frozen() || side.entries.size() >= SLOTS) {
                // giveBack closes the row BEFORE delivering, and if the player is gone it leaves it
                // HELD for the next login instead of marking it RETURNED for nothing.
                giveBack(new OfferEntry.Item(escrowId, held), side);
                return;
            }
            side.entries.add(new OfferEntry.Item(escrowId, held));
            if (online) Sounds.offer(owner);
            resetAgreement();
        }));
    }

    public void offerPokemon(ServerPlayerEntity player, Pokemon pokemon, PokemonFingerprint.Location location) {
        Side side = sideOf(player.getUuid());
        if (frozen() || side.full()) return;
        if (BetterTradesConfig.get().trade.respectTradeableFlag && !pokemon.getTradeable()) {
            Texts.denied(player, "chat.error.pokemon_untradeable");
            return;
        }
        Optional<String> blocked = Blacklist.check(pokemon);
        if (blocked.isPresent()) {
            Texts.denied(player, "chat.error.pokemon_blacklisted", blocked.get());
            return;
        }
        for (OfferEntry entry : side.entries) {
            if (entry instanceof OfferEntry.Mon mon && mon.pokemonUuid().equals(pokemon.getUuid())) return;
        }
        side.entries.add(new OfferEntry.Mon(pokemon.getUuid(),
                PokemonFingerprint.of(player, pokemon, location)));
        Sounds.offer(player);
        resetAgreement();
    }

    /**
     * Money does not leave the balance here: it stays with its owner and only moves at commit, with
     * the provider's atomic operation. That way there is no money in escrow to recover.
     */
    public void offerMoney(ServerPlayerEntity player, long amount) {
        Side side = sideOf(player.getUuid());
        if (frozen() || amount < 0) return;
        if (amount > 0 && !MoneyService.available()) {
            Texts.denied(player, "chat.error.economy_missing");
            return;
        }
        side.money = amount;
        Sounds.offer(player);
        resetAgreement();
    }

    /** The bin button: empties the whole offer, money included, and un-readies both sides. */
    public void withdraw(ServerPlayerEntity player) {
        Side side = sideOf(player.getUuid());
        if (frozen()) return;
        returnItemsOf(side);
        side.entries.clear();
        side.money = 0;
        Sounds.click(player);
        resetAgreement();
    }

    /** Takes one thing back out of the offer, which is what clicking it in the grid does. */
    public void removeEntry(ServerPlayerEntity player, int index) {
        Side side = sideOf(player.getUuid());
        if (frozen() || index < 0 || index >= side.entries.size()) return;
        OfferEntry entry = side.entries.remove(index);
        if (entry instanceof OfferEntry.Item item) {
            giveBack(item, side);
        }
        Sounds.click(player);
        resetAgreement();
    }

    // ------------------------------------------------------------------ confirmation

    /**
     * There is one button and one step: once both sides press it the countdown starts.
     *
     * A player whose inventory cannot hold what is coming their way is not allowed to press
     * it, because the alternative is dropping the items on the floor at the end of the trade.
     */
    public void setReady(UUID playerId, boolean ready) {
        // frozen() already covers verifying, so a second click while the balance read is in flight
        // does not queue another one on the economy worker, which has a single thread.
        if (frozen()) return;
        Side side = sideOf(playerId);
        ServerPlayerEntity player = playerOf(side);
        if (ready && inventoryBlocked(side)) {
            Texts.denied(player, "chat.error.inventory_full");
            return;
        }
        side.ready = ready;
        Sounds.accept(player);
        if (left.ready && right.ready) {
            beginCountdown();
            return;
        }
        onChanged.run();
    }

    /**
     * The cancel button inside the countdown: stops the trade for both.
     *
     * It is the same button as confirm and changes meaning the instant the window opens. A human
     * double click takes one or two ticks: without this grace period the second click cancels the
     * trade that was just confirmed, and the message even says the player wanted it. That still
     * leaves 55 good ticks to really cancel.
     */
    public void cancelCountdown(ServerPlayerEntity player) {
        if (stage != Stage.LOCKED || graceTicks > 0) return;
        cancel(CancelReason.PLAYER_CANCELLED, player.getGameProfile().getName());
    }


    /**
     * Re-checks what could have changed while the two were deciding, then starts the window.
     *
     * The Pokemon are re-read because one of them may have been moved, levelled or renamed
     * since it was offered, and the payer's balance because they may have spent it elsewhere.
     * A failed check drops both sides back to un-ready instead of cancelling: nothing is lost,
     * they just have to look at the offer again.
     */
    private void beginCountdown() {
        ServerPlayerEntity leftPlayer = playerOf(left);
        ServerPlayerEntity rightPlayer = playerOf(right);
        if (leftPlayer == null || rightPlayer == null) {
            cancel(CancelReason.DISCONNECT, leftPlayer == null ? left.playerName : right.playerName);
            return;
        }

        Side changed = firstChangedSide(leftPlayer, rightPlayer);
        if (changed != null) {
            unready("chat.error.pokemon_moved", changed.playerName);
            return;
        }

        long net = left.money - right.money;
        if (net == 0) {
            lock();
            return;
        }
        if (!MoneyService.available()) {
            unready("chat.error.economy_missing");
            return;
        }

        Side payer = net > 0 ? left : right;
        long amount = Math.abs(net);
        verifying = true;
        onChanged.run();
        MoneyService.balance(payer.playerId).whenComplete((balance, error) -> server.execute(() -> {
            verifying = false;
            if (stage != Stage.OFFERING) return;
            if (error != null || balance == null || balance < amount) {
                unready("chat.error.balance_gone", payer.playerName);
                return;
            }
            // The balance read can take up to the economy's timeout, and in that time tick() has
            // kept running: watchInventories may have dropped a confirmation because an inventory
            // filled up. Whoever validates an asynchronous check has to re-read the conditions that
            // started it too, not only the one being verified, or the countdown starts on a
            // confirmation that is no longer there.
            if (!left.ready || !right.ready) {
                onChanged.run();
                return;
            }
            if (inventoryBlocked(left) || inventoryBlocked(right)) {
                unready("chat.error.inventory_full");
                return;
            }
            lock();
        }));
    }

    private void lock() {
        stage = Stage.LOCKED;
        lockTicks = LOCK_TICKS;
        graceTicks = CONFIRM_GRACE_TICKS;
        Sounds.confirm(playerOf(left));
        Sounds.confirm(playerOf(right));
        onChanged.run();
    }

    private void unready(String key, Object... arguments) {
        left.ready = false;
        right.ready = false;
        ServerPlayerEntity leftPlayer = playerOf(left);
        ServerPlayerEntity rightPlayer = playerOf(right);
        if (leftPlayer != null) Texts.denied(leftPlayer, key, arguments);
        if (rightPlayer != null) Texts.denied(rightPlayer, key, arguments);
        onChanged.run();
    }

    /**
     * Runs every tick while the session is alive.
     *
     * It does two things: it moves the countdown, and it keeps an eye on the space in both
     * inventories. The second check cannot wait for a session event, because space also changes
     * by picking things up off the ground while the window is open.
     */
    void tick() {
        if (stage == Stage.OFFERING) {
            watchInventories();
            return;
        }
        if (stage == Stage.COMMITTING) {
            watchCommit();
            return;
        }
        if (stage != Stage.LOCKED) return;

        if (graceTicks > 0) graceTicks--;
        int frame = countdownFrame();
        lockTicks--;
        if (lockTicks <= 0) {
            commit();
            return;
        }
        if (countdownFrame() != frame) onChanged.run();
    }

    /**
     * The leash on COMMITTING.
     *
     * It is entered before asking for the payment and left only inside the callback. If that
     * callback never arrives - Impactor not coming back, or the economy thread busy with an earlier
     * call - nothing else pulls the session out: tick() returns immediately, cancel() only records
     * pendingCancel, and the two UUIDs stay in BY_PLAYER forever. The two players find themselves
     * "already in another trade" until the restart, and not even /bt cancel frees them.
     *
     * Here it unblocks anyway, and says so: the state of the payment at that point is unknown and
     * has to be looked at by hand.
     */
    private void watchCommit() {
        if (++commitTicks <= COMMIT_TIMEOUT_TICKS) return;
        BetterTrades.LOGGER.error("Trade {} stuck in COMMITTING for {} ticks: payment in an unknown"
                + " state, session closed by force", tradeId, commitTicks);
        Texts.staff(server, "chat.staff.commit_stuck", tradeId, left.playerName, right.playerName);
        // The message has to name whoever was really paying, not always left: the payer is the one
        // of the two whose net was positive, and commit() recorded it on the way in.
        String stuckPayer = committingPayerName != null ? committingPayerName : left.playerName;
        stage = Stage.OFFERING;
        pendingCancel = null;
        cancel(CancelReason.MONEY, stuckPayer);
    }

    private void watchInventories() {
        boolean leftBlocked = inventoryBlocked(left);
        boolean rightBlocked = inventoryBlocked(right);
        boolean blocked = leftBlocked || rightBlocked;

        // A confirmation given stays valid only while there is room: if the space disappears
        // afterwards, the confirmation drops by itself instead of starting a trade that drops items.
        boolean cleared = false;
        if (leftBlocked && left.ready) { left.ready = false; cleared = true; }
        if (rightBlocked && right.ready) { right.ready = false; cleared = true; }

        if (blocked != warned || cleared) {
            warned = blocked;
            onChanged.run();
        }
    }

    /**
     * True when this side has fewer free slots than what is about to land in them, counting the
     * inventory and the Pokemon storage separately.
     *
     * The Pokemon are in here because a full store does not refuse the delivery with an error: it
     * half accepts it and the Pokemon leaves the world. Capacity has to be checked before, not after.
     */
    public boolean inventoryBlocked(Side side) {
        ServerPlayerEntity player = playerOf(side);
        if (player == null) return false;

        int incomingItems = 0;
        int incomingPokemon = 0;
        for (OfferEntry entry : (side == left ? right : left).entries) {
            if (entry instanceof OfferEntry.Item) incomingItems++;
            else if (entry instanceof OfferEntry.Mon) incomingPokemon++;
        }

        if (incomingItems > 0) {
            int free = 0;
            for (ItemStack stack : player.getInventory().main) {
                if (stack.isEmpty()) free++;
            }
            if (free < incomingItems) return true;
        }

        // What leaves this side frees room for what arrives, so it does not count against capacity:
        // at the end of the trade the balance is the difference between the two.
        int outgoingPokemon = 0;
        for (OfferEntry entry : side.entries) {
            if (entry instanceof OfferEntry.Mon) outgoingPokemon++;
        }
        int netPokemon = incomingPokemon - outgoingPokemon;
        return netPokemon > 0 && freePokemonSlots(player) < netPokemon;
    }

    /** Free places across party and PC: how many Pokemon that player can still receive. */
    private int freePokemonSlots(ServerPlayerEntity player) {
        PartyStore party = Cobblemon.INSTANCE.getStorage().getParty(player);
        int free = party.size() - party.occupied();
        for (PCBox box : Cobblemon.INSTANCE.getStorage().getPC(player).getBoxes()) {
            free += box.getUnoccupiedSlots();
        }
        return free;
    }

    private void resetAgreement() {
        left.ready = false;
        right.ready = false;
        onChanged.run();
    }

    // ------------------------------------------------------------------ closing

    /**
     * @return true when the session was closed just now; false when it was already closed or when
     *         the cancel was only queued because the payment is in flight. Whoever answers an
     *         administrator has to tell the two apart: "done" on a queued cancel sends them looking
     *         elsewhere for two players who are still inside a trade.
     */
    public boolean cancel(CancelReason reason, String culpritName) {
        if (stage == Stage.CLOSED) return false;
        if (stage == Stage.COMMITTING) {
            // The money is already in flight: the cancel happens when it comes back, otherwise the
            // items would be given back while the payment succeeds.
            pendingCancel = reason;
            pendingCancelName = culpritName;
            return false;
        }
        stage = Stage.CLOSED;

        ServerPlayerEntity leftPlayer = playerOf(left);
        ServerPlayerEntity rightPlayer = playerOf(right);
        returnItemsOf(left);
        returnItemsOf(right);
        left.entries.clear();
        right.entries.clear();

        net.minecraft.text.Text message = Lang.text(reason.key(), culpritName);
        if (leftPlayer != null) {
            Texts.chat(leftPlayer, message);
            Sounds.cancelled(leftPlayer);
        }
        if (rightPlayer != null) {
            Texts.chat(rightPlayer, message);
            Sounds.cancelled(rightPlayer);
        }

        TradeSessions.forget(this);
        onClosed.accept(reason);
        return true;
    }

    /**
     * Puts back into a side's hands what they had offered.
     *
     * The player no longer has to be passed in: escrow is closed before delivery and the recipient
     * is resolved at delivery time, so whoever disconnected in the meantime finds their rows still
     * HELD and picks them up at the next login.
     */
    private void returnItemsOf(Side side) {
        List<EscrowService.Custody> items = new ArrayList<>();
        for (OfferEntry entry : side.entries) {
            if (entry instanceof OfferEntry.Item item) {
                items.add(new EscrowService.Custody(item.escrowId(), item.stack(),
                        side.playerId, side.playerName));
            }
        }
        EscrowService.giveBack(server, tradeId, items, false);
    }

    /** A single entry coming back: the bin, the grid, an offer that landed late. */
    private void giveBack(OfferEntry.Item item, Side side) {
        EscrowService.giveBack(server, tradeId, List.of(new EscrowService.Custody(
                item.escrowId(), item.stack(), side.playerId, side.playerName)), false);
    }

    private void commit() {
        ServerPlayerEntity leftPlayer = playerOf(left);
        ServerPlayerEntity rightPlayer = playerOf(right);
        if (leftPlayer == null || rightPlayer == null) {
            cancel(CancelReason.DISCONNECT, leftPlayer == null ? left.playerName : right.playerName);
            return;
        }
        if (!Database.usable()) {
            cancel(CancelReason.DATABASE_DOWN, left.playerName);
            return;
        }

        Side changed = firstChangedSide(leftPlayer, rightPlayer);
        if (changed != null) {
            Texts.staff(server, "chat.staff.pokemon_changed", tradeId, changed.playerName);
            cancel(CancelReason.POKEMON_CHANGED, changed.playerName);
            return;
        }

        String blacklisted = firstBlacklisted(leftPlayer, rightPlayer);
        if (blacklisted != null) {
            cancel(CancelReason.BLACKLIST, blacklisted);
            return;
        }

        // watchInventories stops at OFFERING: during the three seconds of the cancel window the
        // space can disappear - things picked up off the ground, a chest opened, another plugin -
        // and without this check the items would end up on the floor in front of anyone passing by.
        if (inventoryBlocked(left) || inventoryBlocked(right)) {
            cancel(CancelReason.INVENTORY_FULL,
                    inventoryBlocked(left) ? left.playerName : right.playerName);
            return;
        }

        TradeView.Pending pending = pendingView(leftPlayer, rightPlayer);
        Optional<String> veto = TradeEvents.firePre(pending);
        if (veto.isPresent()) {
            cancel(CancelReason.BLOCKED, veto.get());
            return;
        }

        // Cobblemon's standard veto, which the mixin had taken out of the way along with performTrade.
        // Mods written for Cobblemon listen to that, not to BetterTrades' API.
        if (CobblemonTrade.vetoed(tradeId, leftPlayer, offeredPokemon(left, leftPlayer),
                rightPlayer, offeredPokemon(right, rightPlayer))) {
            cancel(CancelReason.BLOCKED, Lang.raw("chat.cancel.blocked.cobblemon"));
            return;
        }

        long net = left.money - right.money;
        if (net == 0) {
            finishTransfer(pending, leftPlayer, rightPlayer, 0);
            return;
        }
        if (!MoneyService.available()) {
            cancel(CancelReason.MONEY, Lang.raw("chat.cancel.money.no_economy"));
            return;
        }

        // Only the difference is moved: a single transaction, so there is no state where one
        // payment went through and the other did not.
        stage = Stage.COMMITTING;
        commitTicks = 0;
        UUID payer = net > 0 ? left.playerId : right.playerId;
        UUID payee = net > 0 ? right.playerId : left.playerId;
        String payerName = net > 0 ? left.playerName : right.playerName;
        long amount = Math.abs(net);
        committingPayerName = payerName;

        MoneyService.Operation<Boolean> payment = MoneyService.pay(payer, payee, amount);
        payment.awaited().whenComplete((paid, error) -> server.execute(() -> {
            // The watchdog may already have closed this session while the payment was in flight.
            // Carrying on would mean delivering the items a second time, on a session that as far
            // as TradeSessions is concerned no longer exists.
            if (stage != Stage.COMMITTING) {
                BetterTrades.LOGGER.error("Trade {}: the payment came back to an already closed"
                        + " session (stage {}). Payment outcome: {}", tradeId, stage,
                        error != null ? "error" : String.valueOf(paid));
                if (error != null) {
                    refundIfItLandsLate(payment, payee, payer, amount, payerName);
                } else if (Boolean.TRUE.equals(paid)) {
                    refund(payee, payer, amount, payerName);
                }
                return;
            }
            if (error != null) {
                // A timeout, not a refusal: the transfer is still on the economy worker and may
                // succeed a minute from now. Cancelling here and nothing else would leave the payer
                // without money and without items, so the trade closes and the refund hooks onto the
                // real outcome instead of the one we stopped waiting for.
                refundIfItLandsLate(payment, payee, payer, amount, payerName);
                stage = Stage.OFFERING;
                pendingCancel = null;
                cancel(CancelReason.MONEY, payerName);
                return;
            }
            if (!Boolean.TRUE.equals(paid)) {
                stage = Stage.OFFERING;
                pendingCancel = null;
                cancel(CancelReason.MONEY, payerName);
                return;
            }
            ServerPlayerEntity nowLeft = playerOf(left);
            ServerPlayerEntity nowRight = playerOf(right);
            CancelReason queued = pendingCancel;
            if (queued == null && (nowLeft == null || nowRight == null)) {
                queued = CancelReason.DISCONNECT;
                pendingCancelName = nowLeft == null ? left.playerName : right.playerName;
            }
            if (queued != null) {
                refund(payee, payer, amount, payerName);
                stage = Stage.OFFERING;
                pendingCancel = null;
                cancel(queued, pendingCancelName);
                return;
            }
            finishTransfer(pending, nowLeft, nowRight, net > 0 ? amount : -amount);
        }));
    }

    /**
     * Puts the money back where it was after a cancel that arrived once the payment had started.
     *
     * transfer() returns false without throwing when the account cannot be resolved, when the
     * balance is no longer enough because the payee has already spent it, and when there is no
     * economy. Discarding that value and logging "refunded" leaves the payer without money and with
     * nothing in hand, and the staff investigating reads a line saying the opposite. A second
     * attempt covers an Impactor timeout; beyond that a human hand is needed, and it has to be said.
     */
    private void refund(UUID payee, UUID payer, long amount, String payerName) {
        attemptRefund(payee, payer, amount, payerName, true);
    }

    /**
     * One refund attempt, and the second only once the first has really finished.
     *
     * The difference between "the provider said no" and "it did not answer in time" is worth double
     * the money here: a transfer() that expires is still running on the economy worker, so retrying
     * would give the amount back twice. It is retried only on a real false, which is a finished
     * operation that moved nothing.
     */
    private void attemptRefund(UUID payee, UUID payer, long amount, String payerName, boolean first) {
        MoneyService.pay(payee, payer, amount).awaited().whenComplete((paid, error) -> {
            if (error == null && Boolean.TRUE.equals(paid)) {
                BetterTrades.LOGGER.warn("Trade {} cancelled after the payment: {} returned to {}{}",
                        tradeId, amount, payerName, first ? "" : " on the second attempt");
                return;
            }
            if (error != null) {
                BetterTrades.LOGGER.error("REFUND OF UNKNOWN OUTCOME trade={} amount={} from={} to={} ({}):"
                                + " it did not answer in time and may still succeed on its own."
                                + " Check the balance BEFORE refunding by hand; the real outcome"
                                + " ends up in the economy log",
                        tradeId, amount, payee, payer, payerName, error);
                server.execute(() -> Texts.staff(server, "chat.staff.refund_unknown",
                        tradeId, amount, payerName));
                return;
            }
            if (first) {
                attemptRefund(payee, payer, amount, payerName, false);
                return;
            }
            BetterTrades.LOGGER.error("REFUND FAILED trade={} amount={} from={} to={} ({}):"
                            + " {} paid and received nothing, they must be refunded by hand",
                    tradeId, amount, payee, payer, payerName, payerName);
            server.execute(() -> Texts.staff(server, "chat.staff.refund_failed",
                    tradeId, amount, payerName));
        });
    }

    /**
     * Hooks the refund onto the REAL outcome of a payment given up for lost.
     *
     * {@code awaited} has expired and the trade has already been cancelled, but the transfer was
     * never switched off: if it lands, the money has moved for a trade that no longer exists.
     * {@code settled} is the only place where that outcome shows up.
     */
    private void refundIfItLandsLate(MoneyService.Operation<Boolean> payment, UUID payee, UUID payer,
                                     long amount, String payerName) {
        payment.settled().whenComplete((paid, error) -> {
            if (error != null || !Boolean.TRUE.equals(paid)) return;
            BetterTrades.LOGGER.error("Trade {}: the payment of {} by {} succeeded AFTER the trade"
                            + " had been cancelled on timeout. Refund in progress.",
                    tradeId, amount, payerName);
            refund(payee, payer, amount, payerName);
        });
    }

    /**
     * @param netFromLeft how much left {@code left}'s balance and entered {@code right}'s;
     *                    negative when it went the other way, zero when nobody paid.
     */
    private void finishTransfer(TradeView.Pending pending, ServerPlayerEntity leftPlayer,
                                ServerPlayerEntity rightPlayer, long netFromLeft) {
        stage = Stage.CLOSED;
        // The try opens HERE, not after the preparation.
        //
        // Between stage = CLOSED and the delivery, describe() and collectItems() run: they call
        // Cobblemon and the item codec, so they can throw. With the try further down, an exception
        // from them left the session CLOSED and still inside BY_PLAYER: cancel() returns straight
        // away on the CLOSED guard, tick() no longer reaches it, and the two players stayed
        // "already in another trade" until the restart. On the money path there was not even a net
        // above: there finishTransfer runs inside the server.execute of the economy callback, not
        // inside TradeSessions.tick's try/catch, and ThreadExecutor merely logs.
        try {
            // The summary is built before anything moves: afterwards the offers are no longer there.
            List<net.minecraft.text.Text> leftOffered = describe(left, leftPlayer);
            List<net.minecraft.text.Text> rightOffered = describe(right, rightPlayer);

            List<TradeHistory.ItemRow> itemRows = new ArrayList<>();
            List<EscrowService.Custody> deliveries = new ArrayList<>();
            collectItems(left, right, itemRows, deliveries);
            collectItems(right, left, itemRows, deliveries);

            // Escrow is closed before delivery, but without stopping the server thread: handOver
            // marks CONSUMED on the escrow thread and delivers one pass later, to whoever is still
            // connected at that moment. A failed close delivers nothing and leaves the rows HELD,
            // which is another way of saying "the item goes back to its owner at the next login".
            EscrowService.handOver(server, tradeId, deliveries);

            List<TradeHistory.PokemonRow> pokemonRows = new ArrayList<>();
            movePokemon(leftPlayer, rightPlayer, pokemonRows);

            TradeHistory.write(server, new TradeHistory.Completed(tradeId, left.playerId, left.playerName,
                    right.playerId, right.playerName, startedAt, System.currentTimeMillis(), world,
                    "COBBLEMON", itemRows, pokemonRows, moneyRows(netFromLeft)));

            sendOutcome(leftPlayer, right.playerName, rightOffered, leftOffered);
            sendOutcome(rightPlayer, left.playerName, leftOffered, rightOffered);
        } catch (RuntimeException e) {
            // The trade went halfway. Whatever was still in escrow stays HELD and comes back at the
            // next login; whatever already moved is recorded in the log.
            BetterTrades.LOGGER.error("Trade {}: delivery interrupted halfway. The session is closed"
                    + " anyway, unresolved escrow comes back at the next login", tradeId, e);
        } finally {
            // Whatever throws above - Cobblemon on a Pokemon that will not serialise, a species
            // removed by a datapack, a third-party observer - the session MUST leave BY_PLAYER.
            // Otherwise it stays there with stage CLOSED and the two players are stuck forever:
            // cancel() returns immediately on the guard, so not even /bt cancel frees them.
            // li libera.
            TradeSessions.forget(this);
            onClosed.accept(null);
        }
        // Outside the finally, to keep the previous ordering: the event reaches other mods once the
        // session is no longer in BY_PLAYER. With a try of its own, though: the code running here
        // is not ours and must not be able to travel back up into the economy callback.
        try {
            TradeEvents.firePost(pending);
        } catch (RuntimeException e) {
            BetterTrades.LOGGER.error("Trade {}: an API observer threw on firePost."
                    + " The trade was already complete", tradeId, e);
        }
    }



    /** One line per offered piece, ready for the end-of-trade summary. */
    private List<net.minecraft.text.Text> describe(Side side, ServerPlayerEntity player) {
        List<net.minecraft.text.Text> lines = new ArrayList<>();
        for (OfferEntry entry : side.entries) {
            if (entry instanceof OfferEntry.Item item) {
                lines.add(Lang.mixed("chat.trade.summary.item",
                        item.stack().getCount(), item.stack().getName()));
            } else if (entry instanceof OfferEntry.Mon mon) {
                Pokemon pokemon = player == null ? null : PokemonFingerprint.find(player, mon.pokemonUuid());
                lines.add(pokemon == null
                        ? Lang.text("chat.trade.summary.pokemon_unknown")
                        : Lang.text("chat.trade.summary.pokemon",
                                pokemon.getSpecies().getName(), pokemon.getLevel()));
            }
        }
        if (side.money > 0) {
            lines.add(Lang.text("chat.trade.summary.money", side.money, MoneyService.currencyKey()));
        }
        return lines;
    }

    private void sendOutcome(ServerPlayerEntity player, String otherName,
                             List<net.minecraft.text.Text> received, List<net.minecraft.text.Text> given) {
        if (player == null) return;
        Sounds.success(player);
        Texts.chat(player, "chat.trade.completed", otherName);
        if (!BetterTradesConfig.get().general.tradeSummary) return;
        Texts.chat(player, "chat.trade.summary.header", otherName);
        summaryLines(player, "chat.trade.summary.received", received);
        summaryLines(player, "chat.trade.summary.given", given);
    }

    private void summaryLines(ServerPlayerEntity player, String key, List<net.minecraft.text.Text> lines) {
        if (lines.isEmpty()) {
            Texts.chat(player, Lang.mixed(key, Lang.text("chat.trade.summary.nothing")));
            return;
        }
        for (net.minecraft.text.Text line : lines) Texts.chat(player, Lang.mixed(key, line));
    }

    /**
     * One row per player: how much they had offered, and how much really came in or went out.
     *
     * The two numbers almost never match, because only the difference between the two offers is
     * transferred, in a single transaction. Anyone reconstructing a dispute from the gross offers
     * alone computes a balance change that never happened.
     */
    private List<TradeHistory.MoneyRow> moneyRows(long netFromLeft) {
        List<TradeHistory.MoneyRow> rows = new ArrayList<>(2);
        String currency = MoneyService.currencyKey();
        if (left.money > 0 || netFromLeft != 0) {
            rows.add(new TradeHistory.MoneyRow(left.playerId, left.money, -netFromLeft, currency));
        }
        if (right.money > 0 || netFromLeft != 0) {
            rows.add(new TradeHistory.MoneyRow(right.playerId, right.money, netFromLeft, currency));
        }
        return rows;
    }

    /**
     * The second blacklist check: a rule may have been added while the trade was already open, and
     * what got in before must not go through regardless.
     */
    private String firstBlacklisted(ServerPlayerEntity leftPlayer, ServerPlayerEntity rightPlayer) {
        String found = blacklistedIn(left, leftPlayer);
        return found != null ? found : blacklistedIn(right, rightPlayer);
    }

    private String blacklistedIn(Side side, ServerPlayerEntity player) {
        for (OfferEntry entry : side.entries) {
            if (entry instanceof OfferEntry.Item item) {
                Optional<String> blocked = Blacklist.check(item.stack());
                if (blocked.isPresent()) {
                    return Lang.raw("chat.cancel.blacklist.item", side.playerName, blocked.get());
                }
            } else if (entry instanceof OfferEntry.Mon mon) {
                Pokemon pokemon = PokemonFingerprint.find(player, mon.pokemonUuid());
                if (pokemon == null) continue;
                Optional<String> blocked = Blacklist.check(pokemon);
                if (blocked.isPresent()) {
                    return Lang.raw("chat.cancel.blacklist.pokemon", side.playerName, blocked.get());
                }
            }
        }
        return null;
    }

    private TradeView.Pending pendingView(ServerPlayerEntity leftPlayer, ServerPlayerEntity rightPlayer) {
        List<TradeView.ItemView> items = new ArrayList<>();
        List<TradeView.MonView> pokemon = new ArrayList<>();
        collectView(left, leftPlayer, items, pokemon);
        collectView(right, rightPlayer, items, pokemon);
        return new TradeView.Pending(tradeId, left.playerId, right.playerId,
                List.copyOf(items), List.copyOf(pokemon));
    }

    private void collectView(Side side, ServerPlayerEntity player, List<TradeView.ItemView> items,
                             List<TradeView.MonView> pokemon) {
        for (OfferEntry entry : side.entries) {
            if (entry instanceof OfferEntry.Item item) {
                items.add(new TradeView.ItemView(side.playerId,
                        Registries.ITEM.getId(item.stack().getItem()).toString(), item.stack().getCount()));
            } else if (entry instanceof OfferEntry.Mon mon) {
                Pokemon found = player == null ? null : PokemonFingerprint.find(player, mon.pokemonUuid());
                if (found == null) continue;
                pokemon.add(new TradeView.MonView(side.playerId,
                        found.getSpecies().getResourceIdentifier().toString(),
                        found.getForm().getName(), found.getLevel(), found.getShiny(),
                        java.util.Set.copyOf(found.getAspects())));
            }
        }
    }

    private Side firstChangedSide(ServerPlayerEntity leftPlayer, ServerPlayerEntity rightPlayer) {
        for (OfferEntry entry : left.entries) {
            if (entry instanceof OfferEntry.Mon mon && !mon.fingerprint().stillMatches(leftPlayer)) return left;
        }
        for (OfferEntry entry : right.entries) {
            if (entry instanceof OfferEntry.Mon mon && !mon.fingerprint().stillMatches(rightPlayer)) return right;
        }
        return null;
    }

    /** A Pokemon already out of the offering player's store and not yet arrived at its destination. */
    private record InFlight(TradeHistory.PokemonRow row, Pokemon pokemon, UUID pokemonUuid,
                            ServerPlayerEntity fromPlayer, ServerPlayerEntity toPlayer,
                            String fromName) {}

    /**
     * The Pokemon move, kept apart from the rest.
     *
     * They are first taken from both sides, then delivered to both: moving one side at a time, the
     * side served first still finds the slots of the Pokemon it is giving away occupied, and a
     * one-for-one trade with a full party would fail for nothing.
     *
     * An exception from Cobblemon in here must not take the history and the end-of-trade messages
     * with it: the items have already been delivered, and the session has to close anyway.
     */
    private void movePokemon(ServerPlayerEntity leftPlayer, ServerPlayerEntity rightPlayer,
                             List<TradeHistory.PokemonRow> rows) {
        List<InFlight> inFlight = new ArrayList<>();
        List<InFlight> delivered = new ArrayList<>();
        try {
            takePokemon(left, leftPlayer, rightPlayer, inFlight);
            takePokemon(right, rightPlayer, leftPlayer, inFlight);
        } finally {
            // Whatever already left a store has to be delivered or put back, even if takePokemon
            // threw halfway: otherwise it stays outside every store.
            deliverPokemon(inFlight, rows, delivered);

            // From here on it is performTrade's tail, which the mixin no longer lets run: first the
            // trade evolutions on the Pokemon that really arrived, then the event. Only on the ones
            // really delivered: a Pokemon returned to its sender was not traded and must neither
            // evolve nor show up in TRADE_EVENT_POST.
            List<Pokemon> outOfLeft = deliveredFrom(delivered, leftPlayer);
            List<Pokemon> outOfRight = deliveredFrom(delivered, rightPlayer);
            CobblemonTrade.evolve(tradeId, outOfLeft, outOfRight);
            CobblemonTrade.firePost(tradeId, leftPlayer, outOfLeft, rightPlayer, outOfRight);
        }
    }

    /** The delivered Pokemon that had started from this player. */
    private List<Pokemon> deliveredFrom(List<InFlight> delivered, ServerPlayerEntity fromPlayer) {
        List<Pokemon> pokemon = new ArrayList<>();
        for (InFlight held : delivered) {
            if (held.fromPlayer() == fromPlayer) pokemon.add(held.pokemon());
        }
        return pokemon;
    }

    /** The Pokemon offered by one side, resolved now in the store of whoever offers them. */
    private List<Pokemon> offeredPokemon(Side side, ServerPlayerEntity player) {
        List<Pokemon> pokemon = new ArrayList<>();
        for (OfferEntry entry : side.entries) {
            if (!(entry instanceof OfferEntry.Mon mon)) continue;
            Pokemon found = PokemonFingerprint.find(player, mon.pokemonUuid());
            if (found != null) pokemon.add(found);
        }
        return pokemon;
    }

    /** First half: the Pokemon leave their source stores and stay in the method's hands. */
    private void takePokemon(Side from, ServerPlayerEntity fromPlayer, ServerPlayerEntity toPlayer,
                             List<InFlight> inFlight) {
        for (OfferEntry entry : from.entries) {
            if (!(entry instanceof OfferEntry.Mon offered)) continue;
            Pokemon pokemon = PokemonFingerprint.find(fromPlayer, offered.pokemonUuid());
            if (pokemon == null) continue;

            TradeHistory.PokemonRow row = TradeHistory.PokemonRow.of(server, from.playerId, pokemon);
            if (!removeFrom(fromPlayer, pokemon)) continue;
            inFlight.add(new InFlight(row, pokemon, offered.pokemonUuid(), fromPlayer, toPlayer,
                    from.playerName));
        }
    }

    /**
     * Second half: the delivery.
     *
     * add() on a full store returns false instead of throwing. Without looking at that value the
     * Pokemon stays outside every store - taken from whoever offered it, never arrived at whoever
     * receives it - and the history records a transfer that never happened.
     *
     * The history row is only written on a successful delivery. If the delivery fails the Pokemon
     * goes back to whoever offered it and the staff is told: the rest of the trade carries on,
     * because by this point half the items have already moved and cancelling is no longer a
     * coherent operation.
     */
    private void deliverPokemon(List<InFlight> inFlight, List<TradeHistory.PokemonRow> rows,
                                List<InFlight> delivered) {
        for (InFlight held : inFlight) {
            if (!addTo(held.toPlayer(), held.pokemon())) {
                boolean back = addTo(held.fromPlayer(), held.pokemon());
                BetterTrades.LOGGER.error("Trade {}: {} could not be delivered to {} (party and PC full),"
                                + " Pokemon {} {} to the sender {}",
                        tradeId, held.pokemon().getSpecies().getName(),
                        held.toPlayer().getGameProfile().getName(), held.pokemonUuid(),
                        back ? "returned" : "NOT RETURNABLE:", held.fromName());
                Texts.staff(server, "chat.staff.pokemon_stuck", tradeId,
                        held.toPlayer().getGameProfile().getName(), held.pokemonUuid().toString());
                continue;
            }
            CobblemonTrade.resetFriendship(tradeId, held.pokemon());
            rows.add(held.row());
            delivered.add(held);
        }
    }

    private boolean removeFrom(ServerPlayerEntity player, Pokemon pokemon) {
        return Cobblemon.INSTANCE.getStorage().getParty(player).remove(pokemon)
                || Cobblemon.INSTANCE.getStorage().getPC(player).remove(pokemon);
    }

    private boolean addTo(ServerPlayerEntity player, Pokemon pokemon) {
        return Cobblemon.INSTANCE.getStorage().getParty(player).add(pokemon)
                || Cobblemon.INSTANCE.getStorage().getPC(player).add(pokemon);
    }

    /** It only counts: the real delivery is done by escrow, after closing the rows. */
    private void collectItems(Side from, Side to, List<TradeHistory.ItemRow> rows,
                              List<EscrowService.Custody> deliveries) {
        for (OfferEntry entry : from.entries) {
            if (!(entry instanceof OfferEntry.Item item)) continue;
            rows.add(TradeHistory.ItemRow.of(server, from.playerId, item.stack()));
            deliveries.add(new EscrowService.Custody(item.escrowId(), item.stack(),
                    to.playerId, to.playerName));
        }
    }
}
