package dev.branzx.discord.ticket;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.components.ActionRow;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import org.jetbrains.annotations.NotNull;

import java.awt.Color;
import java.util.concurrent.TimeUnit;

/**
 * Support tickets. A panel button opens a **private thread** in the support
 * channel where the member and staff can talk, and a close button archives it.
 * Everything is Discord-native — no game data or database is involved.
 *
 * <p>The bot needs Create Private Threads + Manage Threads (and Send Messages in
 * Threads) in the support channel.
 */
public final class TicketListener extends ListenerAdapter {

    private static final Color BRAND = new Color(0x2ecc71);

    private final String staffRoleId;

    public TicketListener(String staffRoleId) {
        this.staffRoleId = staffRoleId == null || staffRoleId.isBlank() ? null : staffRoleId;
    }

    /** Posts the ticket panel in the channel the command was used in. */
    public void postPanel(SlashCommandInteractionEvent event) {
        var embed = new EmbedBuilder()
                .setTitle("🎫 ต้องการความช่วยเหลือ?")
                .setDescription("กดปุ่มด้านล่างเพื่อเปิด ticket ส่วนตัวกับทีมงาน\n"
                        + "(เช่น ปัญหาการเติมเงิน, การซื้อยศ, บัญชี)")
                .setColor(BRAND)
                .build();
        event.getChannel().sendMessageEmbeds(embed)
                .setComponents(ActionRow.of(Button.primary("ticket:open", "🎫 เปิด Ticket")))
                .queue();
        event.reply("✅ โพสต์ ticket panel แล้ว").setEphemeral(true).queue();
    }

    @Override
    public void onButtonInteraction(@NotNull ButtonInteractionEvent event) {
        switch (event.getComponentId()) {
            case "ticket:open" -> openTicket(event);
            case "ticket:close" -> closeTicket(event);
            default -> { /* not ours */ }
        }
    }

    private void openTicket(ButtonInteractionEvent event) {
        if (event.getChannelType().isThread()) {
            event.reply("อยู่ใน ticket อยู่แล้ว").setEphemeral(true).queue();
            return;
        }
        TextChannel channel = event.getChannel() instanceof TextChannel tc ? tc : null;
        if (channel == null) {
            event.reply("❌ เปิด ticket ได้เฉพาะในห้องข้อความ").setEphemeral(true).queue();
            return;
        }
        String opener = event.getUser().getName();
        String mention = staffRoleId == null ? "" : "<@&" + staffRoleId + "> ";
        channel.createThreadChannel("ticket-" + opener, true).queue(thread -> {
            thread.addThreadMember(event.getUser()).queue();
            thread.sendMessage(mention + event.getUser().getAsMention()
                            + " เปิด ticket แล้ว — อธิบายปัญหาได้เลยครับ ทีมงานจะมาช่วย")
                    .setComponents(ActionRow.of(Button.danger("ticket:close", "🔒 ปิด Ticket")))
                    .queue();
            event.reply("✅ เปิด ticket แล้ว: " + thread.getAsMention()).setEphemeral(true).queue();
        }, error -> event.reply("❌ เปิด ticket ไม่ได้ — ตรวจสิทธิ์บอท (Create Private Threads)")
                .setEphemeral(true).queue());
    }

    private void closeTicket(ButtonInteractionEvent event) {
        if (!event.getChannelType().isThread()) {
            event.reply("ปุ่มนี้ใช้ได้ใน ticket เท่านั้น").setEphemeral(true).queue();
            return;
        }
        ThreadChannel thread = event.getChannel().asThreadChannel();
        event.reply("🔒 ปิด ticket แล้ว — ขอบคุณครับ").queue();
        // Give the reply a moment to land before the thread locks.
        thread.getManager().setArchived(true).setLocked(true)
                .queueAfter(2, TimeUnit.SECONDS, null, err -> { });
    }
}
