# Branz Community Platform — Roadmap

> **Vision.** The Discord server (watersodeep's server) is the **home of the
> community across its whole life cycle** — discover → onboard → engage → retain
> → monetize → advocate → win-back — not just a storefront. The store is one
> stage of seven.
>
> **Core insight.** The Idle game already produces rich life-cycle signals
> (streaks, milestones, ranks, leaderboards, seasons, rare drops). They are not
> yet surfaced to Discord. **Bridging game events → Discord is the heart of the
> work that remains.**

Last updated: 2026-07-23.

---

## 1. Architecture recap

| Component | Role | Notes |
|---|---|---|
| **branz-idle** | The MMO game (Paper plugin). Source of life-cycle signals. | Owns gameplay; emits streaks/milestones/ranks/seasons. |
| **branz-wallet** | Central currency + account link + top-up. `WalletApi`. | Shared MySQL. Done. |
| **branz-discord** | The community bot (JDA, in the game JVM). | Direct access to game events + `WalletApi` + LuckPerms + Bukkit. **This is where most life-cycle features live.** |
| **mcp-discord / "Bunzz"** | Build-time server management driven by the AI assistant. | Creates channels/roles/messages. Not a runtime feature. |
| **Node + web hub** *(future)* | Web dashboard, richer payments, out-of-game community. | Deferred until scale demands; see §5. |

Because BranzDiscord runs inside the game JVM, a game event can become a Discord
message with **no external bridge** — this is the decisive advantage of the
plugin approach and what makes the life-cycle work cheap.

---

## 2. The seven stages

Status legend: ✅ done · 🟡 partial · ⬜ not started

### Stage 1 — Discover / Acquire ⬜
Get people to the server.
- Invite tracking (who invited whom) → referral attribution.
- Referral rewards (Credit/rank for bringing active players).
- Server discovery / vanity invite / shareable landing.
- In-game "join our Discord" prompts with a personal link code.

### Stage 2 — Onboard 🟡
Turn an arrival into a set-up member.
- ✅ Account link (`/link` ↔ in-game `/wallet link`, grants **Linked** role).
- ⬜ Auto-welcome message + DM with first steps.
- ⬜ Rules acknowledgement gate.
- ⬜ Self-serve role selection (buttons/reaction roles: notify prefs, region, playstyle).
- ⬜ "First steps" checklist (link → claim starter → join a channel).

### Stage 3 — Engage ⬜ (highest impact next)
Make the server feel alive.
- ⬜ **Game → Discord notifications**: rank-up, rare drop, milestone reached,
  season start/end, big purchases. *(uses existing game signals)*
- 🟡 **Leaderboards**: channel exists; auto-post/refresh daily/weekly from
  `wallet_accounts` + game stats.
- ⬜ Activity roles (talkative, veteran, active-player) from message/play activity.
- ⬜ Events: scheduled Discord events tied to in-game seasons/double-XP.
- ⬜ Minigames / daily trivia with Credit rewards.

### Stage 4 — Retain ⬜
Bring them back on a rhythm.
- ⬜ Daily reward / streak surfaced to Discord (game already has `StreakService`).
- ⬜ Streak-about-to-break reminder (DM).
- ⬜ Seasonal content countdowns and recap.
- ⬜ Comeback nudge after N days away, with a small incentive.

### Stage 5 — Monetize ✅
Convert engagement to revenue.
- ✅ `/topup` — pending order + HMAC-verified settlement webhook → Credit.
- ✅ `/buyrank` — Credit → LuckPerms group + Discord role (idempotent, refund-safe).
- ✅ `/balance` — Coin + Credit.
- ⬜ Promotions / limited-time bonus Credit.
- ⬜ VIP-only perks & channels (LuckPerms/role gated).
- ⬜ Live GB PrimePay QR charge creation (needs merchant creds; settlement half done).

### Stage 6 — Advocate 🟡
Turn members into promoters and co-owners.
- 🟡 Support tickets (channel exists; add button→private thread flow).
- ⬜ Referral rewards (ties to Stage 1).
- ⬜ Veteran/OG roles by tenure or total top-up.
- ⬜ Feedback & suggestions board with voting.
- ⬜ Content sharing / screenshot showcase + reactions.

### Stage 7 — Win-back ⬜
Recover lapsed players.
- ⬜ Detect inactivity (no play + no chat for N days).
- ⬜ "We miss you" DM with a comeback reward.
- ⬜ Re-engagement campaign around new seasons.

### Cross-cutting ⬜
- **Moderation & safety**: automod, kick/ban/timeout, mod log, anti-scam
  (already warned users in #how-it-works).
- **Analytics**: funnel metrics per stage (joins, links, first purchase,
  retention, churn), revenue dashboard.
- **Staff tools**: admin commands to grant/refund Credit, look up a player,
  manually settle a top-up slip.
- **Infrastructure**: one Discord app vs two (Bunzz vs BranzDiscord), hosting,
  webhook exposure/TLS, secrets management.

---

## 3. Technical enablers still needed

1. **Game → Discord event pipeline.** The single most important enabler for
   Stages 3/4/7. Options:
   - *(recommended)* Idle fires Bukkit **custom events** (e.g. `RankUpEvent`,
     `MilestoneEvent`, `RareDropEvent`, `StreakEvent`); BranzDiscord listens and
     posts/DMs. Clean, in-JVM, no polling, no new dependency direction.
   - Alternative: a small `NotificationApi` service (like `WalletApi`) Idle
     calls directly.
   - Avoid DB polling except as a last resort.
2. **Discord→player identity everywhere.** `WalletApi.linkedUuid` exists; add the
   reverse (`discordIdFor(uuid)`) so game events can find the player's Discord
   user to DM. Needs storing/looking up by `owner_uuid`.
3. **New persistence** (in BranzWallet or a BranzDiscord DB module): referrals,
   activity counters, reminder schedule, ticket metadata.
4. **Scheduler** for retention/win-back jobs (Bukkit scheduler or a small timer).
5. **MCP tool gaps** (build-time only, for the assistant): moderation, scheduled
   events, emoji/sticker upload, invites — add to mcp-discord if needed.

---

## 4. Phased delivery plan

| Phase | Theme | Deliverables | Depends on |
|---|---|---|---|
| **0** ✅ | Foundation | wallet, link, store, ranks, top-up | — |
| **1** | **Engage core** | Game→Discord event pipeline; rank-up / milestone / rare-drop notifications; auto leaderboard | reverse identity lookup |
| **2** | Onboard | auto-welcome + DM, rules gate, role selection buttons, first-steps | — |
| **3** | Retain | streak reminder, daily reward surface, seasonal countdowns, comeback nudge | event pipeline + scheduler |
| **4** | Advocate | ticket flow, referrals, veteran roles, feedback board | referral persistence |
| **5** | Discover + Win-back + Ops | invite tracking, inactivity detection, moderation, analytics | activity data |

**Recommended start: Phase 1 (Engage core)** — highest impact, reuses signals the
game already produces, and builds the event pipeline every later phase needs.

---

## 5. Open decisions

- **One Discord app or two?** Bunzz (assistant/admin) vs BranzDiscord (runtime).
  Recommend separate applications for clarity and least-privilege.
- **When to add the Node/web hub?** Web dashboard, richer analytics, multiple
  payment SDKs, and out-of-game community features are Node's strength. Trigger:
  when the community outgrows in-Discord features or a web store/dashboard is
  wanted. Until then, everything stays in BranzDiscord.
- **Where does new persistence live?** BranzWallet (owns the shared DB) vs a new
  DB module in BranzDiscord. Prefer BranzWallet for anything currency-adjacent.

---

## 6. Status snapshot

- ✅ Stage 5 Monetize; ✅ account linking (Stage 2 core).
- 🟡 Stage 2 Onboard, Stage 3 Leaderboard, Stage 6 Support (channels exist).
- ⬜ Stages 1, 4, 7, cross-cutting, and the game→Discord event pipeline.
