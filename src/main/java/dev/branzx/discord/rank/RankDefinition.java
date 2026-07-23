package dev.branzx.discord.rank;

/**
 * One purchasable rank. {@code durationDays} of 0 means the LuckPerms group is
 * granted permanently; a positive value grants it with that expiry (a
 * subscription-style rank). {@code discordRoleId} is optional.
 */
public record RankDefinition(
        String id,
        String display,
        long price,
        String luckPermsGroup,
        String discordRoleId,
        int durationDays) {

    public boolean isTimed() {
        return durationDays > 0;
    }

    public boolean hasDiscordRole() {
        return discordRoleId != null && !discordRoleId.isBlank();
    }
}
