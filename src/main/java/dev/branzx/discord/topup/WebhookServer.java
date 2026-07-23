package dev.branzx.discord.topup;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.branzx.wallet.api.TopupInfo;
import dev.branzx.wallet.api.TopupSettlement;
import dev.branzx.wallet.api.WalletApi;
import net.dv8tion.jda.api.JDA;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * A tiny HTTP endpoint that settles top-ups when a payment is confirmed. It
 * accepts a form-encoded POST, verifies an {@code X-Signature} header
 * (hex HMAC-SHA256 of the raw body with a shared secret), and, on a success
 * status, calls {@link WalletApi#settleTopup} — which is idempotent, so a
 * gateway retrying the callback is harmless.
 *
 * <p>The generic HMAC contract works immediately for an admin approval tool or
 * any gateway you configure to sign this way. Point GB PrimePay's background
 * URL here and map its fields (referenceNo → reference, resultCode → status).
 */
public final class WebhookServer {

    private static final Map<String, Boolean> SUCCESS = Map.of(
            "paid", true, "success", true, "00", true);

    private final int port;
    private final String path;
    private final String secret;
    private final WalletApi wallet;
    private final JDA jda;
    private final Logger logger;
    // reference -> Discord user id, so the buyer can be DM'd on settlement.
    // Best-effort and in-memory: a restart forgets pending notifications.
    private final Map<String, String> pending = new ConcurrentHashMap<>();

    private HttpServer server;

    public WebhookServer(int port, String path, String secret, WalletApi wallet, JDA jda, Logger logger) {
        this.port = port;
        this.path = path;
        this.secret = secret;
        this.wallet = wallet;
        this.jda = jda;
        this.logger = logger;
    }

    /** Remembers which Discord user started an order so we can DM them later. */
    public void expectSettlement(String reference, String discordUserId) {
        pending.put(reference, discordUserId);
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext(path, this::handle);
        server.setExecutor(null);
        server.start();
        logger.info("Top-up webhook listening on port " + port + " " + path);
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                respond(exchange, 405, "method not allowed");
                return;
            }
            byte[] body = exchange.getRequestBody().readAllBytes();
            String signature = exchange.getRequestHeaders().getFirst("X-Signature");
            if (!verify(body, signature)) {
                logger.warning("Rejected webhook with a bad signature.");
                respond(exchange, 401, "bad signature");
                return;
            }

            Map<String, String> form = parseForm(new String(body, StandardCharsets.UTF_8));
            String reference = firstNonBlank(form.get("reference"), form.get("referenceNo"));
            String status = firstNonBlank(form.get("status"), form.get("resultCode"));
            String providerRef = firstNonBlank(form.get("providerRef"), form.get("transactionId"));
            if (reference == null) {
                respond(exchange, 400, "missing reference");
                return;
            }
            if (status == null || !SUCCESS.getOrDefault(status.toLowerCase(), false)) {
                // Acknowledge non-success callbacks so the gateway stops retrying.
                respond(exchange, 200, "ignored");
                return;
            }

            TopupSettlement outcome = wallet.settleTopup(reference, providerRef);
            if (outcome == TopupSettlement.GRANTED) {
                notifyBuyer(reference);
            }
            respond(exchange, 200, outcome.name().toLowerCase());
        } catch (Exception e) {
            logger.severe("Webhook handling failed: " + e.getMessage());
            respond(exchange, 500, "error");
        }
    }

    private boolean verify(byte[] body, String signature) {
        if (secret == null || secret.isBlank() || signature == null) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] expected = mac.doFinal(body);
            return MessageDigest.isEqual(hex(expected).getBytes(StandardCharsets.UTF_8),
                    signature.trim().toLowerCase().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            logger.severe("Signature check error: " + e.getMessage());
            return false;
        }
    }

    private void notifyBuyer(String reference) {
        String userId = pending.remove(reference);
        if (userId == null || jda == null) {
            return;
        }
        TopupInfo info = wallet.topup(reference);
        long credits = info == null ? 0 : info.credits();
        jda.retrieveUserById(userId).queue(user ->
                        user.openPrivateChannel().queue(channel ->
                                channel.sendMessage("✅ เติม Credit สำเร็จ! ได้รับ " + credits
                                        + " Credit (ref: " + reference + ")").queue(null, e -> { })),
                e -> { /* user unreachable — ignore */ });
    }

    private static Map<String, String> parseForm(String body) {
        Map<String, String> map = new HashMap<>();
        for (String pair : body.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
            String value = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            map.put(key, value);
        }
        return map;
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        if (b != null && !b.isBlank()) return b;
        return null;
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    private static void respond(HttpExchange exchange, int code, String body) throws IOException {
        byte[] out = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(code, out.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(out);
        }
    }
}
