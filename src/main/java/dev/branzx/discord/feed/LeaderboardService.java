package dev.branzx.discord.feed;

import dev.branzx.wallet.api.LeaderEntry;
import dev.branzx.wallet.api.WalletApi;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.awt.Color;
import java.time.Instant;
import java.util.List;

/**
 * Posts a Coin leaderboard to a channel and refreshes it on a timer. It updates
 * one message in place instead of spamming: the message id is cached, and on a
 * fresh start it re-adopts its own most recent leaderboard message in the
 * channel so a restart does not leave a duplicate.
 */
public final class LeaderboardService {

    private static final Color GOLD = new Color(0xffd700);
    private static final String[] MEDALS = {"🥇", "🥈", "🥉"};

    private final Plugin plugin;
    private final JDA jda;
    private final WalletApi wallet;
    private final String channelId;
    private final int topN;
    private volatile String messageId;

    public LeaderboardService(Plugin plugin, JDA jda, WalletApi wallet, String channelId, int topN) {
        this.plugin = plugin;
        this.jda = jda;
        this.wallet = wallet;
        this.channelId = channelId == null || channelId.isBlank() ? null : channelId;
        this.topN = Math.max(1, Math.min(25, topN));
    }

    public void start(int refreshMinutes) {
        if (channelId == null) {
            return;
        }
        long period = Math.max(1, refreshMinutes) * 60L * 20L; // ticks
        // First render ~20s in, so JDA has time to connect.
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::refresh, 20L * 20L, period);
    }

    private void refresh() {
        if (jda == null) {
            return;
        }
        TextChannel channel = jda.getTextChannelById(channelId);
        if (channel == null) {
            return;
        }
        MessageEmbed embed = build(wallet.topCoins(topN));

        String id = messageId;
        if (id != null) {
            channel.editMessageEmbedsById(id, embed).queue(null, err -> sendNew(channel, embed));
            return;
        }
        // No cached id (fresh start): re-adopt our own last leaderboard message.
        channel.getHistory().retrievePast(15).queue(messages -> {
            var mine = messages.stream()
                    .filter(m -> jda.getSelfUser().getId().equals(m.getAuthor().getId())
                            && !m.getEmbeds().isEmpty())
                    .findFirst();
            if (mine.isPresent()) {
                messageId = mine.get().getId();
                channel.editMessageEmbedsById(messageId, embed).queue(null, e -> sendNew(channel, embed));
            } else {
                sendNew(channel, embed);
            }
        }, err -> sendNew(channel, embed));
    }

    private void sendNew(TextChannel channel, MessageEmbed embed) {
        channel.sendMessageEmbeds(embed).queue(m -> messageId = m.getId(), e -> { });
    }

    private MessageEmbed build(List<LeaderEntry> top) {
        StringBuilder sb = new StringBuilder();
        if (top.isEmpty()) {
            sb.append("ยังไม่มีข้อมูลผู้เล่น");
        }
        int rank = 1;
        for (LeaderEntry entry : top) {
            String prefix = rank <= 3 ? MEDALS[rank - 1] : "**" + rank + ".**";
            String name = entry.name() == null ? "?" : entry.name();
            sb.append(prefix).append(" ").append(name)
                    .append(" — ").append(String.format("%,d", entry.coins())).append(" 🪙\n");
            rank++;
        }
        return new EmbedBuilder()
                .setTitle("🏆 อันดับผู้เล่น (Coins)")
                .setDescription(sb.toString())
                .setColor(GOLD)
                .setTimestamp(Instant.now())
                .setFooter("อัปเดตล่าสุด")
                .build();
    }
}
