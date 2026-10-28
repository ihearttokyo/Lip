# Last dictation → agent: implementation extension

Additive user authority27October. Keep no-send/local-first defaults, existing major
plan and macOS second-shortcut parity. No transport/auth/background job is enabled
by this document. This feature passes the user's completed query to an agent; it
does not replicate a chat assistant inside the keyboard.

## User flow to implement

Settings: optional toolbar pin, provider/route/account and default effort. Short
press explicitly sends the last completed eligible Lip dictation through the
chosen admitted route. Before first use, show the exact destination and disclosure;
no account/API fallback. If no result or no configured route, explain without
reading current-field text, clipboard or private history. A new recording is not
sent until completed; failures/cancellation never manufacture completed output.

Long press: anchored slide-out effort chooser with user-requested Instant, Medium,
High, Extra High, Pro. Native white-on-black rows, selected indicator, accessible
focus/order/labels, outside-touch/Back cancel, bounded safe area and RTL/narrow
placement. About180ms user-triggered reveal, no motion when system animations off.
Opening/choosing a DEFAULT does not transmit; an explicit action commits one
immutable query/provider/account/effort snapshot exactly once. Keep effort modes
provider-capability-aware; Pro may mean a distinct model/mode, not an effort enum.
Unsupported mode is visibly unavailable, not silently substituted. No fake URL
parameters or unverified provider intent extras. Never write clipboard or press
Send in a destination app by UI automation. Claim transport dispatch separately
from provider acceptance/completion; ambiguous failures do not auto-retry.

## Verified route distinction, not a consumer-app promise

- OpenAI's Responses API supports asynchronous background requests and polling.
  It is an authenticated API route with its own account/billing/retention behavior,
  not proof of creating a chat in the ChatGPT consumer app. Current documentation
  states temporary response storage for asynchronous polling, including roughly10
  minutes with store=false; don't advertise background mode as zero retention.
  https://developers.openai.com/api/docs/guides/background
- OpenAI documents Codex SDK/exec/app-server as agent harness integrations. A
  Codex-backed local bridge could be a separate explicitly named destination; it
  must not be labelled a ChatGPT conversation merely because it uses plan login.
  https://developers.openai.com/blog/codex-as-a-platform
- Anthropic's current help states Claude Agent SDK/claude-p may use signed-in plan
  limits; linked Console API-key usage is a distinct route. Admission must verify
  the exact applicable plan/client terms. No subscription token extraction or
  API fallback. This is not proof of controlling Claude Android consumer chats.
  https://support.claude.com/en/articles/15036540-use-the-claude-agent-sdk-with-your-claude-plan
- Official Claude tooling overview distinguishes API clients from the higher-level
  agent runtime; do not bundle an unbounded tool-running agent in the keyboard.
  A separately authenticated local desktop bridge needs explicit pairing, locked
  permissions, no arbitrary shell/endpoint, lifecycle/cancel and disconnect tests.
  https://platform.claude.com/docs/en/cli-sdks-libraries/overview

No verified public consumer ChatGPT/Claude app contract accepting query+all five
effort choices was established in this bounded research. Mark it UNVERIFIED, not
impossible; inspect an authorized actual app manifest/documented share action
before implementing. API and consumer-app modes remain separate choices. Any API
implementation must first use the credential-admission skill; never request or
persist plaintext secrets in this chat or silently spend paid credits.

## Delivery blocks and unchanged9.2 gates

1. RAM-only completed-result provenance and privacy/lifetime contract, provider
   capability/settings model, configurable toolbar slot and accessible chooser.
2. Native synthetic tests: absent/canceled/new/old result, stale chooser/field,
   account switch, revoke/lock/restart, duplicate tap, output exactness/no clipboard/
   no current-field read. Test actual menu animation/reduced-motion/screensizes.
3. Supported route adapter behind explicit opt-in; synthetic transport first, then
   user-authorized nonprivate real send with exact destination/version/cost route.
   Distinguish successful dispatch from a real new provider conversation/result.
4. Mac second combo same provenance/defaults/route; no installed Mac restart or
   live private transcript use until existing hold changes. Independent actual
   intended-use9.2 and provider/privacy failures block acceptance, not averaged.

### Important Claude qualification from the specific SDK documentation
The official SDK overview, fetched after the general plan help page, explicitly
requires prior approval before third-party developers offer claude.ai login/rate
limits in their product. Its supported SDK languages are Python/TypeScript; it
runs the Claude Code binary and exposes tool permissions. The general help page's
statement about personal signed-in usage does NOT establish Lip's authority to
ship consumer login or reuse subscription tokens. No such login should be offered
without the applicable approval. An API-key route is separate and needs explicit
credential/billing admission; a local bridge also needs terms/permission review.
https://code.claude.com/docs/en/agent-sdk/overview
