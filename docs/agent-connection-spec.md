# Jimmy ↔ agents: connection contract (v1 draft)

For the team building the server side. Jimmy (the Docket 5 phone app) connects to **any** server that implements this
contract. Clay on Mohamed's server is the first (reference) implementation, not a requirement.

## 1. What Jimmy does, and what it never does

- Jimmy prepares an order for an agent; **the user taps Send**. Jimmy never sends an order on its own (Rule 1).
- The phone talks **directly** to the user's server. Nothing passes through OPS LEGAL TECH; we never hold anyone's
  URL, token or data.
- The user gives Jimmy only: **endpoint URL + token** (+ an optional label). Both are kept in the phone's secure
  keystore.
- Jimmy knows nothing about the backend: no agent names, models, paths or vendor at build time.

## 2. Transport

- Standard **MCP** (JSON-RPC 2.0) over **Streamable HTTP**, HTTPS only, with a valid public certificate.
- The endpoint must be reachable from a phone on mobile data (public HTTPS, or a VPN the user's phone joins).
- Auth: `Authorization: Bearer <token>` on every request. OAuth may come later; Bearer is enough for v1.
- `initialize` → `serverInfo` includes `"contract": "jimmy-agents/1"`. Jimmy refuses an unknown major version.
- Only `tools/list`, `tools/call` and (optionally) `resources/read` are used. No sampling, no prompts, no streaming
  notifications are needed.

## 3. The tools (exactly these three)

### `list_agents()`
Returns the agents this server offers, read by Jimmy each time (never baked in).

```json
{ "agents": [
  { "id": "monthly-report",
    "name": "Monthly report",
    "description": "Prepares the monthly activity report for a client or project.",
    "needs": ["project or client", "month"],
    "typical_minutes": 10,
    "prepare_only": true } ] }
```

- `needs`: plain words Jimmy shows in the order form (what the user must give).
- `prepare_only`: **must be `true`**. The server attests the agent only drafts, analyses or summarises: it never
  sends, pays, signs or deletes anything. Jimmy hides agents without it.

### `ask_agent({ agent, order, context?, want?, request_id })`
- `agent`: an `id` from `list_agents`.
- `order`: what to do, in plain words (up to 4,000 characters).
- `context`: optional facts from Jimmy (project name, dates, people), up to 20,000 characters. No files inline.
- `want`: optional output wish (`"pdf"`, `"docx"`, `"summary"`, …).
- `request_id`: a unique id made by the phone. **The same `request_id` twice must not create two orders** (a retry
  after a lost connection).

Returns at once (within 15 s):

```json
{ "status": "accepted", "job_id": "j-8f2c", "agent": "monthly-report", "eta_minutes": 10 }
```

or, for a quick agent, directly the final shape of `check_order` below.

### `check_order({ job_id })`
```json
{ "status": "working" | "done" | "failed" | "needs_input",
  "agent": "monthly-report",
  "summary": "Plain-words result or the question to answer (up to 2,000 characters)",
  "artifact_uri": "https://… or resource://…",
  "artifact_name": "2026-10 ACT report.pdf",
  "notes": "Anything the user should check" }
```

- Jimmy polls: after 1 min, then every 5 min while the app is open, and at its regular checks (at most every 30 min)
  in the background, until `done`, `failed` or 24 h.
- `needs_input`: the agent asks a question; the user answers with a new `ask_agent` that has
  `context.reply_to = job_id`.

## 4. Results and files

- `artifact_uri` is opaque to Jimmy. Either an **https link the user can open** (their OneDrive or SharePoint share,
  for example) or an MCP resource that `resources/read` returns with the same token.
- Files stay on the user's server or cloud. Jimmy shows the summary as a card in the bell and opens the link on tap;
  with OneDrive connected, it may save a copy in the project's folder (the user's own drive).

## 5. Errors

- A JSON-RPC error with a plain-words `message`, shown to the user as is. Codes: `401` wrong token, `404` unknown
  agent or job, `429` too many orders (with `retry_after_seconds`), `503` agent offline.
- Jimmy never retries an order more than once, always with the same `request_id`.

## 6. The decisive test

> If Claude/Clay is replaced by ChatGPT, Gemini or a custom agent behind the same three tools, token and return
> shapes, Jimmy needs **no change**.

Anything in sections 2 to 5 that would only work with one vendor is a bug in this contract: tell us before building.

## 7. What the server team provides to the user

1. The endpoint URL (HTTPS) and a token for this user (revocable).
2. A short list of the agents and what each one needs (Jimmy also reads it live from `list_agents`).
3. Confirmation that every listed agent is prepare-only.
4. Where results land (link type), and who can open them.

## 8. Honest boundary

Jimmy connects to any agent that implements this contract. An AI service is not reachable "out of the box": someone
hosts a small bridge in front of it (Clay is ours). Marketing must say "connects to agents that follow the open
Jimmy connection contract", never "works with any AI".
