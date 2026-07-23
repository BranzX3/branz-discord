package dev.branzx.discord;

import dev.branzx.wallet.api.WalletApi;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.UserSnowflake;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.awt.Color;
import java.util.UUID;

/**
 * Handles the storefront slash commands. Every currency operation goes through
 * the injected {@link WalletApi}; nothing here touches the database directly.
 *
 * <p>The wallet calls block on a database, so each command acknowledges first
 * ({@code deferReply}) and then edits the reply once the work is done — well
 * within Discord's 3-second window.
 */
public final class StoreCommandListener extends ListenerAdapter {

    private final Plugin plugin;
    private final WalletApi wallet;
    private final String guildId;
    private final String linkedRoleId;

    public StoreCommandListener(Plugin plugin, WalletApi wallet, String guildId, String linkedRoleId) {
        this.plugin = plugin;
        this.wallet = wallet;
        this.guildId = guildId;
        this.linkedRoleId = linkedRoleId == null || linkedRoleId.isBlank() ? null : linkedRoleId;
    }

    @Override
    public void onReady(@NotNull ReadyEvent event) {
        Guild guild = event.getJDA().getGuildById(guildId);
        if (guild == null) {
            plugin.getLogger().warning("Bot is not in guild " + guildId + "; commands not registered.");
            return;
        }
        guild.updateCommands().addCommands(
                Commands.slash("link", "เชื่อมบัญชี Discord กับ Minecraft ด้วยรหัสจาก /wallet link ในเกม")
                        .addOption(OptionType.STRING, "code", "รหัส 6 หลักที่ได้จากในเกม", true),
                Commands.slash("balance", "เช็คยอด Coin และ Credit ของคุณ"),
                Commands.slash("topup", "เติม Credit ด้วยเงินจริง (เร็ว ๆ นี้)"),
                Commands.slash("buyrank", "ซื้อยศด้วย Credit (เร็ว ๆ นี้)")
        ).queue();
        plugin.getLogger().info("Registered storefront commands in guild " + guild.getName() + ".");
    }

    @Override
    public void onSlashCommandInteraction(@NotNull SlashCommandInteractionEvent event) {
        switch (event.getName()) {
            case "link" -> onLink(event);
            case "balance" -> onBalance(event);
            case "topup" -> onTopup(event);
            case "buyrank" -> onBuyrank(event);
            default -> { /* unknown command, ignore */ }
        }
    }

    private void onLink(SlashCommandInteractionEvent event) {
        String code = event.getOption("code", "", net.dv8tion.jda.api.interactions.commands.OptionMapping::getAsString).trim();
        if (!code.matches("\\d{6}")) {
            event.reply("❌ รหัสต้องเป็นตัวเลข 6 หลัก").setEphemeral(true).queue();
            return;
        }
        event.deferReply(true).queue();
        UUID owner = wallet.redeemLinkCode(code, event.getUser().getId());
        if (owner == null) {
            event.getHook().editOriginal(
                    "❌ รหัสไม่ถูกต้องหรือหมดอายุแล้ว — พิมพ์ `/wallet link` ในเกมเพื่อขอรหัสใหม่").queue();
            return;
        }
        grantLinkedRole(event);
        event.getHook().editOriginal(
                "✅ เชื่อมบัญชีสำเร็จ! เช็คยอดด้วย `/balance` และเติม/ซื้อได้แล้ว").queue();
    }

    private void onBalance(SlashCommandInteractionEvent event) {
        event.deferReply(true).queue();
        UUID owner = wallet.linkedUuid(event.getUser().getId());
        if (owner == null) {
            event.getHook().editOriginal(
                    "❌ คุณยังไม่ได้เชื่อมบัญชี — พิมพ์ `/wallet link` ในเกม แล้ว `/link <รหัส>` ที่นี่").queue();
            return;
        }
        long coins = wallet.coins(owner);
        long credits = wallet.credits(owner);
        var embed = new EmbedBuilder()
                .setTitle("💰 ยอดคงเหลือของคุณ")
                .setColor(new Color(0x2ecc71))
                .addField("🪙 Coins", String.format("%,d", coins), true)
                .addField("💎 Credits", String.format("%,d", credits), true)
                .setFooter("MC: " + owner)
                .build();
        event.getHook().editOriginalEmbeds(embed).queue();
    }

    private void onTopup(SlashCommandInteractionEvent event) {
        event.deferReply(true).queue();
        if (wallet.linkedUuid(event.getUser().getId()) == null) {
            event.getHook().editOriginal("❌ กรุณาเชื่อมบัญชีก่อนด้วย `/link`").queue();
            return;
        }
        event.getHook().editOriginal(
                "🚧 ระบบเติมเงินกำลังจะเปิด — ยังไม่ได้เชื่อม payment gateway").queue();
    }

    private void onBuyrank(SlashCommandInteractionEvent event) {
        event.deferReply(true).queue();
        if (wallet.linkedUuid(event.getUser().getId()) == null) {
            event.getHook().editOriginal("❌ กรุณาเชื่อมบัญชีก่อนด้วย `/link`").queue();
            return;
        }
        event.getHook().editOriginal(
                "🚧 ระบบยศกำลังพัฒนา — จะเปิดขายเมื่อเชื่อม LuckPerms เสร็จ").queue();
    }

    /** Best-effort Linked role grant; a failure must not fail the link itself. */
    private void grantLinkedRole(SlashCommandInteractionEvent event) {
        if (linkedRoleId == null || event.getGuild() == null) return;
        Role role = event.getGuild().getRoleById(linkedRoleId);
        if (role == null) return;
        event.getGuild()
                .addRoleToMember(UserSnowflake.fromId(event.getUser().getId()), role)
                .queue(null, error -> { /* missing permission / role too high — ignore */ });
    }
}
