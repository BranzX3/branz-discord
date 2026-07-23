package dev.branzx.discord;

import dev.branzx.wallet.api.WalletApi;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * The Discord storefront front-end. It runs inside the game server and reaches
 * the shop through the central {@link WalletApi}, so there is no duplicated
 * currency logic — the bot calls the very same service the game does.
 *
 * <p>On a multi-backend network only one server should enable this; two
 * gateways on one token would double every reply.
 */
public final class DiscordPlugin extends JavaPlugin {

    private JDA jda;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        if (!getConfig().getBoolean("discord.enabled", true)) {
            getLogger().info("Discord front-end disabled by config (discord.enabled).");
            return;
        }

        String token = getConfig().getString("discord.token", "").trim();
        String guildId = getConfig().getString("discord.guild-id", "").trim();
        if (token.isEmpty() || guildId.isEmpty()) {
            getLogger().warning("discord.token / discord.guild-id are not set; the bot will not start.");
            return;
        }

        WalletApi wallet = resolveWalletApi();
        if (wallet == null) {
            getLogger().severe("BranzWallet service is unavailable; the bot will not start.");
            return;
        }

        String linkedRoleId = getConfig().getString("discord.linked-role-id", "").trim();
        try {
            // createLight drops member/message caches the storefront never
            // needs. build() connects asynchronously, so the server keeps
            // booting while the gateway comes up.
            jda = JDABuilder.createLight(token)
                    .addEventListeners(new StoreCommandListener(this, wallet, guildId, linkedRoleId))
                    .build();
            getLogger().info("Discord storefront front-end starting...");
        } catch (Exception e) {
            getLogger().severe("Failed to start the Discord bot: " + e.getMessage());
        }
    }

    @Override
    public void onDisable() {
        if (jda != null) {
            jda.shutdownNow();
        }
    }

    /**
     * Resolves the central wallet service. BranzDiscord depends on BranzWallet,
     * so it is normally present; Throwable also covers a present-but-broken
     * wallet jar so a bad hook cannot crash enable.
     */
    private WalletApi resolveWalletApi() {
        try {
            var registration = getServer().getServicesManager().getRegistration(WalletApi.class);
            return registration == null ? null : registration.getProvider();
        } catch (Throwable t) {
            getLogger().severe("Could not resolve the BranzWallet service: " + t);
            return null;
        }
    }
}
