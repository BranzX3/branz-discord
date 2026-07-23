package dev.branzx.discord.feed;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;

import java.awt.Color;

/**
 * Posts community moments to the public feed channel. A blank channel id simply
 * disables the feed, so nothing here fails when it is not configured.
 */
public final class Feed {

    private static final Color BRAND = new Color(0x2ecc71);

    private final JDA jda;
    private final String channelId;

    public Feed(JDA jda, String channelId) {
        this.jda = jda;
        this.channelId = channelId == null || channelId.isBlank() ? null : channelId;
    }

    public boolean enabled() {
        return channelId != null;
    }

    /** Fire-and-forget embed post; safe to call from any thread. */
    public void post(String title, String body) {
        if (channelId == null || jda == null) {
            return;
        }
        TextChannel channel = jda.getTextChannelById(channelId);
        if (channel == null) {
            return;
        }
        channel.sendMessageEmbeds(new EmbedBuilder()
                .setTitle(title)
                .setDescription(body)
                .setColor(BRAND)
                .build()).queue(null, error -> { /* channel gone / no permission — ignore */ });
    }
}
