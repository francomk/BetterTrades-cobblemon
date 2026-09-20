package com.bettertrades.trade;

import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.api.moves.Move;
import com.cobblemon.mod.common.api.pokemon.stats.Stat;
import com.cobblemon.mod.common.api.pokemon.stats.Stats;
import com.cobblemon.mod.common.pokemon.Pokemon;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;

/**
 * An offered Pokemon is never moved or locked through Cobblemon's own paths: it is fingerprinted
 * here and checked again at commit. If the fingerprint does not match, the trade is cancelled.
 *
 * HP, status and friendship stay out of the fingerprint: they change on their own and would
 * cancel valid trades.
 */
public record PokemonFingerprint(UUID pokemonUuid, UUID owner, Location location, String hash) {

    public enum Location { PARTY, PC }

    private static final Stat[] STATS = {
            Stats.HP, Stats.ATTACK, Stats.DEFENCE,
            Stats.SPECIAL_ATTACK, Stats.SPECIAL_DEFENCE, Stats.SPEED
    };

    public static PokemonFingerprint of(ServerPlayerEntity owner, Pokemon pokemon, Location location) {
        return new PokemonFingerprint(pokemon.getUuid(), owner.getUuid(), location, hash(pokemon));
    }

    /** Looks in the party first, then in the PC: null when the Pokemon is no longer where it should be. */
    public static Pokemon find(ServerPlayerEntity owner, UUID pokemonUuid) {
        Pokemon inParty = Cobblemon.INSTANCE.getStorage().getParty(owner).get(pokemonUuid);
        if (inParty != null) return inParty;
        return Cobblemon.INSTANCE.getStorage().getPC(owner).get(pokemonUuid);
    }

    public boolean stillMatches(ServerPlayerEntity currentOwner) {
        if (!currentOwner.getUuid().equals(owner)) return false;
        Pokemon current = find(currentOwner, pokemonUuid);
        return current != null && hash(current).equals(hash);
    }

    public static String hash(Pokemon pokemon) {
        StringBuilder canonical = new StringBuilder(256);
        canonical.append(pokemon.getUuid()).append('|')
                .append(pokemon.getSpecies().getResourceIdentifier()).append('|')
                .append(pokemon.getForm().getName()).append('|')
                .append(pokemon.getLevel()).append('|')
                .append(pokemon.getShiny()).append('|')
                .append(pokemon.getGender().name()).append('|')
                .append(pokemon.getNickname() == null ? "" : pokemon.getNickname().getString()).append('|')
                .append(pokemon.getNature().getName()).append('|')
                .append(pokemon.getAbility().getName()).append('|')
                .append(pokemon.getCaughtBall().getName()).append('|')
                .append(pokemon.getOriginalTrainer() == null ? "" : pokemon.getOriginalTrainer()).append('|')
                .append(pokemon.getTradeable()).append('|');

        for (Stat stat : STATS) {
            canonical.append(stat.getIdentifier()).append(':')
                    .append(value(pokemon.getIvs().get(stat))).append(':')
                    .append(value(pokemon.getEvs().get(stat))).append(',');
        }
        canonical.append('|');

        for (String move : moves(pokemon)) canonical.append(move).append(',');
        canonical.append('|');

        for (String aspect : new TreeSet<>(pokemon.getAspects())) canonical.append(aspect).append(',');
        canonical.append('|');

        ItemStack held = pokemon.getHeldItem$common();
        canonical.append(held.isEmpty() ? "" : held.getItem() + "x" + held.getCount() + held.getComponents());

        return sha256(canonical.toString());
    }

    public static List<String> moves(Pokemon pokemon) {
        List<String> names = new ArrayList<>(4);
        for (Move move : pokemon.getMoveSet()) {
            names.add(move.getName());
        }
        return names;
    }

    public static Map<String, int[]> stats(Pokemon pokemon) {
        Map<String, int[]> out = new java.util.LinkedHashMap<>();
        for (Stat stat : STATS) {
            out.put(stat.getIdentifier().toString(),
                    new int[]{value(pokemon.getIvs().get(stat)), value(pokemon.getEvs().get(stat))});
        }
        return out;
    }

    private static int value(Integer boxed) {
        return boxed == null ? 0 : boxed;
    }

    private static String sha256(String canonical) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
