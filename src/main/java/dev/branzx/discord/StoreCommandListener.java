package dev.branzx.discord;

import dev.branzx.discord.feed.Feed;
import dev.branzx.discord.rank.RankCatalog;
import dev.branzx.discord.rank.RankDefinition;
import dev.branzx.discord.rank.RankService;
import dev.branzx.discord.topup.TopupCatalog;
import dev.branzx.discord.topup.TopupPackage;
import dev.branzx.discord.topup.WebhookServer;
import dev.branzx.wallet.api.WalletApi;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.UserSnowflake;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.interactions.components.ActionRow;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.awt.Color;
import java.util.UUID;

/**
 * Handles the storefront slash commands and the rank confirm buttons. Every
 * currency operation goes through the injected {@link WalletApi}, and rank
 * grants through {@link RankService}; nothing here touches the database.
 *
 * <p>The wallet/rank calls block, so each interaction acknowledges first
 * ({@code deferReply}/{@code deferEdit}) and edits the reply once done.
 */
public final class StoreCommandListener extends ListenerAdapter {

    private static final Color BRAND = new Color(0x2ecc71);

    private final Plugin plugin;
    private final WalletApi wallet;
    private final String guildId;
    private final String linkedRoleId;
    private final RankCatalog catalog;
    private final RankService rankService;
    private final TopupCatalog topupCatalog;
    private volatile WebhookServer webhookServer;
    private volatile Feed feed;

    public StoreCommandListener(Plugin plugin, WalletApi wallet, String guildId,
                                String linkedRoleId, RankCatalog catalog, RankService rankService,
                                TopupCatalog topupCatalog) {
        this.plugin = plugin;
        this.wallet = wallet;
        this.guildId = guildId;
        this.linkedRoleId = linkedRoleId == null || linkedRoleId.isBlank() ? null : linkedRoleId;
        this.catalog = catalog;
        this.rankService = rankService;
        this.topupCatalog = topupCatalog;
    }

    /** Wired after the webhook starts so /topup can register buyers for a DM on settlement. */
    public void setWebhookServer(WebhookServer webhookServer) {
        this.webhookServer = webhookServer;
    }

    /** Wired after JDA connects so a rank purchase can be announced to the feed. */
    public void setFeed(Feed feed) {
        this.feed = feed;
    }

    @Override
    public void onReady(@NotNull ReadyEvent event) {
        Guild guild = event.getJDA().getGuildById(guildId);
        if (guild == null) {
            plugin.getLogger().warning("Bot is not in guild " + guildId + "; commands not registered.");
            return;
        }

        OptionData rankOption = new OptionData(OptionType.STRING, "rank", "ยศที่ต้องการซื้อ", true);
        for (RankDefinition rank : catalog.all()) {
            rankOption.addChoice(rank.display() + " — " + rank.price() + " Credit", rank.id());
        }
        SlashCommandData buyrank = Commands.slash("buyrank", "ซื้อยศด้วย Credit");
        if (!catalog.isEmpty()) {
            buyrank.addOptions(rankOption);
        }

        OptionData packageOption = new OptionData(OptionType.STRING, "package", "แพ็กเกจที่ต้องการเติม", true);
        for (TopupPackage pkg : topupCatalog.all()) {
            packageOption.addChoice(pkg.display() + " — ฿" + pkg.baht() + " → " + pkg.credits() + " Credit", pkg.id());
        }
        SlashCommandData topup = Commands.slash("topup", "เติม Credit ด้วยเงินจริง");
        if (!topupCatalog.isEmpty()) {
            topup.addOptions(packageOption);
        }

        guild.updateCommands().addCommands(
                Commands.slash("link", "เชื่อมบัญชี Discord กับ Minecraft ด้วยรหัสจาก /wallet link ในเกม")
                        .addOption(OptionType.STRING, "code", "รหัส 6 หลักที่ได้จากในเกม", true),
                Commands.slash("balance", "เช็คยอด Coin และ Credit ของคุณ"),
                topup,
                buyrank
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
        String code = event.getOption("code", "", OptionMapping::getAsString).trim();
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
        grantRole(event.getGuild(), event.getUser().getId(), linkedRoleId);
        event.getHook().editOriginal(
                "✅ เชื่อมบัญชีสำเร็จ! เช็คยอดด้วย `/balance` และเติม/ซื้อได้แล้ว").queue();
    }

    private void onBalance(SlashCommandInteractionEvent event) {
        event.deferReply(true).queue();
        UUID owner = wallet.linkedUuid(event.getUser().getId());
        if (owner == null) {
            event.getHook().editOriginal(notLinked()).queue();
            return;
        }
        var embed = new EmbedBuilder()
                .setTitle("💰 ยอดคงเหลือของคุณ")
                .setColor(BRAND)
                .addField("🪙 Coins", String.format("%,d", wallet.coins(owner)), true)
                .addField("💎 Credits", String.format("%,d", wallet.credits(owner)), true)
                .setFooter("MC: " + owner)
                .build();
        event.getHook().editOriginalEmbeds(embed).queue();
    }

    private void onTopup(SlashCommandInteractionEvent event) {
        event.deferReply(true).queue();
        UUID owner = wallet.linkedUuid(event.getUser().getId());
        if (owner == null) {
            event.getHook().editOriginal(notLinked()).queue();
            return;
        }
        if (topupCatalog.isEmpty()) {
            event.getHook().editOriginal("🚧 ยังไม่มีแพ็กเกจเติมเงิน").queue();
            return;
        }
        OptionMapping option = event.getOption("package");
        TopupPackage pkg = option == null ? null : topupCatalog.get(option.getAsString());
        if (pkg == null) {
            event.getHook().editOriginal("❌ เลือกแพ็กเกจไม่ถูกต้อง").queue();
            return;
        }

        String reference = "TOPUP-" + UUID.randomUUID();
        if (!wallet.createTopup(reference, owner, pkg.credits(), pkg.amountSatang(), pkg.id())) {
            event.getHook().editOriginal("⚠️ สร้างรายการไม่สำเร็จ ลองใหม่อีกครั้ง").queue();
            return;
        }
        // Remember the buyer so the webhook can DM them when payment settles.
        if (webhookServer != null) {
            webhookServer.expectSettlement(reference, event.getUser().getId());
        }

        String instructions = plugin.getConfig().getString("topup.instructions", "");
        var embed = new EmbedBuilder()
                .setTitle("💳 เติม Credit — " + pkg.display())
                .setColor(BRAND)
                .addField("ยอดชำระ", "฿" + String.format("%,d", pkg.baht()), true)
                .addField("จะได้รับ", String.format("%,d Credit", pkg.credits()), true)
                .addField("Reference", "`" + reference + "`", false)
                .setFooter("Credit จะเข้าอัตโนมัติเมื่อระบบยืนยันการชำระ")
                .build();
        String body = instructions.isBlank() ? "" : "\n" + instructions;
        event.getHook().editOriginal("📌 สร้างรายการแล้ว โปรดชำระเงินตามยอด" + body)
                .setEmbeds(embed).queue();
    }

    private void onBuyrank(SlashCommandInteractionEvent event) {
        event.deferReply(true).queue();
        UUID owner = wallet.linkedUuid(event.getUser().getId());
        if (owner == null) {
            event.getHook().editOriginal(notLinked()).queue();
            return;
        }
        if (!rankService.available() || catalog.isEmpty()) {
            event.getHook().editOriginal("🚧 ระบบยศยังไม่พร้อมใช้งาน").queue();
            return;
        }
        OptionMapping option = event.getOption("rank");
        RankDefinition rank = option == null ? null : catalog.get(option.getAsString());
        if (rank == null) {
            event.getHook().editOriginal("❌ เลือกยศไม่ถูกต้อง").queue();
            return;
        }

        long credits = wallet.credits(owner);
        String duration = rank.isTimed() ? rank.durationDays() + " วัน" : "ถาวร";
        var embed = new EmbedBuilder()
                .setTitle("🛒 ยืนยันการซื้อยศ")
                .setColor(BRAND)
                .addField("ยศ", rank.display(), true)
                .addField("ระยะเวลา", duration, true)
                .addField("ราคา", String.format("%,d Credit", rank.price()), true)
                .addField("Credit คงเหลือ", String.format("%,d", credits), false)
                .build();

        // The nonce ties the confirm button to one purchase intent: clicking it
        // twice reuses the same transaction id, so the Credit debit is idempotent.
        String nonce = Long.toString(System.currentTimeMillis());
        Button confirm = Button.success("buyrank:confirm:" + rank.id() + ":" + nonce,
                "ยืนยันซื้อ (" + rank.price() + " Credit)");
        Button cancel = Button.secondary("buyrank:cancel", "ยกเลิก");
        event.getHook().editOriginalEmbeds(embed).setComponents(ActionRow.of(confirm, cancel)).queue();
    }

    @Override
    public void onButtonInteraction(@NotNull ButtonInteractionEvent event) {
        String id = event.getComponentId();
        if (!id.startsWith("buyrank:")) {
            return;
        }
        event.deferEdit().queue();

        if (id.equals("buyrank:cancel")) {
            event.getHook().editOriginal("ยกเลิกแล้ว").setEmbeds().setComponents().queue();
            return;
        }

        String[] parts = id.split(":");
        if (parts.length < 4) {
            event.getHook().editOriginal("❌ คำขอไม่ถูกต้อง").setEmbeds().setComponents().queue();
            return;
        }
        String rankId = parts[2];
        String nonce = parts[3];

        UUID owner = wallet.linkedUuid(event.getUser().getId());
        RankDefinition rank = catalog.get(rankId);
        if (owner == null || rank == null) {
            event.getHook().editOriginal(owner == null ? notLinked() : "❌ ยศไม่ถูกต้อง")
                    .setEmbeds().setComponents().queue();
            return;
        }

        String transactionId = "RANK:" + owner + ":" + rankId + ":" + nonce;
        RankService.Outcome outcome = rankService.purchase(owner, rank, transactionId);
        if (outcome.status() == RankService.Status.SUCCESS) {
            if (rank.hasDiscordRole()) {
                grantRole(event.getGuild(), event.getUser().getId(), rank.discordRoleId());
            }
            Feed f = feed;
            if (f != null) {
                f.post("🎉 ยศใหม่!", event.getUser().getAsMention()
                        + " เพิ่งอัปเป็นยศ **" + rank.display() + "** — ยินดีด้วย!");
            }
        }
        event.getHook().editOriginal(outcome.message()).setEmbeds().setComponents().queue();
    }

    /** Best-effort role grant; a failure must not fail the operation it follows. */
    private void grantRole(Guild guild, String userId, String roleId) {
        if (guild == null || roleId == null || roleId.isBlank()) {
            return;
        }
        Role role = guild.getRoleById(roleId);
        if (role == null) {
            return;
        }
        guild.addRoleToMember(UserSnowflake.fromId(userId), role)
                .queue(null, error -> { /* missing permission / role too high — ignore */ });
    }

    private static String notLinked() {
        return "❌ คุณยังไม่ได้เชื่อมบัญชี — พิมพ์ `/wallet link` ในเกม แล้ว `/link <รหัส>` ที่นี่";
    }
}
