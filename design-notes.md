# RetailMind: Design Notes

Status: Stage 0, revised once after Stage 1 (multi-store, daily inventory feed over SFTP, essentials). Planned design. Only Stage 1 is built.
Sources: README.md, HLD.md. Where they conflict, section 9 says which one wins.

## 1. Decisions

| Topic | Decision | Status |
|---|---|---|
| Chat LLM | Claude (Anthropic) via Spring AI Anthropic starter | Confirmed by user |
| Model name | Config property `retailmind.ai.chat-model`, not hard-coded | Default |
| Embeddings | Separate provider. Anthropic offers no embedding model | Default, see 2 |
| Language | Java 21 | Default |
| Spring Boot / Spring AI | Boot 4.1.0 + Spring AI 2.0.0 (GA 2026-06-12, from release notes). Not yet confirmed by a real build | Default, verify |
| Deployment | Modular monolith, one Spring Boot app | HLD |
| DB | PostgreSQL + pgvector, Flyway migrations | HLD |
| Conversation history | Persisted (`conversations`, `chat_messages`), bounded context window | Default |
| Upload formats | PDF, DOCX, TXT, MD. Max 10 MB | Default |
| Auth | Spring Security, JWT, roles below | Default |
| Stores | Many stores. Each has internal `id` and external `store_code` used in feed files | Confirmed by user |
| Inventory source | Each store sends one file daily at end of day. Pulled from SFTP. Inventory is "as of last file", not live | Confirmed by user |
| Essentials | Admin-maintained `is_essential` flag on products. Risk by days of cover, default 2 days, from last 7 days of sales | Confirmed by user |
| Feed format | CSV, full snapshot, columns `store_code,sku,quantity_on_hand,as_of`. Threshold stays in app | Confirmed by user |
| Store manager access | Sees only own store. Other users see all stores | Confirmed by user |
| Inventory history | Latest only. Import records kept. Daily snapshots deferred until a feature needs them | Default |

## 2. Claude-specific notes

- Spring AI supports Anthropic chat with tool calling. Fits the allowlisted tool design.
- **Embeddings gap.** Claude has no embeddings API. RAG needs another model. Options:
  1. Local ONNX transformers model in Spring AI (no API key, 384 dims). Default for dev and demo.
  2. Hosted embedding API (for example Voyage AI or OpenAI). Better quality, adds a second provider and key.
- Vector dimension is fixed by the model. Column `vector(384)` for default. Changing the model means a migration and re-embedding all chunks. Store `embedding_model` on each document.
- API key via env var `AI_PROVIDER_API_KEY` (README name). Never logged, never sent to model.
- Token usage comes from provider response metadata. Feed it to Micrometer.

## 3. Module map

One deployable. Modules talk through service interfaces, not through each other's repositories.

| Module | Owns | May call |
|---|---|---|
| `api` | Controllers, DTOs, error mapping | `ai.orchestration`, domain services |
| `security` | Auth, roles, rate limit, request ID filter | none |
| `ai.orchestration` | Prompt, model call, tool loop, source building | `ai.tools`, `ai.rag` |
| `ai.tools` | Five read-only tools, arg validation | domain services only |
| `ai.rag` | Ingestion, chunking, embedding, retrieval | `persistence` (vector tables) |
| `product`, `inventory`, `promotion`, `order` | Business rules, repositories | own tables |
| `store` | Stores, store codes, user-to-store access | own tables |
| `inventoryfeed` | SFTP poller, file validation, import pipeline, import records | `inventory`, `store`, `product` services only |
| `proposal` | Proposal lifecycle, approval, apply | `promotion`, `product`, audit |
| `audit` | `audit_events` writer | none |
| `observability` | Metrics, safe logging | none |

Hard rules:
- `ai.*` never imports repositories of domain modules.
- `ai.*` never imports the `proposal` apply/decision service. Proposal creation by AI goes through one narrow `ProposalDraftService.create(...)` that can only write `PENDING_REVIEW`.
- Domain modules never import `ai.*`.
- Enforce with ArchUnit tests.

## 4. Package layout

```
com.example.retailmind
├── api/                 controllers, dto, error
├── security/
├── ai/
│   ├── orchestration/
│   ├── tools/
│   └── rag/
├── product/ inventory/ promotion/ order/
├── proposal/
├── audit/
├── persistence/         shared config only
└── observability/
```

Create a package only when it has a class. No empty folders.

## 5. Request flows

### 5.1 Business question
1. `POST /api/chat`. Filter assigns request ID, authenticates, rate-limits.
2. Controller validates DTO (length limit on `message`).
3. Orchestrator builds prompt: trusted system text, bounded history, user message, tool definitions.
4. Claude requests a tool call.
5. Registry checks tool name is allowlisted. Args validated against schema.
6. Tool calls domain service. Service checks authorization and ids/dates.
7. Result returns to Claude for summary. Max N tool calls per request (default 3).
8. Response: answer, sources (tool name, reference, `retrievedAt` from runtime clock), request ID, conversation ID.

### 5.2 Policy question (RAG)
1. Embed question. Search top K chunks with metadata filter (approved docs, audience).
2. If best similarity below threshold: return insufficient-evidence answer. No model guess.
3. Else pass chunks to Claude inside a clearly delimited, untrusted "evidence" block.
4. Response carries document name, document ID, chunk ref.

### 5.3 Combined
Run retrieval plus allowed tool calls under same limits. Missing input leads to clarifying question.

### 5.4 Pricing/promotion suggestion
1. Model reads data through read-only tools.
2. Model output goes to `ProposalDraftService`. Status forced to `PENDING_REVIEW`, creator `AI_SUGGESTION`.
3. Humans decide through the decision endpoint (section 7). Model has no path to it.

## 6. Tools

| Tool | Args | Validation |
|---|---|---|
| `getProductDetails` | `productId` | positive id, product exists |
| `getLowStockProducts` | none | threshold from DB column |
| `getActivePromotions` | `productIds` | max 50 ids, each positive |
| `getSalesSummary` | `startDate`, `endDate`, optional `storeId` | ISO dates, start <= end, max range 366 days |
| `getEssentialsAtRisk` | optional `storeId` | essentials with days of cover below limit, or out of stock |

- `getProductDetails`, `getLowStockProducts`, `getActivePromotions` also take optional `storeId` where stock is shown.
- Every stock result carries `asOf` (time of the store's last file) and a `stale` flag.
- Store scope comes from the authenticated user. For a store manager the model-supplied `storeId` is ignored or checked against allowed stores. Model never widens access.
- Low-stock results list essentials first.

- All read-only. No `executeSql`. No write tool of any kind.
- Invalid args: reject, safe diagnostic, at most one correction attempt.
- Order service stays internal in v1. No order tool, no order endpoint.

## 7. Proposal workflow

Table `pricing_promotion_proposals`: `id`, `proposal_type` (PRICE_CHANGE, PROMOTION), target product or SKU set, proposed values, rationale, source refs, `creator_type`, `status`, `version`, `created_at`, `expires_at`, `reviewer_id`, `review_comment`, `decided_at`, `applied_at`.

Statuses: `PENDING_REVIEW`, `APPROVED`, `REJECTED`, `APPLIED`, `EXPIRED`.

Decision endpoint:
1. Spring Security checks `PRICING_PROMOTION_REVIEW`. Service layer checks again.
2. Reviewer ID comes from security context. Request body has no user ID field.
3. Load proposal. Must be `PENDING_REVIEW` and version must match request version.
4. Reject: record, done. Active data untouched.
5. Edit: store new values, stay `PENDING_REVIEW`, bump version. Needs a fresh approval on final values.
6. Approve: reload live data, revalidate margin, eligibility, dates. If it fails, no change, error returned.
7. Apply in one transaction. Idempotency key prevents double apply. Optimistic lock on target rows.
8. Write `audit_events`: proposal, reviewer, decision, time, before/after, result.

Required tests (HLD section 8): AI cannot change price; normal user cannot approve; stale or decided proposal blocked; approval attributed to real reviewer; constraints rechecked; rejected, pending, expired never affect active prices.

## 8. Security and limits

- Roles: `USER` (all stores, read), `STORE_MANAGER` (own store only, read), `ADMIN`, `PRICING_PROMOTION_REVIEWER` (holds `PRICING_PROMOTION_REVIEW`). Admin does not get review permission automatically.
- Store scoping enforced in services (store filter from security context), not in prompts and not in controllers only. Applies to REST, tools and RAG audience filter alike.
- Scoping is built in Stage 5. Before that, Stage 2 and 2b APIs accept `storeId` but have no auth, so run them locally only.
- Document upload: admin only. Checks type, size, content sniffing.
- Retrieved text and uploaded docs are untrusted data. Separate from system prompt.
- Rate limit per user. Over limit returns 429 with `Retry-After`.
- Limits: max input chars, max output tokens, max tool calls, max history messages.
- Logs: request ID, tool name, latency, tokens. No prompts, documents, secrets by default.
- Actuator: only `health` open. Rest behind admin.
- Synthetic data only.

## 8a. Stores, inventory feed, essentials

### Schema changes (revise `V1`, nothing deployed yet)
- `stores`: `id`, `store_code` unique, `name`, `active`.
- `inventory`: key `(store_id, product_id)`, plus `quantity_on_hand`, `reorder_threshold`, `as_of`, `updated_at`.
- `products`: add `is_essential` (default false), `default_reorder_threshold`. New store-product rows from the feed use the default threshold.
- `orders`: add `store_id`. Sales per store.
- `inventory_imports`: `id`, `store_id`, `file_name`, `checksum`, `as_of`, `status` (RECEIVED, APPLIED, SKIPPED_DUPLICATE, REJECTED), `row_count`, `error_summary`, `created_at`. Unique on `(store_id, checksum)`.
- `user_store_access`: user to store. Added in Stage 5 with users.
- Seed data: 3 stores, per-store stock, some essentials flagged.

### Feed file
- Name: `<store_code>_<yyyyMMdd>.csv`. Header row required. Columns: `store_code,sku,quantity_on_hand,as_of`.
- One file is a full snapshot for one store.

### Pipeline (all steps in `inventoryfeed`)
1. Poll SFTP `inbound/` every N minutes (config). Ignore names not matching the pattern.
2. Check size limit (config, default 5 MB). Download to temp file.
3. SHA-256 checksum. Same store and checksum already imported: mark `SKIPPED_DUPLICATE`, move on.
4. Strict parse. Reject whole file if any of:
   - store code in a row differs from file name, or store unknown or inactive
   - SKU unknown, or fails pattern `[A-Za-z0-9._-]{1,64}`
   - quantity not a whole number, or negative
   - `as_of` unparseable or in the future
   - duplicate SKU rows
   - `as_of` older than what is stored for that store (never overwrite newer with older)
5. One transaction: upsert all rows. SKUs missing from file keep old rows. Count reported in the import record.
6. Write `inventory_imports` row. Move file to `processed/` or `failed/`. Error text is a safe summary, no row data dump.

### Missing and stale files
- Config cutoff time (default 06:00 IST). Store with no applied file for the previous business day is `stale`.
- `stale` shows in tool results, chat answers ("as of ..., store has not sent today's file") and an admin view of stores missing files.

### Essentials
- Days of cover = `quantity_on_hand` / average daily units sold at that store, last 7 days. Count orders with status PLACED, PAID, FULFILLED. Cancelled and refunded are excluded.
- At risk when days of cover < N (config `retailmind.essentials.min-days-cover`, default 2), or quantity is 0.
- No sales in the window and quantity above 0: not flagged by cover. Flag only by normal threshold.
- Deterministic Java calculation. Model never computes it.

### SFTP security
- Key auth only. Strict host-key check against a pinned known-hosts file. No password auth, no trust-all.
- Config by env: `SFTP_HOST`, `SFTP_PORT`, `SFTP_USER`, `SFTP_PRIVATE_KEY_PATH`, `SFTP_KNOWN_HOSTS`. Never logged, never in source.
- App only touches its fixed directories. File names are validated before use. No path built from file content.
- CSV is parsed as data. Nothing is evaluated.
- Dev and tests: local-folder source feeds the same pipeline. Compose gets a test SFTP container in Stage 2b.
- Library choice (Spring Integration SFTP or plain SSH client) made at Stage 2b. Pick smaller.

### Single store
- No special mode. One store is one row in `stores` (for example `MAIN`).
- `resolveStore(storeId?)`: explicit id returns that store. No id and exactly one active store returns it. No id and several stores returns all, grouped by store. No guessing.
- Chat answers leave out the store name when there is only one store.
- Adding a second store later: insert a row, start receiving its file. No migration.

### Out of scope here
- Manual upload endpoint. SFTP is the only source. Admin gets read-only import status: `GET /api/admin/inventory-imports`.
- Daily snapshots, trends, forecasting.
- Auto reorder or purchase orders. Read and report only.

## 9. README / HLD conflicts and fixes

| Conflict | Fix |
|---|---|
| Sales tool: HLD `getSalesSummary`, README `searchSalesSummary` | Use `getSalesSummary`. Done in README. |
| HLD has two sections numbered 10 | Done. Observability is 11, later sections shifted. |
| Proposal workflow missing from both milestone lists | Done in HLD and README milestones. |
| `pricing_promotion_proposals` missing from HLD entity list | Done in HLD section 4.7. |
| README lists `getProductDetails` first, HLD table order differs | No impact. Ignore. |
| Order service defined, no order tool or endpoint | Keep internal. Revisit later. |
| README status says plan only | Keep until features ship. Update per stage. |
| HLD and README assume one store, live inventory | Add stores, store-scoped access, daily feed, "as of" timestamps. Update both in Stage 10. |
| HLD lists four tools | Now five (`getEssentialsAtRisk`). Update HLD 4.3. |
| HLD has no inventory import or SFTP | Add section and diagram node. Update HLD in Stage 10. |
| HLD/README have no `stores`, `inventory_imports`, `is_essential` | Add to entity lists. |
| Stage 1 `V1` is single-store | Revised at start of Stage 2 (see 8a). |

README and HLD were updated on user request after the Stage 1 review (stores, feed, essentials, five tools, renumbered sections, proposal workflow in milestones, Claude and embeddings, Sales tool name fixed). Stage 10 re-checks them against the built code. Rows below marked "done" are already fixed there.

## 10. Open decisions from HLD section 15

| Question | Default here |
|---|---|
| LLM provider | Claude |
| Embedding provider | Local ONNX 384 dims, swappable |
| Java / Spring versions | Java 21, latest stable pair checked at Stage 1 |
| Persist conversations | Yes |
| Document formats and size | PDF, DOCX, TXT, MD, 10 MB |
| Who sees which docs | `audience` metadata on chunk: ALL or ADMIN |
| Evaluation thresholds | Set after first baseline run. No numbers claimed before. |
| Stores | Many stores, `store_code` in feed |
| Inventory feed | SFTP pull, CSV, daily full snapshot |
| Essentials rule | Days of cover < 2, from 7-day sales. Admin-set flag |
| Store manager access | Own store only |
| Store ID format and feed time | Open: need real `store_code` examples and the usual arrival time. Cutoff default 06:00 IST |

## 11. Trade-offs

| Choice | Gain | Cost |
|---|---|---|
| Modular monolith | Simple build and deploy | Less independent scaling |
| Claude hosted | Fast start, good tool use | Cost, provider dependency |
| Separate embedding model | Works with any chat model | Two model configs, re-embed on change |
| pgvector in main DB | One system | Dedicated vector DB may scale better |
| Read-only tools | Small blast radius | No autonomous updates, by design |
| Human approval on pricing | Business owns decision | Slower than automation, by design |

## 12. Stage plan

| Stage | Output |
|---|---|
| 0 | This file |
| 1 | Maven skeleton, Docker Compose (Postgres + pgvector), Flyway, seed data |
| 2 | Revise `V1` (stores, store-scoped inventory, essentials flag, `store_id` on orders) and seed. Domain services, read REST APIs with optional `storeId`, error format, unit tests |
| 2b | Inventory feed: `inventory_imports`, CSV validation, SFTP poller, stale-store check, essentials days-of-cover service, admin import-status endpoint, test SFTP container, tests |
| 3 | Spring AI + Claude, `POST /api/chat`, timeouts, no tools |
| 4 | Five read-only tools (adds `getEssentialsAtRisk`), `asOf` and stale in results, validation, call limit, tests |
| 5 | Security, roles incl. `STORE_MANAGER`, `user_store_access`, store scoping in services, audit table, rate limit |
| 6 | Proposal workflow and its six required tests |
| 7 | Document ingestion, embeddings, pgvector, RAG, sources |
| 8 | Metrics (incl. import success, rejects, stale stores), token usage, safe logs, Actuator limits |
| 9 | Evaluation set (50 to 100 questions), injection and upload tests |
| 10 | Dockerfile, `.env.example` (SFTP vars), optional UI, README and HLD fixes (stores, feed, essentials, five tools) |
