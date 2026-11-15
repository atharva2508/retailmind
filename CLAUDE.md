# RetailMind (springboot-postgres-api)

Retail operations assistant. Spring Boot 3.3.0, Java 17, PostgreSQL, JWT auth, Lombok, JPA (`ddl-auto: update`).
Original plan wanted Boot 4.1 / Java 21 / Spring AI + Claude / pgvector / Flyway. None of that exists yet. Chat is keyword routing, not an LLM.

## How to work here
- One fix at a time. Finish it, run the build, report in a few lines, then STOP and wait for the user to say "next".
- Short answers. No filler. Laziest working solution first: reuse what exists, use the standard library, avoid new dependencies and abstractions.
- Keep validation, error handling, security and accessibility intact.
- No git commits or pushes unless the user asks. No new files outside the project folder.
- Run `mvn verify` after every fix and show failures verbatim. Nothing below was built or tested yet.
- Docs `README.md`, `HLD.md`, `design-notes.md` are NOT in this repo. The rules that matter are summarised below.

## Status
- Fix 1 DONE, not built: registration always creates ROLE_USER; H2 console removed and H2 is test-only; JWT secret and seed passwords come from env; compose binds Postgres to 127.0.0.1.
- Fix 2 DONE, not built: `StoreAccessService.scopeOrNull / resolveStore` on every endpoint with a storeId (low-stock, details, reconciliation, fulfillment, dashboard, chat, suggestions); proposals have `storeId`; audit scoped and capped at 200; 5 new tests.
- FIRST TASK: run `mvn verify`, fix any compile or test failure from Fix 1 and 2, report.
- `.backup-fix1/` is a copy of the files before Fix 1. Safe to delete.

## Required environment (no defaults on purpose)
`DB_PASSWORD`, `JWT_SECRET` (32+ bytes, app refuses to start otherwise). Optional `DB_USER`, `DB_URL`.
Dev only: `SEED_ENABLED=true` and `SEED_ADMIN_PASSWORD`, `SEED_MANAGER_PASSWORD`, `SEED_REVIEWER_PASSWORD`, `SEED_USER_PASSWORD`.
Spring Boot does not read `.env`: use the IDE run config or shell env. `docker compose up -d` needs `DB_PASSWORD` in `.env`.
Tests use `src/test/resources/application.yml` (H2, test-only secrets and seed passwords).

## Domain rules (keep)
- Snapshot file columns stay `bal, aud, rac, held`. App shows five buckets: available = bal - held; in checkout = held (TTL block); hold = confirmed and picked, not shipped (from `order_lines`: confirmed - cancelled - shipped); good return = code 03 not moved to available; unavailable (bad return) = code 09 not sent to vendor.
- BAL drops when the store confirms an order. Customer cancel after confirmation adds back to BAL. NOT_FOUND cancel does not. Cancel before confirmation changes nothing.
- Reconciliation: expected_eod_bal = SOD.bal - confirmed + customer_cancelled(after confirmation) + good_returns_moved; expected_eod_rac = SOD.rac + received - good_moved - bad_sent.
- Store scope: admin sees all; other roles only their own store. Deny by default. Never trust a client storeId. Never default to store 1.
- The AI/chat is read-only. Only write path reachable from it is a pending proposal. Humans approve.

## Open work, in order
3. Approval flow (C4): reviewer comes from the login, not the request body; only reviewer permission (not store manager) may review; review only from PENDING_REVIEW; creator cannot approve own proposal; version check and one transaction on apply; apply must really change the price; expiry.
4. Errors (H4, H5): malformed or stale JWT returns 401 not 500; one `@RestControllerAdvice` (bad date, bad number, DB size errors -> 400); size limits on proposal text.
5. Login and tokens (H1, H3): per-username login limit and lockout, bucket eviction; refresh token hashing, reuse detection, locked rotation, absolute lifetime, logout, cleanup; no username/email enumeration; case-insensitive usernames.
6. Feed and data (H6, H7) plus reconciliation bugs: range checks on import; checksum of file content; absent SKU becomes 0 and flagged; store coverage check; `asOf` from data not now(); stale flag; never show "no data" as fresh; reconciliation `status` must not be hardcoded OK; add back only confirmed customer cancels; null `receivedAt` must not crash fulfillment issues; deleted product must not break suggestions.
7. Medium and low: CORS allowlist, audit immutability and login events, query performance, Flyway instead of ddl-auto, upgrade Boot, admin `PUT /products` must go through proposal and audit.
Later: real Claude integration, essentials three-state result, RAG.

## Test rule
Every fix adds a failing-first test for the break it closes. Existing tests log in with the seeded users from the test yml.
