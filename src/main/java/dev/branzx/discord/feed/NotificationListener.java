package dev.branzx.discord.feed;

import dev.branzx.wallet.api.WalletApi;
import dev.branzx.wallet.event.CommunityNotification;
import net.dv8tion.jda.api.JDA;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

/**
 * Turns game-side {@link CommunityNotification}s into Discord posts. The event
 * is fired by Idle (or any plugin) and carries its own routing — broadcast to
 * the feed, DM the player, or both. Idle never depends on this plugin; both only
 * know the shared wallet event.
 */
public final class NotificationListener implements Listener {

    private final Plugin plugin;
    private final JDA jda;
    private final WalletApi wallet;
    private final Feed feed;

    public NotificationListener(Plugin plugin, JDA jda, WalletApi wallet, Feed feed) {
        this.plugin = plugin;
        this.jda = jda;
        this.wallet = wallet;
        this.feed = feed;
    }

    @EventHandler
    public void onNotification(CommunityNotification event) {
        if (event.broadcast()) {
            feed.post(event.title(), event.message());
        }
        if (event.dm() && event.player() != null) {
            // The link lookup hits the database, so resolve it off the main
            // thread; the JDA send is async on top of that.
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                String discordId = wallet.discordIdFor(event.player());
                if (discordId == null || jda == null) {
                    return;
                }
                jda.retrieveUserById(discordId).queue(user ->
                                user.openPrivateChannel().queue(channel ->
                                        channel.sendMessage("**" + event.title() + "**\n" + event.message())
                                                .queue(null, e -> { })),
                        e -> { /* unreachable — ignore */ });
            });
        }
    }
}
