# TDA 5: five tasks a day

An Android app built around one paper method that works for people with ADD
(TDA in French): **a table with one line per day and five equal cells per day.**
Each cell is one task, meaning one focused block of a few hours. You highlight a
cell in yellow when it's done. A fully yellow line is a good day, and the lines
below show what's coming.

```
Day | 1            | 2             | 3              | 4           | 5
----+--------------+---------------+----------------+-------------+-------------
Th3 | ■ Tax: gather| ■ Call notary | ■ ACME meeting | □ Offer v2  | □ Garage
F4  | □ Tax: fill  | □ Bank docs   | □ Offer v3     | ·           | ·
M7  | □ Tax: review| ...
```
(■ = yellow/done, □ = to do, · = free cell. `Th3` = Thursday the 3rd, `F12` = Friday the 12th.)

The app adds a **TDA Assistant**. Each user connects their own AI account
(Anthropic Claude, OpenAI, or any OpenAI-compatible service), and the assistant
fills and maintains the table from conversation ("add a meeting with ACME on
Thursday", "the tax report blocks the refinancing, plan it").

## What's in the MVP

| Feature | Where |
|---|---|
| 6-column table (day + 5 equal cells), tap = yellow, long-press = details, move, delete | `app/.../ui/TableScreen.kt` |
| **Home-screen widget** with the same table; tapping a cell turns it yellow | `app/.../widget/TableWidget.kt` |
| Planner: max 5 per day, big tasks split into steps on different days, waiting time between steps, weekends skipped, meetings on a fixed day | `core/.../plan/Planner.kt` |
| **Blockers inherit priority**: a task that blocks a CRITICAL task becomes CRITICAL (the tax report → refinancing case) | `Planner.effectiveWeights` |
| **Urgent work that doesn't fit**: up to 3 ranked options (move the least important cells / only move tasks without a deadline / accept being late), with what moves and what becomes late. Nothing moves until you choose | `core/.../plan/Rescheduler.kt` |
| Daily "cron" (4:30 every morning): unfinished cells roll over, new steps get placed, plus an optional AI morning review with a notification | `app/.../work/DailyPlanWorker.kt` |
| **Assistant rules**, shown in the app in priority order: edit, reorder, turn off, add your own | `core/.../model/DefaultRules.kt`, `app/.../ui/RulesScreen.kt` |
| Assistant memory ("prefers calls in the morning"...), visible and deletable on the Rules screen | `remember` tool |
| Bring-your-own AI: Anthropic, OpenAI, OpenAI-compatible (Mistral, OpenRouter...). The key is encrypted with the Android Keystore | `core/.../agent/`, `app/.../data/SettingsRepository.kt` |
| Google Play subscription (monthly / yearly). The table and widget are free; the assistant needs Premium | `app/.../billing/BillingRepository.kt` |

### How the assistant works

The AI decides *what* to do: which tasks, how to split them, their priorities,
deadlines and what they block. A deterministic planner in `core` decides *where*
the cells go, so the model can't break the 5-per-day rule. The assistant acts
through tools (`get_table`, `add_task`, `propose_options`, `apply_option`,
`set_step_done`, `move_step`, `remember`, `add_rule`...). Your rules are sent to
it in priority order on every request.

## Project layout

```
core/   Pure Kotlin: models, planner, rescheduler, rules, AI providers, agent loop.
        No Android dependency, so it can be shared with an iOS app later (Kotlin Multiplatform).
app/    Android: Jetpack Compose UI, Glance widget, WorkManager, Play Billing.
```

## Build

```bash
./gradlew :core:test            # planner + agent tests, any machine with a JDK 17+
./gradlew :app:assembleDebug    # needs the Android SDK (ANDROID_HOME or local.properties)
```

Debug builds unlock Premium so you can test the assistant without a Play listing.
CI (`.github/workflows/android.yml`) runs the tests and builds debug and release APKs.

See [`docs/ROADMAP.md`](docs/ROADMAP.md) for the steps to publish on Google Play and later the App Store.
