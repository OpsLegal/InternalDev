# Working rules for this repository

## Master rule (applies to every venture and every decision)

**Launch quickly with the minimum solid product, get real feedback, learn and correct.**

- Ship the smallest version that works reliably, then iterate from what real users do and say.
- Don't spend time polishing, branding or adding features before people have used the thing.
- When in doubt between "more complete" and "in users' hands sooner", choose sooner, as long as it
  is solid: no data loss, no security shortcuts, no broken core flow.
- Defer everything that can be decided later with real feedback; write it in docs/ROADMAP.md instead.

## Product principle (Docket 5): simple beats complete

The app exists because complicated tools make people with ADD block or lose focus. Every screen and concept must
stay obvious at a glance: fewer levels, fewer words, one clear choice at a time, big one-thumb buttons.

- Two levels only: a **task** is one cell; anything that needs several cells in an order is a **project** of steps.
  Never add a third level (sub-tasks, task groups...).
- When a feature adds a concept, a screen or a question, first look for a way to fold it into an existing one.
- Show the consequence of a change at the moment of the choice (e.g. "project now ends Oct 20"), never after.

## This project

Docket 5, formerly "TDA 5" (published by **OPS LEGAL TECH**, the legal-tech sister brand of Ops Legal): an Android app
with a 5-tasks-a-day table, home-screen widget and a voice AI assistant. See README.md.

- `core/`: pure Kotlin, tested with `./gradlew :core:test`.
- `app/`: Android; builds need `ANDROID_HOME`. CI builds on every push.
- Release builds are signed with the Play upload key from environment variables (never commit keys).
- Changes are tried in the HTML preview first. Only when the user approves ("push to the store") run the
  Android workflow manually (workflow_dispatch): its `play-internal` job uploads to the Play **Internal testing**
  track (version code = 100 + run number). Plain pushes only build and test. Production releases stay manual.
