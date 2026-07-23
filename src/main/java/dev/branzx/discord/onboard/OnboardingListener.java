package dev.branzx.discord.onboard;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.guild.member.GuildMemberJoinEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.components.ActionRow;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

/**
 * Onboarding: welcomes new members and lets them self-assign roles.
 *
 * <p>The role panel and its buttons work purely through interactions, so they
 * need no privileged intent. The join welcome does need the Server Members
 * Intent; it is enabled separately (see {@code onboard.welcome.enabled}) so a
 * deployment that has not turned that on in the Developer Portal is unaffected.
 */
public final class OnboardingListener extends ListenerAdapter {

    public record SelfRole(String label, String roleId) {
    }

    private static final Color BRAND = new Color(0x2ecc71);

    private final Plugin plugin;
    private final boolean welcomeEnabled;
    private final String welcomeChannelId;
    private final boolean dm;
    private final List<SelfRole> selfRoles;

    public OnboardingListener(Plugin plugin, boolean welcomeEnabled, String welcomeChannelId,
                              boolean dm, List<SelfRole> selfRoles) {
        this.plugin = plugin;
        this.welcomeEnabled = welcomeEnabled;
        this.welcomeChannelId = welcomeChannelId == null || welcomeChannelId.isBlank()
                ? null : welcomeChannelId;
        this.dm = dm;
        this.selfRoles = selfRoles;
    }

    @Override
    public void onGuildMemberJoin(@NotNull GuildMemberJoinEvent event) {
        if (!welcomeEnabled) {
            return;
        }
        Member member = event.getMember();
        String server = event.getGuild().getName();
        if (welcomeChannelId != null) {
            TextChannel channel = event.getGuild().getTextChannelById(welcomeChannelId);
            if (channel != null) {
                channel.sendMessage("🎉 ยินดีต้อนรับ " + member.getAsMention() + " สู่ **" + server
                        + "**! เริ่มด้วย `/link` เพื่อเชื่อมบัญชี แล้วเลือก role ของคุณด้วย panel ในเซิร์ฟ")
                        .queue(null, e -> { });
            }
        }
        if (dm) {
            member.getUser().openPrivateChannel().queue(pc ->
                    pc.sendMessage(firstSteps(server)).queue(null, e -> { }), e -> { });
        }
    }

    private String firstSteps(String server) {
        return "**ยินดีต้อนรับสู่ " + server + "! 🎉**\n\n"
                + "เริ่มต้น 3 ขั้น:\n"
                + "1️⃣ ในเกมพิมพ์ `/wallet link` → ได้รหัส 6 หลัก\n"
                + "2️⃣ ในเซิร์ฟพิมพ์ `/link <รหัส>` เพื่อเชื่อมบัญชี\n"
                + "3️⃣ เช็คยอดด้วย `/balance` · เติมด้วย `/topup` · ซื้อยศด้วย `/buyrank`\n\n"
                + "> ⚠️ ทีมงานจะไม่ทัก DM ขอรหัสผ่าน/OTP เด็ดขาด";
    }

    // ---- self-role panel ----

    public boolean hasSelfRoles() {
        return !selfRoles.isEmpty();
    }

    /** Posts the role-selection panel to the channel the command was used in. */
    public void postPanel(SlashCommandInteractionEvent event) {
        if (selfRoles.isEmpty()) {
            event.reply("ยังไม่ได้ตั้งค่า self-roles ใน config").setEphemeral(true).queue();
            return;
        }
        var embed = new EmbedBuilder()
                .setTitle("🎭 เลือก Role ของคุณ")
                .setDescription("กดปุ่มด้านล่างเพื่อเปิด/ปิด role")
                .setColor(BRAND)
                .build();
        List<Button> buttons = new ArrayList<>();
        for (SelfRole role : selfRoles) {
            buttons.add(Button.secondary("role:" + role.roleId(), role.label()));
        }
        event.getChannel().sendMessageEmbeds(embed).setComponents(ActionRow.of(buttons)).queue();
        event.reply("✅ โพสต์ panel แล้ว").setEphemeral(true).queue();
    }

    @Override
    public void onButtonInteraction(@NotNull ButtonInteractionEvent event) {
        String id = event.getComponentId();
        if (!id.startsWith("role:")) {
            return;
        }
        String roleId = id.substring("role:".length());
        if (selfRoles.stream().noneMatch(r -> r.roleId().equals(roleId))) {
            event.reply("❌ role นี้ไม่เปิดให้เลือกเอง").setEphemeral(true).queue();
            return;
        }
        Guild guild = event.getGuild();
        Member member = event.getMember();
        Role role = guild == null ? null : guild.getRoleById(roleId);
        if (role == null || member == null) {
            event.reply("❌ ไม่พบ role").setEphemeral(true).queue();
            return;
        }
        if (member.getRoles().contains(role)) {
            guild.removeRoleFromMember(member, role).queue();
            event.reply("➖ เอา **" + role.getName() + "** ออกแล้ว").setEphemeral(true).queue();
        } else {
            guild.addRoleToMember(member, role).queue();
            event.reply("➕ เพิ่ม **" + role.getName() + "** แล้ว").setEphemeral(true).queue();
        }
    }
}
