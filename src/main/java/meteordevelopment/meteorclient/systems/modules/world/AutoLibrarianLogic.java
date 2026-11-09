/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.world;

import java.util.List;
import java.util.Set;

/** Pure trade evaluation for {@link AutoLibrarian}. */
public final class AutoLibrarianLogic {
    private AutoLibrarianLogic() {}

    /** One enchanted-book offer: enchantment id, level, max level and emerald price. */
    public record BookOffer(String enchantment, int level, int maxLevel, int price) {}

    /** @return the first acceptable offer, or null. */
    public static BookOffer findMatch(List<BookOffer> offers, Set<String> wanted, int maxPrice, boolean requireMaxLevel) {
        for (BookOffer o : offers) {
            if (!wanted.contains(o.enchantment())) continue;
            if (o.price() > maxPrice) continue;
            if (requireMaxLevel && o.level() < o.maxLevel()) continue;
            return o;
        }
        return null;
    }
}
