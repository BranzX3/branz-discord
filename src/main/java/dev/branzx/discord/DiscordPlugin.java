package dev.branzx.discord;

import dev.branzx.discord.feed.Feed;
import dev.branzx.discord.feed.LeaderboardService;
import dev.branzx.discord.feed.NotificationListener;
import dev.branzx.discord.onboard.OnboardingListener;
import dev.branzx.discord.rank.RankCatalog;
import dev.branzx.discord.rank.RankService;
import dev.branzx.discord.topup.TopupCatalog;
import dev.branzx.discord.topup.WebhookServer;
import dev.branzx.wallet.api.WalletApi;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
    private WebhookServer webhookServer;

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

        RankCatalog catalog = RankCatalog.from(getConfig().getConfigurationSection("ranks"));
        RankService rankService = new RankService(wallet, resolveLuckPerms(), getLogger());
        TopupCatalog topupCatalog = TopupCatalog.from(
                getConfig().getConfigurationSection("topup.packages"));

        try {
            // createLight drops member/message caches the storefront never
            // needs. build() connects asynchronously, so the server keeps
            // booting while the gateway comes up.
            StoreCommandListener listener = new StoreCommandListener(
                    this, wallet, guildId, linkedRoleId, catalog, rankService, topupCatalog);

            OnboardingListener onboarding = buildOnboarding();
            listener.setOnboarding(onboarding);

            JDABuilder builder = JDABuilder.createLight(token);
            // The join-welcome needs the privileged Server Members Intent; only
            // request it when the feature is on, so a portal without it still boots.
            if (getConfig().getBoolean("onboard.welcome.enabled", false)) {
                builder.enableIntents(GatewayIntent.GUILD_MEMBERS);
            }
            jda = builder.addEventListeners(listener, onboarding).build();
            startWebhook(wallet, listener);

            // Community feed: post game moments here and broadcast rank buys.
            Feed feed = new Feed(jda, getConfig().getString("feed.channel-id", ""));
            listener.setFeed(feed);
            getServer().getPluginManager().registerEvents(
                    new NotificationListener(this, jda, wallet, feed), this);

            if (getConfig().getBoolean("leaderboard.enabled", false)) {
                new LeaderboardService(this, jda, wallet,
                        getConfig().getString("leaderboard.channel-id", ""),
                        getConfig().getInt("leaderboard.top-n", 10))
                        .start(getConfig().getInt("leaderboard.refresh-minutes", 60));
            }

            getLogger().info("Discord storefront front-end starting...");
        } catch (Exception e) {
            getLogger().severe("Failed to start the Discord bot: " + e.getMessage());
        }
    }

    @Override
    public void onDisable() {
        if (webhookServer != null) {
            webhookServer.stop();
        }
        if (jda != null) {
            jda.shutdownNow();
        }
    }

    /** Builds the onboarding listener (welcome + self-role panel) from config. */
    private OnboardingListener buildOnboarding() {
        List<OnboardingListener.SelfRole> selfRoles = new ArrayList<>();
        for (Map<?, ?> entry : getConfig().getMapList("onboard.self-roles")) {
            Object label = entry.get("label");
            Object roleId = entry.get("role-id");
            if (label != null && roleId != null) {
                selfRoles.add(new OnboardingListener.SelfRole(
                        String.valueOf(label), String.valueOf(roleId)));
            }
        }
        return new OnboardingListener(this,
                getConfig().getBoolean("onboard.welcome.enabled", false),
                getConfig().getString("onboard.welcome.channel-id", ""),
                getConfig().getBoolean("onboard.welcome.dm", true),
                selfRoles);
    }

    /** Starts the top-up settlement webhook if enabled, and wires it to /topup. */
    private void startWebhook(WalletApi wallet, StoreCommandListener listener) {
        if (!getConfig().getBoolean("topup.webhook.enabled", false)) {
            return;
        }
        int port = getConfig().getInt("topup.webhook.port", 8787);
        String path = getConfig().getString("topup.webhook.path", "/webhook/topup");
        String secret = getConfig().getString("topup.webhook.secret", "");
        if (secret.isBlank() || secret.equals("change-me")) {
            getLogger().warning("topup.webhook.secret is unset/default; the webhook is disabled "
                    + "until you set a private secret.");
            return;
        }
        try {
            webhookServer = new WebhookServer(port, path, secret, wallet, jda, getLogger());
            webhookServer.start();
            listener.setWebhookServer(webhookServer);
        } catch (Exception e) {
            getLogger().severe("Failed to start the top-up webhook: " + e.getMessage());
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

    /**
     * Resolves LuckPerms if installed. It is a soft dependency: without it rank
     * sales are simply unavailable, so a null return is handled gracefully.
     */
    private LuckPerms resolveLuckPerms() {
        if (getServer().getPluginManager().getPlugin("LuckPerms") == null) {
            getLogger().warning("LuckPerms not found; /buyrank will report ranks as unavailable.");
            return null;
        }
        try {
            return LuckPermsProvider.get();
        } catch (Throwable t) {
            getLogger().warning("Could not resolve the LuckPerms API: " + t);
            return null;
        }
    }
}
