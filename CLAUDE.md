# Working rules for this repository

## Master rule (applies to every venture and every decision)

**Launch quickly with the minimum solid product, get real feedback, learn and correct.**

- Ship the smallest version that works reliably, then iterate from what real users do and say.
- Don't spend time polishing, branding or adding features before people have used the thing.
- When in doubt between "more complete" and "in users' hands sooner", choose sooner, as long as it
  is solid: no data loss, no security shortcuts, no broken core flow.
- Defer everything that can be decided later with real feedback; write it in docs/ROADMAP.md instead.

## This project

TDA 5 (published by **OPS LEGAL TECH**, the legal-tech sister brand of Ops Legal): an Android app
with a 5-tasks-a-day table, home-screen widget and a voice AI assistant. See README.md.

- `core/`: pure Kotlin, tested with `./gradlew :core:test`.
- `app/`: Android; builds need `ANDROID_HOME`. CI builds on every push.
- Release builds are signed with the Play upload key from environment variables (never commit keys).
