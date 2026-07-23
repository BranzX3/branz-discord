package dev.branzx.discord.topup;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The top-up products, loaded from the {@code topup.packages} config section. */
public final class TopupCatalog {

    private final Map<String, TopupPackage> byId = new LinkedHashMap<>();

    public static TopupCatalog from(ConfigurationSection section) {
        TopupCatalog catalog = new TopupCatalog();
        if (section == null) {
            return catalog;
        }
        for (String id : section.getKeys(false)) {
            ConfigurationSection p = section.getConfigurationSection(id);
            if (p == null) {
                continue;
            }
            catalog.byId.put(id, new TopupPackage(
                    id,
                    p.getString("display", id),
                    p.getInt("baht", 0),
                    p.getLong("credits", 0)));
        }
        return catalog;
    }

    public TopupPackage get(String id) {
        return byId.get(id);
    }

    public List<TopupPackage> all() {
        return new ArrayList<>(byId.values());
    }

    public boolean isEmpty() {
        return byId.isEmpty();
    }
}
