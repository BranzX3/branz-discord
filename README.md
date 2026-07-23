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
| `/topup` | 🚧 stub | Will create a PromptPay/TrueMoney charge and grant Credit on a verified webhook. |
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

## Dependencies

- `BranzWallet` (hard depend) — the currency + linking service.
- `LuckPerms` (soft depend) — used by the rank system (not wired yet).
- JDA (shaded) — the Discord gateway; audio and okhttp/jackson are relocated.
