# BranzDiscord

The Discord storefront front-end, as a Paper plugin. It runs inside the game
server and reaches the shop through the central **BranzWallet** `WalletApi`, so
there is **no duplicated currency logic** — the bot calls the same service the
game does, and (later) grants ranks/items in-JVM.

## Why a plugin (not a standalone bot)

Delivering a purchase — granting a LuckPerms group, handing an item to an online
player — must run inside the server JVM anyway. Keeping the bot there too means
one component instead of two, one language, and direct `WalletApi` access with
no SQL to keep in sync. A standalone Node bot + web dashboard can be added later,
when the community outgrows a pure storefront.

## Commands

| Command | Status | What it does |
|---|---|---|
| `/link <code>` | ✅ live | Redeems a 6-digit code from `/wallet link` in-game (`WalletApi.redeemLinkCode`) and grants the Linked role. |
| `/balance` | ✅ live | Shows the caller's Coin and Credit (`WalletApi.coins` / `credits`). |
| `/topup <package>` | ✅ live | Creates a pending order (`WalletApi.createTopup`) and shows the amount, credits and a reference. A payment webhook settles it and grants Credit. |
| `/buyrank <rank>` | ✅ live | Confirm-button purchase: spends Credit (idempotent), grants a LuckPerms group (permanent or timed, offline-safe), assigns the Discord role. Auto-refunds if the grant fails. Requires LuckPerms. |

## Ranks

Define products in the `ranks` section of `config.yml` — each maps to a LuckPerms
group, a Credit price, an optional Discord role, and `duration-days` (0 =
permanent). The buyer confirms via a button before any Credit is spent; a
repeated click reuses the same transaction id, so the charge can never double.

## Setup

1. Build: `./gradlew build` → `build/libs/BranzDiscord-1.0.0.jar`.
2. Drop it into `survival/plugins/` alongside `BranzWallet.jar`.
3. Edit `plugins/BranzDiscord/config.yml`: set `discord.token`, `discord.guild-id`,
   and optionally `discord.linked-role-id`.
4. Restart. Slash commands register to the guild on connect.

**On a multi-backend network, enable this on exactly one server** — two gateways
on one token double every reply.

## Top-up & payments

`/topup` creates a pending order in the wallet and shows a reference. Money is
settled by a **webhook**, not by the bot charging anyone:

- The bot runs a small HTTP endpoint (`topup.webhook.*` in config) that accepts a
  form-encoded POST and verifies `X-Signature = hex HMAC-SHA256(rawBody, secret)`.
- On a success status it calls `WalletApi.settleTopup(reference, providerRef)`,
  which grants Credit **idempotently** — a gateway retrying the callback is safe.
- The buyer is DM'd on settlement (best-effort).

This generic signed-webhook contract works today with an admin approval tool or
any gateway. **GB PrimePay**: point its background URL at this endpoint and map
`referenceNo → reference`, `resultCode → status` (the handler already reads those
field names); sign the body per your GB PrimePay secret, or place a thin adapter
in front. Creating the QR charge itself (calling GB PrimePay's API) still needs
your merchant credentials and is left to configure — the settlement half is done.

Expose the webhook port only to the payment provider, and set a private
`topup.webhook.secret`.

## Dependencies

- `BranzWallet` (hard depend) — the currency + linking service.
- `LuckPerms` (soft depend) — used by the rank system (not wired yet).
- JDA (shaded) — the Discord gateway; audio and okhttp/jackson are relocated.
