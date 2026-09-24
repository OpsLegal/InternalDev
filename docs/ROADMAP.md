# Roadmap: from MVP to the stores

## 1. Before the first Play Store release

- [ ] **Check the build on a real device.** Add the widget and check that tapping a cell turns it yellow.
- [ ] **App identity.** Choose the final name and `applicationId` (currently `com.opslegal.tda`), make a proper icon, and write the store listing and screenshots.
- [ ] **Signing.** Create an upload key, enroll in Play App Signing, and add a signed-release job to CI (keystore stored as a GitHub secret).
- [ ] **Subscription in Play Console.** Create the subscription product `tda_premium` with two base plans, `monthly` and `yearly`, and optionally a free-trial offer. The IDs must match `BillingRepository.PRODUCT_ID`.
- [ ] **Server-side purchase verification.** Right now the app trusts Google Play's client response. Before real revenue, verify purchase tokens with the Play Developer API from a small backend and listen to Real-Time Developer Notifications.
- [ ] **Privacy policy and Data safety form.** Tasks stay on the device. The AI key and conversation go only to the provider the user picks. Say this clearly, since users are sending their own data to their own AI account.
- [ ] **Backup / export.** `allowBackup` is off, to keep the encrypted key out of backups. Add a JSON export/import of the board, or cloud sync (see 3), so users don't lose their table when they change phones.
- [ ] **Onboarding.** A 3-screen intro to the method, an "add the widget" hint, and help getting an API key for each provider.
- [ ] **Translations.** French first (the day letters L/Ma/Me/J/V already exist).

## 2. Assistant improvements

- **Calendar awareness.** Read the device calendar (`CalendarContract`, which already syncs Outlook and Google accounts) so the assistant sees meetings and busy days. A day full of meetings should get fewer cells.
- **Email / OneDrive / Outlook.** Anthropic's API can connect remote MCP servers (the MCP connector). Premium users could connect Microsoft 365 or Google so the assistant turns important emails into tasks. This needs OAuth per user and a careful permission design.
- **Effort estimates.** Let a step take 2 cells (a "double block") for heavy work, with the assistant proposing the split.
- **Learning.** Track which cells keep rolling over and which days end fully yellow, and feed that into the assistant memory ("Mondays: 4 tasks, not 5").
- **Voice input** for quick capture ("add: call Patrick about the balance").
- **Streaming replies** in the chat for faster feedback.

## 3. Multi-device and iOS

- `core` is plain Kotlin with no Android dependency. The next step is to convert it to a
  **Kotlin Multiplatform** module (swap `java.time` for `kotlinx-datetime` and `UUID` for a KMP id), then build an
  iOS app with SwiftUI and a WidgetKit widget that reuses the same planner and assistant.
- For sync between phone, tablet and PC, add an account and a small sync backend (or a
  per-user cloud store). The board is a single JSON document, which keeps sync simple.
- On iOS, subscriptions go through StoreKit 2. Use one backend entitlement for both stores, so a subscription bought on either platform unlocks the app on both.

## 4. Pricing ideas

- Free: the table, the widget, manual planning, and the rules.
- Premium (monthly / yearly, the yearly plan about 40% cheaper): the assistant, the morning review, and later sync and calendar/email connections.
- Users pay their AI provider directly for tokens, which keeps your costs flat. A "managed AI" plan with a server-side key could come later for users who don't want to create an API key.
