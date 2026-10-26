# RetailMind --- AI-Powered Retail Operations Assistant

RetailMind is a portfolio project that combines a Java backend with AI
application patterns. It lets retail users ask natural-language
questions about inventory, products, promotions, and business policies.
The application uses trusted backend tools for live business data and
Retrieval-Augmented Generation (RAG) for document-based answers.

> **Project status:** Architecture and implementation plan. Update the
> status and feature list as features are implemented; do not describe
> planned functionality as already delivered.
>
> Built so far: a Stage 1 scaffold (Maven project, Docker Compose,
> schema, seed data). The schema is being revised for multiple stores and
> the Maven build has not yet been verified. Nothing else is implemented.
> See `docs/design-notes.md` for the staged plan.

## Goals

-   Demonstrate practical AI application development using Java and
    Spring Boot.
-   Answer questions using real application data rather than relying on
    the model's memory.
-   Use LLM tool calling to invoke controlled backend operations.
-   Use RAG to retrieve relevant passages from retail policy and
    promotion documents.
-   Apply backend security, validation, observability, testing, and
    deployment practices.
-   Produce a project that can be explained clearly in Java backend and
    AI application interviews.

## Example questions

-   Which products are below their stock threshold?
-   Which low-stock products have active promotions?
-   What are the eligibility rules for a buy-one-get-one-free offer?
-   Explain the refund policy for an order cancelled after using a gift
    card.
-   Which products had the highest sales in a selected period?
-   Which daily-use items (milk, bread, rice) are about to run out at
    store S2?
-   Which stores have not sent today's inventory file?

The application should clearly distinguish live database facts from
policy-document information. If evidence is missing, it should say so
rather than inventing an answer.

Inventory comes from files that each store sends at end of day, so stock
answers are "as of" the last file, not live. Every stock answer states
its `asOf` time and warns when a store's file is missing. There can be
many stores; a store manager sees only their own store. With a single
store, everything works the same with one row in `stores`.

## Proposed technology stack

  -----------------------------------------------------------------------
  Area                                Technology
  ----------------------------------- -----------------------------------
  Language                            Java 21 (or the Java version
                                      supported by the selected Spring
                                      Boot release)

  Backend                             Spring Boot, Spring Web

  AI integration                      Spring AI with a supported hosted
                                      chat model

  Persistence                         Spring Data JPA, PostgreSQL

  Semantic search                     pgvector

  Security                            Spring Security; JWT if required
                                      for the chosen authentication flow

  Validation                          Jakarta Bean Validation

  Testing                             JUnit 5, Mockito, Spring Boot Test,
                                      Testcontainers (recommended)

  API documentation                   Springdoc OpenAPI

  Operations                          Spring Boot Actuator, Micrometer

  Packaging                           Maven, Docker Compose

  Frontend                            React or Angular (optional; backend
                                      APIs can be developed first)
  -----------------------------------------------------------------------

Chat model: Claude through Spring AI's Anthropic integration. Anthropic
has no embeddings API, so RAG uses a separate embedding model (local ONNX
by default, swappable). Inventory files are pulled over SFTP (client
library chosen when the feed is built). Planned versions: Java 21,
Spring Boot 4.1.0, Spring AI 2.0.0, taken from release notes and still
to be confirmed by a real build.

Pin compatible dependency versions when creating the project. Do not
copy version numbers from an old tutorial without checking
compatibility.

## Architecture overview

RetailMind starts as a **modular monolith**. One Spring Boot application
contains clearly separated modules, which keeps local development and
deployment manageable while preserving boundaries that can be extracted
later if there is a genuine need.

``` mermaid
flowchart TD
    U[Web UI / API Client] --> API[Spring Boot REST API]
    API --> SEC[Authentication, Authorization, Validation]
    SEC --> ORCH[AI Orchestration Service]
    ORCH --> LLM[Hosted LLM]
    ORCH --> TOOLS[Allowlisted Business Tools]
    TOOLS --> DOMAIN[Product, Inventory, Promotion, Order Services]
    DOMAIN --> DB[(PostgreSQL)]
    ORCH --> RETRIEVER[RAG Retrieval Service]
    RETRIEVER --> VDB[(PostgreSQL + pgvector)]
    DOCS[Policy and Promotion Documents] --> INGEST[Document Ingestion Pipeline]
    SFTP[Store SFTP: daily inventory files] --> FEED[Inventory Feed Import]
    FEED --> DOMAIN
    INGEST --> VDB
    API --> OBS[Logs, Metrics, Health]
```

### Important design rule

The LLM may propose a tool call, but the application validates and
executes it. The model must not receive unrestricted database
credentials, arbitrary SQL execution, or direct write access to business
records.

### Non-negotiable rule: business team owns pricing and promotion decisions

**AI is advisory only. It can analyze retail data and suggest prices or
promotional strategies, but it cannot make the final decision or apply a
price/promotion change.** The authorized business team is the sole
decision-maker.

- AI recommendations must include a rationale and the supporting data or
  policy sources where available.
- A business user with the appropriate role must explicitly approve,
  modify, or reject each proposal.
- The Java backend must enforce approval permissions and deterministic
  business constraints; a prompt or model response is never an approval.
- No price or promotion change can be applied until approval is recorded
  and the backend revalidates the proposed change.
- Record the recommendation, reviewer, decision, timestamps, and applied
  change in an audit trail.
- AI tools remain read-only for business records. The model cannot invoke
  an approval or apply-change operation.

## Core modules

-   **API layer:** chat endpoint, document-ingestion endpoint
    (admin-only), request validation, consistent error responses.
-   **AI orchestration:** prompt construction, conversation context,
    model invocation, tool-call coordination, output handling.
-   **Business tools:** five allowlisted read-only operations:
    `getProductDetails`, `getLowStockProducts`, `getActivePromotions`,
    `getSalesSummary`, and `getEssentialsAtRisk`.
-   **Domain services:** deterministic product, inventory, promotion,
    and order logic.
-   **Knowledge/RAG:** document extraction, chunking, embeddings,
    similarity search, and source references.
-   **Stores and inventory feed:** stores, store-scoped access, daily SFTP
    file import with strict validation, stale-store detection.
-   **Persistence:** relational entities and vector records.
-   **Security and operations:** authentication, authorization, rate
    limits, request IDs, metrics, and audit events.

## Initial API proposal

  ----------------------------------------------------------------------------
  Method                  Endpoint                     Purpose
  ----------------------- ---------------------------- -----------------------
  POST                    `/api/chat`                  Ask a question and
                                                       receive an
                                                       evidence-grounded
                                                       answer

  GET                     `/api/products`              List/search products

  GET                     `/api/products/{id}`         Retrieve product
                                                       details

  GET                     `/api/inventory/low-stock`   Find products below
                                                       their configured
                                                       threshold

  GET                     `/api/promotions/active`     Retrieve active
                                                       promotions

  POST                    `/api/pricing-promotion-proposals` Create a
                                                       suggestion for
                                                       business review

  GET                     `/api/pricing-promotion-proposals/{id}` View
                                                       proposal and status

  POST                    `/api/pricing-promotion-proposals/{id}/decision`
                                                       Authorized business
                                                       user approves/rejects
                                                       (human decision only)

  POST                    `/api/admin/documents`       Upload a policy
                                                       document for ingestion;
                                                       admin-only

  GET                     `/actuator/health`           Application health
                                                       (restrict exposure in
                                                       production)
  ----------------------------------------------------------------------------

Additional endpoints:

-   `GET /api/stores`: stores the user may see.
-   `GET /api/inventory/essentials-at-risk`: essential goods at risk
    (optional `storeId`).
-   `GET /api/admin/inventory-imports`: import status (admin only).
-   `GET /api/admin/inventory-imports/stale`: stores missing a file
    (admin only).

Inventory, product, and promotion endpoints accept an optional
`storeId`. With one active store and no `storeId`, that store is used.
With several stores and no `storeId`, results are grouped by store.

Example chat request:

``` json
{
  "conversationId": "optional-existing-conversation-id",
  "message": "Which low-stock products have active promotions?"
}
```

Example response shape (illustrative only):

``` json
{
  "answer": "Two products match the request.",
  "sources": [
    {
      "type": "tool",
      "name": "getLowStockProducts",
      "retrievedAt": "2026-01-01T10:00:00Z"
    }
  ],
  "conversationId": "conversation-id",
  "requestId": "request-id"
}
```

Use actual runtime timestamps and IDs in the implementation. Do not
hard-code the illustrative values.

## Data model

Suggested relational tables:

-   `stores`: store ID, store code (used in feed files), name, active
    flag.
-   `products`: product ID, SKU, name, category, price, cost, active
    flag, essential flag, default reorder threshold.
-   `inventory`: store ID and product ID, quantity on hand, reorder
    threshold, as-of time, updated timestamp.
-   `inventory_imports`: store, file name, checksum, as-of time, status,
    row count, error summary.
-   `promotions`: promotion ID, name, type, start/end timestamps, active
    status, rule configuration.
-   `promotion_products`: which products a promotion covers.
-   `orders`: order ID, store ID, status, total, created timestamp.
-   `order_items`: order ID, product ID, quantity, unit price.
-   `documents`: document ID, filename, checksum, ingestion status,
    created timestamp.
-   `document_chunks`: chunk ID, document ID, chunk text, metadata,
    embedding vector.
-   `conversations` and `chat_messages` (optional): conversation
    metadata and message history.

Use foreign keys, indexes for frequent filters, and database migrations
(for example, Flyway). Promotion calculations should be deterministic
Java business logic, not free-form model-generated arithmetic.

### Human approval workflow for pricing and promotions

Use a separate proposal/approval lifecycle rather than letting the AI
write directly to the active `promotions` or product-price records.

Suggested `pricing_promotion_proposals` fields:

- `id`, `proposal_type` (`PRICE_CHANGE` or `PROMOTION`), `product_id`
  or target SKU set, proposed values/rules, and AI-generated rationale.
- Supporting data/source references, creator type (`AI_SUGGESTION`),
  status, created timestamp, and expiry where relevant.
- Reviewer user ID, review comment, decision timestamp, and applied
  timestamp.

Suggested statuses: `PENDING_REVIEW`, `APPROVED`, `REJECTED`,
`APPLIED`, and `EXPIRED`.

Workflow:

1. AI reads approved business data through allowlisted read-only tools.
2. AI creates a **suggestion/proposal**, not an active price or promotion.
3. The business team reviews the rationale, data, margin impact, and
   applicable policy.
4. An authorized business user approves, edits, or rejects the proposal.
5. On approval, the Java backend revalidates permissions and all domain
   rules, records the decision, and applies the approved change.
6. Audit records capture who decided and what changed.

An approval endpoint must accept an authenticated human user's decision;
it must never accept an AI-generated decision as approval. Consider
optimistic locking/version checks so a proposal cannot overwrite newer
pricing or promotion data.

## Inventory feed and essentials

Each store sends one CSV file per day at end of day. The application pulls
it from SFTP.

-   File name: `<store_code>_<yyyyMMdd>.csv`.
-   Columns: `store_code,sku,quantity_on_hand,as_of`. One full snapshot per
    store. Reorder thresholds stay in the application.
-   Each file is checksummed (duplicates are skipped), validated strictly,
    and applied in one transaction. Any bad row rejects the whole file.
    A file older than the stored data is rejected.
-   A store with no file for the previous business day after the cutoff
    (default 06:00 IST) is marked stale.
-   Essential goods are flagged by an admin. A product is at risk when its
    days of cover (stock divided by average daily sales over the last 7
    days) is below 2, or stock is 0. This is computed in Java, not by the
    model.
-   SFTP uses key authentication and a pinned host key. Credentials come
    from environment variables.

The model cannot trigger, change, or read raw feed files.

## RAG ingestion and query flow

1.  An authorized administrator uploads a policy or promotion document.
2.  The backend validates the file type and size, extracts text, and
    splits it into chunks with useful overlap.
3.  The embedding model converts each chunk into a vector.
4.  Text, metadata, and vectors are stored in PostgreSQL/pgvector.
5.  When a user asks a policy question, the backend embeds the query and
    retrieves relevant chunks.
6.  The LLM generates an answer grounded in the retrieved passages.
7.  The API returns source references so the UI can show where the
    answer came from.

Treat uploaded documents as untrusted data. Document text must never
override system instructions or grant permissions.

## Safety and reliability

-   Allowlist tools and validate every tool argument.
-   Enforce authorization in Java services, not in prompts alone.
-   Keep the initial version read-only for AI-triggered tools.
-   Never expose secrets, credentials, or unrestricted SQL access to the
    model.
-   Set request timeouts, token/output limits, and rate limits.
-   Handle model-provider errors and malformed structured output.
-   Separate system instructions from retrieved document content.
-   Return citations or source metadata for document-grounded answers.
-   Log request IDs, tool names, latency, and token usage; avoid logging
    secrets or unnecessary personal data.
-   Restrict Actuator endpoints and document-upload functionality.
-   Use synthetic data in public demos.
-   Enforce store scope in Java services: a store manager cannot read
    another store's data, and the model cannot widen scope.
-   Treat inventory files as untrusted input: size limit, strict
    parsing, whole-file rejection, SFTP key auth and pinned host key.
-   Show `asOf` and a stale warning on every stock answer.

## Testing strategy

1.  **Unit tests:** domain rules, stock thresholds, promotion
    eligibility, argument validation.
2.  **Integration tests:** repositories and REST endpoints using
    PostgreSQL/Testcontainers where practical.
3.  **AI tool tests:** verify correct tool selection and ensure
    unauthorized or invalid tool calls are rejected.
4.  **RAG tests:** verify relevant passages are retrieved and returned
    source references are correct.
5.  **Evaluation set:** maintain 50--100 representative questions,
    expected facts, expected tools, and unacceptable failure cases.
6.  **Security tests:** prompt injection, malicious documents,
    access-control bypass attempts, oversized uploads.
7.  **Operational tests:** model timeout, provider error, rate limiting,
    and database unavailability.
8.  **Inventory feed tests:** valid file, duplicate file, unknown store,
    unknown SKU, negative quantity, future or older `as_of`, oversized
    file, SFTP unavailable, stale-store detection, days-of-cover
    calculation, single-store and multi-store resolution, store-manager
    scope.

Track tool-selection accuracy, answer correctness, retrieval relevance,
groundedness, latency, and cost. Report measured results only; do not
invent performance or accuracy claims.

## Local development (planned)

Prerequisites: - JDK 21 (or the version selected for the project) -
Maven - Docker and Docker Compose - A supported LLM provider API key,
unless using a local model

Suggested setup sequence:

1.  Create the Spring Boot project and configure PostgreSQL.
2.  Add schema migrations (stores, per-store inventory) and synthetic
    seed data.
3.  Implement and test the standard retail REST endpoints.
4.  Add the inventory feed: SFTP import, validation, stale check,
    essentials logic.
5.  Add Spring AI (Claude) and a simple `/api/chat` endpoint.
6.  Add the five allowlisted read-only tools.
7.  Add security, roles, store scoping, and audit.
8.  Add the pricing/promotion proposal workflow.
9.  Add document ingestion and RAG with pgvector.
10. Add observability, evaluation tests, Docker, and a demo UI.

Example environment variables (names are suggestions; align them with
your actual configuration):

``` bash
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/retailmind
SPRING_DATASOURCE_USERNAME=retailmind
SPRING_DATASOURCE_PASSWORD=change-me
AI_PROVIDER_API_KEY=replace-with-your-key
SFTP_HOST=sftp.example.com
SFTP_PORT=22
SFTP_USER=retailmind
SFTP_PRIVATE_KEY_PATH=/secrets/sftp_key
SFTP_KNOWN_HOSTS=/secrets/known_hosts
```

Do not commit `.env` files, API keys, passwords, or production data.
Provide an `.env.example` containing placeholders only.

## Repository structure (suggested)

``` text
retailmind/
├── README.md
├── HLD.md
├── pom.xml
├── Dockerfile
├── compose.yaml
├── src/
│   ├── main/java/com/example/retailmind/
│   │   ├── api/
│   │   ├── security/
│   │   ├── ai/
│   │   │   ├── orchestration/
│   │   │   ├── tools/
│   │   │   └── rag/
│   │   ├── product/
│   │   ├── inventory/
│   │   ├── promotion/
│   │   ├── order/
│   │   ├── store/
│   │   ├── inventoryfeed/
│   │   ├── proposal/
│   │   ├── audit/
│   │   ├── persistence/
│   │   └── observability/
│   └── test/
└── docs/
    ├── design-notes.md
    └── evaluation-set.md
```

Adapt package names and folders to the actual codebase rather than
creating empty packages solely to match this diagram.

## Definition of done

-   AI-generated pricing/promotion output is stored only as a proposal;
    it never changes active prices or promotions.
-   Only an authenticated, authorized business user can approve, modify,
    or reject a proposal; the backend revalidates rules before applying it.
-   Every proposal decision and applied change is auditable.
-   A user can ask an inventory or promotion question and receive data
    returned by trusted tools.
-   A policy question can be answered using retrieved document passages
    with source references.
-   Invalid and unauthorized tool requests are rejected.
-   The application handles model timeouts and errors without exposing
    secrets.
-   Unit and integration tests cover important business and safety
    paths.
-   Docker-based local setup and a clear README are available.
-   The evaluation set and its measured results are documented.
-   Each store's daily inventory file is imported with validation;
    invalid files are rejected whole and reported.
-   Stock answers show their `asOf` time and flag stale stores.
-   Essential goods at risk are reported using days of cover.
-   A store manager can read only their own store's data.

## Roadmap

-   **Milestone 1:** Spring Boot retail APIs and PostgreSQL schema
    (stores, per-store inventory).
-   **Milestone 2:** Daily inventory feed over SFTP and essentials
    logic.
-   **Milestone 3:** LLM (Claude) integration and chat endpoint.
-   **Milestone 4:** Validated tool calling over business services.
-   **Milestone 5:** Security, store scoping, and audit.
-   **Milestone 6:** Pricing/promotion proposal and approval workflow.
-   **Milestone 7:** Document ingestion, embeddings, and RAG.
-   **Milestone 8:** Evaluation and observability.
-   **Milestone 9:** Docker deployment, UI, demo, and interview-ready
    documentation.

## Resume positioning (after implementation)

> Built an AI-powered retail operations assistant using Java, Spring
> Boot, Spring AI, and PostgreSQL, combining validated tool calling for
> live inventory and promotion data with RAG-based answers over business
> documents. Added authorization, source attribution, automated
> evaluation, and resilience handling.

Only use this statement after the corresponding features are implemented
and tested. Add quantified results only when supported by measurements.
