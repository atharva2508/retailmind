# RetailMind --- High-Level Design (HLD)

-   **Document status:** Proposed design. Revised after review: multiple
    stores, daily inventory feed over SFTP, essential goods.
-   **System:** RetailMind --- AI-Powered Retail Operations Assistant
-   **Primary objective:** Demonstrate practical AI application
    development using Java and Spring Boot.
-   **Initial deployment style:** Modular monolith
-   **Primary users:** Retail operations users and administrators

## 1. Purpose and scope

RetailMind allows users to ask natural-language questions about retail
products, inventory, promotions, sales summaries, and policy documents.
It combines deterministic backend operations with a large language model
(LLM).

The LLM interprets a request and produces a response, but the Java
application remains the authority for access control, business rules,
database operations, and validation.

### In scope

-   Natural-language chat endpoint.
-   Multiple stores, each with its own store code and its own inventory.
    A deployment with a single store works with no special mode.
-   Daily inventory files sent by each store at end of day and pulled
    from SFTP by the application.
-   Monitoring of essential daily-use goods (for example milk, bread,
    rice, eggs) using days of cover.
-   Store-scoped access: a store manager sees only their own store.
-   Read-only tools for product, inventory, promotion, and sales-summary
    queries.
-   AI-generated pricing/promotion recommendations routed to a human
    business review workflow.
-   Human approval, rejection, or modification of proposals by authorized
    business users, followed by deterministic backend validation.
-   RAG over approved policy and promotion documents.
-   Source attribution for document-grounded answers.
-   Authentication/authorization, validation, logging, metrics, and
    automated tests.
-   Docker-based development and deployment.

### Out of scope for the initial version

-   Autonomous AI writes to inventory, orders, product prices, or active
    promotions. Any approved change is executed by the Java backend only
    after an authorized human business decision.
-   Arbitrary model-generated SQL execution.
-   Model training or fine-tuning.
-   Multi-agent orchestration.
-   Microservices and Kafka as mandatory initial infrastructure.
-   Production use with real customer/payment data.
-   Manual inventory upload endpoint. SFTP is the only inventory source.
-   Demand forecasting, daily stock history and trend analysis.
-   Automatic reordering or purchase orders. The system reports only.

## 2. Requirements

### Functional requirements

1.  A user can submit a natural-language question.
2.  The system determines whether to answer using a business tool,
    document retrieval, or both.
3.  Business tools query approved services and return structured data.
4.  RAG retrieves relevant chunks from approved documents.
5.  The final answer includes source information where applicable.
6.  The system states when evidence is insufficient.
7.  Administrators can upload supported documents for ingestion.
8.  The system records request IDs, tool execution outcomes, latency,
    and model usage without logging secrets.
9.  Each store's daily inventory file is imported, validated, and
    applied. Bad files are rejected whole and reported.
10. Every stock answer states when the store's data was last updated
    ("as of") and warns when a store has not sent its latest file.
11. The system identifies essential goods at risk of running out at a
    store, using deterministic days-of-cover logic in Java.
12. A store manager can only read data for their own store.

### Non-functional requirements

-   **Security:** authenticate users and authorize operations
    server-side.
-   **Reliability:** handle LLM provider errors, timeouts, invalid
    outputs, and database errors gracefully.
-   **Correctness:** use Java business logic and database results as the
    source of truth.
-   **Maintainability:** keep AI orchestration separate from domain
    logic.
-   **Observability:** measure request latency, tool outcomes, token
    usage, and failures.
-   **Cost control:** limit input/output tokens, rate-limit requests,
    and monitor model usage.
-   **Testability:** allow business logic to be tested without invoking
    a live LLM.
-   **Privacy:** use synthetic data for public demos and avoid storing
    unnecessary personal information.

No throughput, latency, availability, or accuracy SLA is claimed at this
stage. Establish baselines through testing before setting targets.

## 3. Architecture

``` mermaid
flowchart TB
    CLIENT[React / Angular UI or API client]
    API[Spring Boot REST API]
    AUTH[Authentication, authorization, validation, rate limits]
    CHAT[Chat / AI orchestration service]
    MODEL[Hosted LLM provider]
    TOOL[Allowlisted tool registry]
    DOMAIN[Product, inventory, promotion, order services]
    DB[(PostgreSQL)]
    RAG[RAG retrieval service]
    VECTOR[(pgvector)]
    UPLOAD[Admin document upload]
    INGEST[Text extraction, chunking, embedding]
    OBS[Logs, metrics, health checks]
    STORES[Store SFTP: daily inventory files]
    FEED[Inventory feed import]

    CLIENT --> API
    API --> AUTH
    AUTH --> CHAT
    CHAT <--> MODEL
    CHAT --> TOOL
    TOOL --> DOMAIN
    DOMAIN --> DB
    CHAT --> RAG
    RAG --> VECTOR
    UPLOAD --> INGEST
    INGEST --> VECTOR
    STORES --> FEED
    FEED --> DOMAIN
    API --> OBS
    CHAT --> OBS
```

### Architectural decision: modular monolith first

Use one deployable Spring Boot application with module boundaries. This
reduces operational complexity and keeps the project focused on AI
application patterns. Separate services only if scaling, ownership, or
deployment requirements later justify the additional complexity.

## 4. Component responsibilities

### 4.1 REST API layer

Responsibilities: - Accept and validate chat requests. - Apply
authentication and request limits. - Create or continue a
conversation. - Return a stable response DTO and request ID. - Map known
failures to safe HTTP error responses.

Proposed endpoint: `POST /api/chat`.

### 4.2 AI orchestration service

Responsibilities: - Build prompts from trusted system instructions and
relevant user context. - Invoke the configured chat model. - Coordinate
tool calls and document retrieval. - Apply limits to tool-call count,
conversation context, and output size. - Verify tool results and format
the final response. - Include source metadata when evidence was used.

The orchestration service must not implement core pricing or inventory
business rules itself.

### 4.3 Tool registry

Initial allowlisted tools (four in the table, the fifth described below
it):

  ---------------------------------------------------------------------------------------
  Tool                                    Purpose                 Access
  --------------------------------------- ----------------------- -----------------------
  `getProductDetails(productId)`          Retrieve product        Read-only
                                          information             

  `getLowStockProducts()`                 Retrieve inventory      Read-only
                                          below threshold         

  `getActivePromotions(productIds)`       Retrieve active         Read-only
                                          promotion records       

  `getSalesSummary(startDate, endDate)`   Retrieve an approved    Read-only
                                          sales summary           
  ---------------------------------------------------------------------------------------

A fifth tool, `getEssentialsAtRisk(storeId?)`, returns essential goods
whose days of cover are below the configured limit, or that are out of
stock. It is read-only like the others.

Stock-related tools take an optional `storeId`. Every stock result
includes `asOf` (time of the store's last applied file) and a `stale`
flag. Low-stock results list essential goods first. The store scope
comes from the authenticated user, never from the model: for a store
manager a model-supplied `storeId` is ignored or checked against the
allowed stores.

Tool arguments must be schema-validated. Domain services must enforce
authorization and validate identifiers and date ranges. Do not provide a
general-purpose `executeSql` tool.

### 4.4 Domain services

-   **Product service:** product and SKU lookup.
-   **Inventory service:** stock quantity and reorder threshold queries.
-   **Promotion service:** promotion eligibility and deterministic
    pricing rules.
-   **Order service:** order summaries and status queries, subject to
    user permissions.
-   **Store service:** stores, store codes, and which users may see
    which stores. `resolveStore(storeId?)`: an explicit id returns that
    store. No id and exactly one active store returns that store. No id
    and several stores returns all stores, grouped by store. Nothing is
    guessed.
-   **Essentials logic (inventory service):** days of cover = quantity on
    hand divided by average daily units sold at the store over the last
    7 days, counting orders in status PLACED, PAID, or FULFILLED. An
    essential product is at risk when days of cover is below the limit
    (default 2 days) or quantity is 0. With no sales in the window and
    quantity above 0, it is not flagged by cover.

The initial release must not execute writes through AI tool calls.
For pricing and promotions, AI is strictly advisory: it may prepare a
proposal with evidence and rationale, but only an authorized business
team member can make the final decision. The model cannot approve its own
proposal or call an operation that activates a price/promotion change.

After a human approves a proposal, the Java backend must independently
check the reviewer's role, revalidate the latest data and deterministic
business rules, persist the decision, and apply the approved change in a
controlled transaction where practical. Rejections and edits must also be
recorded. Use idempotency and optimistic version checks to prevent
duplicate or stale changes.

### 4.5 RAG service

Responsibilities: - Search only approved documents. - Retrieve
semantically relevant chunks. - Apply metadata filters where
appropriate. - Return source IDs, document names, and chunk
references. - Provide evidence to the LLM without allowing document
content to alter system policy.

### 4.6 Document ingestion pipeline

1.  Authenticate and authorize the administrator.
2.  Validate file type, size, and content.
3.  Extract text from supported formats.
4.  Normalize and chunk text with appropriate overlap.
5.  Generate embeddings using the selected embedding model.
6.  Store chunk text, metadata, and vectors.
7.  Record ingestion status and errors.

Ingestion should be idempotent where practical, for example by tracking
a document checksum and version.

### 4.6a Inventory feed import

Each store sends one inventory file per day at end of day. The
application pulls files from SFTP. Stores never write to the database.

File name: `<store_code>_<yyyyMMdd>.csv`. Header row required. Columns:
`store_code,sku,quantity_on_hand,as_of`. One file is a full snapshot for
one store. Reorder thresholds stay in the application, not in the file.

Pipeline:

1.  Poll the SFTP `inbound/` directory on a configured interval. Ignore
    names that do not match the pattern.
2.  Enforce a size limit (default 5 MB). Download to a temporary file.
3.  Compute a SHA-256 checksum. If the same store and checksum were
    already imported, record `SKIPPED_DUPLICATE` and stop.
4.  Parse strictly. Reject the whole file if any row has: a store code
    that differs from the file name, or an unknown or inactive store; an
    unknown SKU or a SKU outside `[A-Za-z0-9._-]{1,64}`; a quantity that
    is not a non-negative whole number; an unparseable or future `as_of`;
    a duplicate SKU. Also reject a file whose `as_of` is older than the
    data already stored for that store.
5.  In one transaction, upsert all rows. SKUs missing from the file keep
    their old rows; the count is reported.
6.  Write an `inventory_imports` record (store, file name, checksum,
    `as_of`, status, row count, safe error summary). Move the file to
    `processed/` or `failed/`.

A store with no applied file for the previous business day after a
configured cutoff time (default 06:00 IST) is marked `stale`. Stale status
appears in tool results, in chat answers, and in an admin view.

SFTP security: key authentication only; strict host-key checking against a
pinned known-hosts file; no password auth and no trust-all. Connection
settings come from environment variables and are never logged. The
application touches only its fixed directories and validates file names
before use. File content is parsed as data and never evaluated.

### 4.7 Persistence

Use PostgreSQL for relational data. Use pgvector in the same database
for embedding storage and similarity search in the initial design. Keep
a logical boundary between business tables and document/vector tables.

Suggested entities: - `stores` - `products` - `inventory` (per store) -
`promotions` - `promotion_products` - `orders` - `order_items` -
`inventory_imports` - `documents` - `document_chunks` -
`pricing_promotion_proposals` - `conversations` (optional) -
`chat_messages` (optional) - `audit_events` (for security-relevant
actions) - `user_store_access` (user to store)

Use Flyway or a similar migration tool to manage schema changes.

## 5. Main request flows

### 5.1 Business-data question

Example: "Which low-stock products have active promotions?"

1.  The client sends a chat request to the REST API.
2.  The API authenticates the user, validates the request, and creates a
    request ID.
3.  The orchestration service sends the request and available tool
    definitions to the LLM.
4.  The LLM requests an allowlisted tool call.
5.  The application validates the tool name and arguments.
6.  The corresponding Java domain service queries PostgreSQL.
7.  The tool result is returned to the orchestration layer and, when
    needed, to the LLM for a final summary.
8.  The API returns the answer and structured source metadata.

The model never receives database credentials. Tool calls are
application-mediated, not executed directly by the model.

### 5.2 Policy question using RAG

Example: "What is the refund policy for an order paid with a gift card?"

1.  The API validates the request and authenticates the user.
2.  The retrieval service embeds the question and searches relevant
    chunks.
3.  The service returns the top relevant chunks and source metadata.
4.  The LLM generates a response constrained to the supplied evidence.
5.  The response includes document references.
6.  If relevant evidence is not found, the system indicates that it
    cannot answer confidently from the available documents.

### 5.3 Combined question

For questions that need both policy and live data, the orchestration
service can invoke a read-only tool and retrieve document chunks. It
should limit the number of tool calls and return a partial/clarifying
response if required inputs are missing.

### 5.4 Daily inventory import

1.  A store drops its daily file in the SFTP `inbound/` directory.
2.  The feed poller picks it up, checks name and size, and computes the
    checksum.
3.  Duplicate files are skipped. Invalid files are rejected whole, moved to
    `failed/`, and recorded with a safe error summary.
4.  A valid file is applied in one transaction and recorded.
5.  Later chat and API answers for that store show the new `asOf`.

The model has no path to this flow. It cannot trigger, alter, or read raw
import files.

### 5.5 Essential goods question

Example: "Which daily-use items are about to run out at store S2?"

1.  The API authenticates the user and resolves the allowed store scope.
2.  The model calls `getEssentialsAtRisk`. Java computes days of cover.
3.  The answer lists products with days of cover and quantity, the store,
    and the `asOf` time, with a warning if the store is stale.

## 6. API contracts

### Chat request

`POST /api/chat`

``` json
{
  "conversationId": null,
  "message": "Which low-stock products have active promotions?"
}
```

### Chat response

``` json
{
  "requestId": "generated-request-id",
  "conversationId": "generated-conversation-id",
  "answer": "Example answer based on retrieved business data.",
  "sources": [
    {
      "type": "tool",
      "name": "getLowStockProducts",
      "reference": "inventory-query",
      "retrievedAt": "runtime-generated-timestamp"
    }
  ]
}
```

Tool sources for stock answers also carry the store and the `asOf` time.

These are illustrative contracts. The implementation should define
explicit DTOs, validate input lengths, generate real IDs and timestamps,
and avoid exposing internal stack traces or sensitive data.

### Other initial endpoints

  ----------------------------------------------------------------------------
  Method                  Endpoint                     Notes
  ----------------------- ---------------------------- -----------------------
  GET                     `/api/products`              Filtered product search

  GET                     `/api/products/{id}`         Product details

  GET                     `/api/inventory/low-stock`   Read-only low-stock
                                                       query

  GET                     `/api/promotions/active`     Read-only active
                                                       promotions

  POST                    `/api/admin/documents`       Authorized document
                                                       ingestion

  GET                     `/actuator/health`           Restrict operational
                                                       endpoints appropriately
  ----------------------------------------------------------------------------

### Endpoints added after review

  Method   Endpoint                               Notes
  -------- -------------------------------------- ---------------------------------
  GET      `/api/stores`                          Stores the user may see
  GET      `/api/inventory/essentials-at-risk`    Optional `storeId`
  GET      `/api/admin/inventory-imports`         Import status; admin only
  GET      `/api/admin/inventory-imports/stale`   Stores with a missing file; admin

Existing inventory, product, and promotion endpoints accept an optional
`storeId`. Pricing and promotion proposal endpoints are listed in
section 8. Until authentication is built, these endpoints are for local
use only.

## 7. Data design

### Relational data

-   **Store:** `id`, `store_code` (unique, used in feed files), `name`,
    `active`.
-   **Product:** `id`, `sku`, `name`, `category`, `price`, `cost`,
    `active`, `is_essential` (admin-maintained), `default_reorder_threshold`.
-   **Inventory:** key `(store_id, product_id)`, `quantity_on_hand`,
    `reorder_threshold`, `as_of`, `updated_at`. Latest values only.
-   **Inventory import:** `id`, `store_id`, `file_name`, `checksum`,
    `as_of`, `status` (RECEIVED, APPLIED, SKIPPED_DUPLICATE, REJECTED),
    `row_count`, `error_summary`, `created_at`. Unique on
    `(store_id, checksum)`.
-   **Promotion:** `id`, `name`, `type`, `start_at`, `end_at`, `active`,
    `rule_config`. A `promotion_products` link table says which products
    a promotion covers.
-   **Order:** `id`, `store_id`, `status`, `total_amount`, `created_at`.
-   **Order item:** `order_id`, `product_id`, `quantity`, `unit_price`.

Use constraints and indexes for frequently queried SKU, product, status,
date, and foreign-key columns. Keep money in suitable decimal types and
perform promotion calculations using deterministic Java logic and
appropriate decimal arithmetic.

### Vector data

Each document chunk should retain: - Stable chunk ID. - Document ID and
document version/checksum. - Chunk text. - Embedding vector. -
Page/section metadata where available. - Access-control metadata if
documents have different audiences.

The embedding model and vector dimensions must be consistent with the
configured pgvector column and index.

## 8. Human decision authority for pricing and promotions

### Core policy

**The business team owns the final pricing and promotion decision. The AI
must never autonomously set, approve, activate, or modify a product price
or promotion.** The AI provides decision support only.

### Proposal lifecycle

1. The AI retrieves current product, inventory, sales, promotion, and
   policy information using allowlisted read-only tools.
2. It generates a recommendation with a rationale, assumptions, and
   supporting source references where available.
3. The backend stores the recommendation as a proposal with status
   `PENDING_REVIEW`; it does not change active prices or promotions.
4. An authenticated user with the business-review permission can approve,
   edit, or reject the proposal.
5. For an approval, the backend rechecks the user's authorization,
   verifies the proposal has not already been decided, reloads current
   data, and validates configured margin, eligibility, date, and other
   domain rules.
6. The backend records the human decision and applies only the approved
   values. A rejected proposal remains unapplied. A modified proposal
   must be reviewed and approved in its final form.
7. An audit record captures the proposal, reviewer identity, decision,
   decision time, before/after values, and application result.

Suggested statuses: `PENDING_REVIEW`, `APPROVED`, `REJECTED`, `APPLIED`,
and `EXPIRED`. Keep proposal records separate from active product prices
and promotions. An AI tool must not be able to write reviewer identity,
set an approval status, or invoke the apply operation.

### Authorization boundary

Use a dedicated permission such as `PRICING_PROMOTION_REVIEW` for
business reviewers, with administrative access granted separately.
Enforce this permission in Spring Security and again in the service layer.
The approval endpoint must derive the reviewer identity from the
authenticated security context; it must not trust a user ID supplied in
the request body. The AI model's text, tool calls, or apparent
confirmation are never proof of approval.

### Proposed endpoints

- `POST /api/pricing-promotion-proposals` — create a recommendation
  proposal; no active business record is changed.
- `GET /api/pricing-promotion-proposals/{id}` — retrieve proposal and
  review status subject to authorization.
- `POST /api/pricing-promotion-proposals/{id}/decision` — authorized
  human approves, edits, or rejects a proposal.
- Applying an approved proposal should be a backend-controlled operation,
  not an AI-exposed tool. It must be idempotent and validate the proposal
  version and current business constraints.

### Required tests

- An AI tool call cannot activate or alter a price/promotion.
- A normal user cannot approve a proposal without the required permission.
- A reviewer cannot approve a proposal that is already rejected/applied
  or whose version is stale.
- Approval is attributed to the authenticated human reviewer.
- Business constraints are revalidated at approval time.
- Rejected, pending, or expired proposals never affect active pricing.

## 9. Security and AI risk controls

  -----------------------------------------------------------------------
  Risk                                Mitigation
  ----------------------------------- -----------------------------------
  AI makes final pricing/promotion    AI can create recommendations only;
  decision                            only authorized business users can
                                      approve, and Java enforces the
                                      approval workflow

  Unauthorized or forged approval    Derive reviewer from authenticated
                                      security context; enforce a dedicated
                                      permission in controller and service

  Stale or duplicate approval        Use proposal status/version checks,
                                      idempotency, and audit records

  Prompt injection in a user message  Treat retrieved text as untrusted
  or document                         data; keep policy instructions
                                      separate

  Unauthorized tool invocation        Allowlist tools and enforce access
                                      control in Java

  Arbitrary SQL or data exposure      No unrestricted SQL tool; use
                                      parameterized repositories and
                                      approved queries

  Hallucinated policy answer          Ground responses in retrieved
                                      evidence and return source
                                      references

  Sensitive data in logs              Redact secrets and avoid
                                      unnecessary personal data

  Excessive model cost                Rate limits, token limits, bounded
                                      context, usage metrics

  Provider outage or timeout          Timeouts, safe error handling,
                                      bounded retries where appropriate

  Malicious/oversized upload          File validation, size limits,
                                      content checks, restricted
                                      ingestion

  Duplicate ingestion                 Checksums/versioning and idempotent
                                      ingestion workflow
  -----------------------------------------------------------------------

Additional risks from the store and feed design:

-   **Malformed or malicious inventory file:** size limit, strict column
    and type checks, reject the whole file on any bad row, no evaluation of
    file content.
-   **Spoofed or compromised SFTP server:** key authentication, pinned host
    key, fixed directories, validated file names.
-   **One store reading another store's data:** store scope derived from
    the security context and enforced in services, including tools. The
    model cannot widen scope.
-   **Stale stock shown as current:** `asOf` on every stock answer, `stale`
    flag, missing-file check.
-   **Older file overwriting newer data:** reject files whose `as_of` is
    older than stored data.
-   **Wrong essentials risk from the model:** days of cover is computed in
    Java, not by the model.

Do not treat prompt instructions as a replacement for security controls.
Authorization must be enforced by the application and database access
layer.

## 10. Failure handling

-   **LLM timeout/provider error:** return a controlled error and
    request ID; do not return stack traces.
-   **Invalid tool arguments:** reject the call, record a safe
    diagnostic, and allow a bounded correction attempt if appropriate.
-   **No relevant RAG results:** return an insufficient-evidence
    response or ask a clarifying question.
-   **Database unavailable:** return a service error; do not fabricate
    results.
-   **Document ingestion failure:** persist failure status and a safe
    error summary so the administrator can retry.
-   **Rate limit exceeded:** return HTTP 429 with a suitable retry
    policy.
-   **Unsupported request:** explain the currently supported
    capabilities.
-   **Invalid inventory file:** reject the whole file, keep existing
    data, move the file to `failed/`, record a safe error summary.
-   **SFTP unreachable:** keep serving existing data marked with its
    `asOf`, retry on the next poll, raise a metric and log entry.
-   **Store file missing:** mark the store `stale`; never invent stock.

Retry only operations that are safe to repeat. Avoid retry loops for
model tool calls.

## 11. Observability and evaluation

Capture: - Request ID and endpoint. - Total request latency and
model-call latency. - Tool name, duration, success/failure, and
validation outcome. - Token usage and estimated model cost when provider
usage data is available. - RAG retrieval duration and source IDs. -
Provider and application error counts. - Inventory imports by outcome,
rejected files, and stores currently stale.

Do not log full prompts, documents, credentials, or sensitive user data
by default.

Create an evaluation set of 50--100 questions and track: -
Tool-selection accuracy. - Tool-argument validity. - Answer correctness
against known facts. - Retrieval relevance and source attribution. -
Groundedness and abstention when evidence is insufficient. - Latency and
cost.

Record the model/provider, prompt version, dataset version, and test
conditions so results can be reproduced. Do not claim an accuracy
percentage until it has been measured.

## 12. Deployment view

Initial deployment: - One Spring Boot application container. - One
PostgreSQL instance with pgvector enabled. - Hosted LLM and embedding
APIs accessed through secure outbound connections. - Optional frontend
container. - Secrets supplied through environment configuration or a
secret manager. - Health checks, logs, and metrics enabled.

Stores deliver inventory files to an SFTP location the application can
reach. Local development and tests use a local folder or a test SFTP
container feeding the same import pipeline.

For local development, Docker Compose can start the application
dependencies. Keep production secrets out of source control. Use
synthetic data in the public demo.

## 13. Key design decisions and trade-offs

  -----------------------------------------------------------------------
  Decision                Reason                  Trade-off
  ----------------------- ----------------------- -----------------------
  Java/Spring Boot        Builds on existing      AI library examples may
                          backend skills          be more common in
                                                  Python

  Modular monolith        Simpler to build, test, Less independent
                          and deploy              scaling than separate
                                                  services

  Hosted LLM initially    Faster path to a useful Usage cost and provider
                          AI feature              dependency

  PostgreSQL + pgvector   Relational and vector   Dedicated vector
                          data in one system      databases may suit
                                                  larger scale

  Tool calling for live   Keeps business logic    Requires carefully
  facts                   deterministic           designed and validated
                                                  tools

  RAG for policy          Answers can be grounded Quality depends on
  documents               in internal content     ingestion and retrieval

  Read-only tools         Reduces impact of       Cannot perform
  initially               unsafe AI actions       autonomous business
                                                  updates
  -----------------------------------------------------------------------

Additional decisions after review:

-   **Multi-store schema from the start:** one extra column and join now is
    cheaper than adding `store_id` to existing data later. One store is
    just one row in `stores`.
-   **SFTP pull for inventory:** stores keep their own systems and the
    application owns validation. Cost: data is up to a day old, so every
    answer carries `asOf`.
-   **Whole-file rejection:** a bad row fails the file, so partial and
    confusing stock states never exist.
-   **Days of cover for essentials:** a fixed threshold suits soap, not
    milk. Cost: needs sales history per store.
-   **Claude for chat, separate embedding model:** Anthropic offers no
    embeddings API, so RAG uses another model (local ONNX by default,
    swappable).

## 14. Future evolution

Only add these when the core application works and a clear need exists:

-   Add user-confirmed write workflows with audit trails and
    idempotency.
-   Add hybrid retrieval, reranking, and document versioning.
-   Add streaming responses and conversation management.
-   Add caching for safe, repeatable retrievals.
-   Extract modules into services if scaling or ownership justifies it.
-   Introduce Kafka for asynchronous workflows such as document
    ingestion if needed.
-   Add CI/CD and a repeatable cloud deployment.

Microservices and Kafka are not prerequisites for demonstrating AI
application development.

## 15. Implementation milestones

1.  Build retail schema (stores, per-store inventory), seed data, and
    REST endpoints.
2.  Build the inventory feed: SFTP import, validation, stale check, and
    essentials logic.
3.  Integrate an LLM (Claude) through Spring AI.
4.  Implement and test the five allowlisted read-only tools.
5.  Add authentication, authorization (including store scoping), and
    audit.
6.  Add the pricing/promotion proposal and approval workflow.
7.  Add document ingestion, embeddings, pgvector, and RAG.
8.  Add observability, AI safety tests, and evaluation.
9.  Add Docker, frontend, deployment, and project documentation.

## 16. Decisions and open items

Decided:

-   **Chat model:** Claude through Spring AI.
-   **Embeddings:** separate model, local ONNX (384 dimensions) by
    default, swappable. Changing it needs a migration and re-embedding.
-   **Versions:** Java 21, Spring Boot 4.1.0, Spring AI 2.0.0. Chosen from
    release notes; confirm with a real build.
-   **Conversation history:** persisted, bounded context window.
-   **Documents:** PDF, DOCX, TXT, MD; 10 MB maximum.
-   **Document audience:** `audience` on each chunk, `ALL` or `ADMIN`.
-   **Stores:** many stores, identified in files by `store_code`.
-   **Inventory feed:** daily full-snapshot CSV, pulled from SFTP.
-   **Essentials:** admin-maintained flag; at risk when days of cover is
    below 2 (7-day sales window).
-   **Store manager access:** own store only; other users see all stores.

Open:

-   Real `store_code` examples and the usual file arrival time. Cutoff
    defaults to 06:00 IST.
-   Evaluation thresholds, set only after a baseline run.

These decisions should be recorded in configuration and project
documentation as they are implemented.
