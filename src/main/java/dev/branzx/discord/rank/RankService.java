package dev.branzx.discord.rank;

import dev.branzx.wallet.api.WalletApi;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.NodeType;
import net.luckperms.api.node.types.InheritanceNode;

import java.time.Duration;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Buys a rank: spends Credit through the central wallet, then grants the
 * LuckPerms group. The two steps are ordered so a player can never end up
 * charged without the rank — the Credit debit is idempotent (keyed by the
 * caller's transaction id), and if the LuckPerms grant fails afterward the
 * Credit is refunded.
 */
public final class RankService {

    public enum Status {
        SUCCESS,
        INSUFFICIENT_OR_DUPLICATE,
        ALREADY_OWNED,
        RANKS_UNAVAILABLE,
        GRANT_FAILED,
    }

    public record Outcome(Status status, String message) {
        static Outcome of(Status status, String message) {
            return new Outcome(status, message);
        }
    }

    private final WalletApi wallet;
    private final LuckPerms luckPerms;
    private final Logger logger;

    public RankService(WalletApi wallet, LuckPerms luckPerms, Logger logger) {
        this.wallet = wallet;
        this.luckPerms = luckPerms;
        this.logger = logger;
    }

    public boolean available() {
        return luckPerms != null;
    }

    /**
     * Attempts a purchase for {@code owner}. {@code transactionId} makes the
     * whole thing idempotent: a repeated confirm click reuses the same id, so
     * the Credit debit cannot run twice. Blocking — call it off the main
     * server thread (JDA event threads already are).
     */
    public Outcome purchase(UUID owner, RankDefinition rank, String transactionId) {
        if (luckPerms == null) {
            return Outcome.of(Status.RANKS_UNAVAILABLE,
                    "🚧 ระบบยศยังไม่พร้อม (ไม่พบ LuckPerms)");
        }

        // A permanent rank the player already holds must not be charged again.
        if (!rank.isTimed() && ownsPermanently(owner, rank.luckPermsGroup())) {
            return Outcome.of(Status.ALREADY_OWNED,
                    "คุณมียศ **" + rank.display() + "** อยู่แล้ว");
        }

        String detail = "{\"rank\":\"" + rank.id() + "\",\"days\":" + rank.durationDays() + "}";
        if (!wallet.adjustCredit(owner, -rank.price(), "RANK_PURCHASE", transactionId, detail)) {
            return Outcome.of(Status.INSUFFICIENT_OR_DUPLICATE,
                    "❌ Credit ไม่พอ หรือรายการนี้ถูกทำไปแล้ว");
        }

        try {
            grantGroup(owner, rank);
        } catch (Exception e) {
            // Grant failed after the charge — return the Credit so the player is
            // never out of pocket without the rank.
            logger.severe("Rank grant failed for " + owner + " (" + rank.id()
                    + "), refunding: " + e.getMessage());
            wallet.adjustCredit(owner, rank.price(), "RANK_REFUND", "REFUND:" + transactionId,
                    "{\"rank\":\"" + rank.id() + "\"}");
            return Outcome.of(Status.GRANT_FAILED,
                    "⚠️ ให้ยศไม่สำเร็จ — คืน Credit ให้แล้ว ลองใหม่หรือแจ้งแอดมิน");
        }

        String suffix = rank.isTimed() ? " (" + rank.durationDays() + " วัน)" : " (ถาวร)";
        return Outcome.of(Status.SUCCESS,
                "✅ ซื้อยศ **" + rank.display() + "**" + suffix + " สำเร็จ! ยศจะมีผลในเกมทันที");
    }

    private boolean ownsPermanently(UUID owner, String group) {
        try {
            User user = luckPerms.getUserManager().loadUser(owner).get();
            return user.getNodes(NodeType.INHERITANCE).stream()
                    .anyMatch(n -> n.getGroupName().equalsIgnoreCase(group) && !n.hasExpiry());
        } catch (Exception e) {
            // If we cannot read membership, fail closed on the ownership check
            // (treat as not owned) so a purchase is still possible.
            logger.warning("Could not read rank membership for " + owner + ": " + e.getMessage());
            return false;
        }
    }

    private void grantGroup(UUID owner, RankDefinition rank) throws Exception {
        InheritanceNode.Builder builder = InheritanceNode.builder(rank.luckPermsGroup());
        if (rank.isTimed()) {
            builder.expiry(Duration.ofDays(rank.durationDays()));
        }
        InheritanceNode node = builder.build();
        // modifyUser loads, mutates and saves — and works for offline players,
        // so a Discord purchase does not require the buyer to be online.
        luckPerms.getUserManager().modifyUser(owner, user -> user.data().add(node)).get();
    }
}
