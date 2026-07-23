package dev.branzx.discord.topup;

/**
 * A top-up product: pay {@code baht} to receive {@code credits}. Amounts are
 * whole baht; the wallet stores the money side in satang.
 */
public record TopupPackage(String id, String display, int baht, long credits) {

    public long amountSatang() {
        return baht * 100L;
    }
}
