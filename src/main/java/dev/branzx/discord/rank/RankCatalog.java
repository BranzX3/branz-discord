package dev.branzx.discord.rank;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The rank products, loaded from the {@code ranks} section of config.yml.
 * Insertion order is preserved so the Discord command lists them the way they
 * are written in the config.
 */
public final class RankCatalog {

    private final Map<String, RankDefinition> byId = new LinkedHashMap<>();

    /** Builds a catalog from a config section; an absent/empty section is fine. */
    public static RankCatalog from(ConfigurationSection section) {
        RankCatalog catalog = new RankCatalog();
        if (section == null) {
            return catalog;
        }
        for (String id : section.getKeys(false)) {
            ConfigurationSection r = section.getConfigurationSection(id);
            if (r == null) {
                continue;
            }
            catalog.byId.put(id, new RankDefinition(
                    id,
                    r.getString("display", id),
                    r.getLong("price", 0),
                    r.getString("luckperms-group", id),
                    r.getString("discord-role-id", ""),
                    r.getInt("duration-days", 0)));
        }
        return catalog;
    }

    public RankDefinition get(String id) {
        return byId.get(id);
    }

    public List<RankDefinition> all() {
        return new ArrayList<>(byId.values());
    }

    public boolean isEmpty() {
        return byId.isEmpty();
    }
}
