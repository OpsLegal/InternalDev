# Docket 5 — features, plans and what each one brings

Working document for the business plan and the tester guide (`docs/guide/docket5-guide.html`).
Nothing here is final: plans and prices are to be validated with testers.

## The bare minimum (Core)

The app must stay useful with every optional feature turned off. Core is what is left, and it is enough to
replace a paper planner for someone who blocks in front of long lists:

| Feature | What it does | Why it is core |
|---|---|---|
| The 5-cell table | One line per day, five cells, one task per cell. Colours: blue task, green project step, black meeting, red deadline, yellow done, red background = not done on its day. | It is the product: a day you can see at a glance. |
| Tasks and projects (two levels) | A task fills one cell; a project is steps in an order. Never a third level. | Simple beats complete. |
| Automatic planner | Places cells by importance, deadlines, project order and waits; makes room before a deadline; never overloads a day. | Removes the "where do I put this" decision. |
| Done · Push · Again later · Move · Undo | Done turns a cell yellow; Push/Again later re-plans it and keeps the red record; Move (hold 2 s or Move in the menu) puts it on a chosen day; Undo after a move. | Every change shows its consequence first (a project's new end). |
| Find | One search over table, calendar and the rest, instant, on the phone. | Nothing gets lost. |
| Suggestions (local) | Do it today, a smaller first step, a project at risk, bring forward, no Nourish this week. Snooze or Don't change. Computed on the phone, no AI. | Nudges without effort. |
| Progress | Projects coloured by state (running well / needs attention / finished), templates, delete. | One look tells what is late. |
| Home-screen widget | Today's cells without opening the app. | Out of sight is out of mind. |
| Manual mode | Every form works without AI. | The app never depends on a connection. |

Security, for all plans: everything is kept on the phone (no Docket server, Android backup off). Keys and
sign-in tokens are encrypted with the Android Keystore. Rule 1, always on and unchangeable: the assistant
prepares, it never sends, accepts, declines, pays or deletes without the user's tap.

## Essential (base subscription)

| Feature | Value when on | Lost when off | Still works when off | Effect on results |
|---|---|---|---|---|
| Your own AI (Claude or ChatGPT key) | Talk or type in your words; ✨ writes the task, plans a project's steps, Discuss finds the best course of action. Data goes from the phone straight to the AI you chose. | The assistant, ✨ buttons, Discuss, voice. | Manual forms, planner, suggestions, everything in Core. | High: the difference between a planner and an assistant. |
| Voice | Say it, Jimmy places it and answers aloud; hands-free mode. | Speaking instead of typing. | Typing to the assistant. | Medium (high on the road). |
| Persona (name and face) | An assistant with a name and a face; messages written to "Jimmy" are recognised and answered as Jimmy. | The personal touch. | Same assistant, called "Assistant". | Low on results, high on engagement. |
| What matters (Ground · Build · Nourish) | Every cell serves something; the week is balanced (score /100, details, weekly review). | Balance scores and the review's advice. | Planning by importance and deadlines. | Medium: protects health, people and joy. |
| My week (areas, days per job) | Work for each job only on its days ("ACT on Tue/Thu"). | Area-aware planning. | One work week for everything. | High for people with two jobs; none otherwise. |
| Routines | Recurring moments (gym, school run) counted in the week without taking cells. | Routines in the balance. | Add them as tasks. | Low. |
| Easy and hard for me | Heavy work gets an easy first step; easy work is used as a reward. | Personal pacing. | Effort set per task. | Medium for procrastination. |
| Assistant rules | Rules by priority; ask Jimmy to write one (guarded: no rule can break Rule 1). | Custom behaviour. | Built-in rules. | Low to medium. |
| Days off and holidays | No work planned those days; deadlines planned around them. | Vacation-aware planning. | Push cells by hand. | Medium. |
| On my mind and Ideas | Empty your head in a list; Jimmy places it. Park ideas and projects. | Brain dump to cells. | Add tasks one by one. | Medium for ADD. |
| Templates | A finished project becomes a template for the next one. | One-tap project steps. | Write the steps again (or ✨). | Low. |
| Shopping list | Items to buy; one Errands cell for a trip, never one cell each. | The cart. | Add errands as tasks. | Low. |

## Plus (higher plan: the 360° assistant)

| Feature | Value when on | Lost when off | Still works when off | Effect on results |
|---|---|---|---|---|
| Email (Microsoft; Gmail by notifications) | Every email that asks something becomes a card: reply, add to tasks, done, dismiss, with a note. Read-only. | Nothing slips through email. | You add what you remember. | High for professionals. |
| Messages (WhatsApp, SMS, Beeper) | Requests in chats become cards; who waits for you, and since when. | The "who waits on me" view. | Same, by hand. | High. |
| Phone calendar | Meetings take their cells; review tomorrow's events the day before. | Calendar-aware planning. | Add meetings as cells. | High. |
| Reply drafts | Jimmy writes the reply in your style, from your note ("say yes for Thursday"); you send it. | Drafts. | Reply yourself. | Medium. |
| Notes about people | What you noted about a person is read before answering them again. | People memory. | — | Medium. |
| Scheduled checks and weekly sweep | Checks at your review times; the sweep finds threads left unanswered. | Proactive catch-up. | Check now by hand. | Medium. |
| Documents and receipts reading | Photo of a form: what to fill becomes a checklist; receipt: total, taxes, tip, vendor read for you. | Reading by the AI. | Attach files and type amounts. | Medium. |
| Expense reports | Period and project, totals and billable part, CSV or PDF to send. | Reports. | The list of expenses. | Low to medium. |

## Pricing notes (to test, not decided)

- The user pays their own AI usage directly to Anthropic or OpenAI (often a few dollars a month); the
  subscription pays for the app only. Say it plainly: it is also a security argument.
- Test willingness to pay with testers (guide, last chapter) before setting prices.
- Possible later tier: several email accounts (two Microsoft accounts, Microsoft + Gmail) and family sharing.

## Turning features on and off (later, in the app)

Once testers have validated the list: Settings → Features, one switch per optional feature with its "value
when on" and "still works when off" lines from this document. Turning a feature off hides its screens and
buttons and stops its checks; it never deletes data, so turning it back on restores everything.
