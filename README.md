# RetailMind

RetailMind is a Spring Boot retail intelligence service aligned to the design notes in `design-notes.md`.

## Current scope

- JWT authentication with seeded admin, store manager, and user roles
- Protected retail endpoints for health, low stock, product detail, stock snapshots, and fulfillment issues
- Inventory-domain seed data for stock snapshots, order lines, and return lines
- Deterministic reconciliation and fulfillment calculations aligned with the design-note stock bucket model
- Pricing and promotion proposal workflow with review and approval states
- Automated discount suggestions driven by inventory risk and low-stock signals
- Executive dashboard summary for operational-level retail health
- Immutable audit trail for retail actions and import decisions
- CORS + request-rate protection in the API layer
- Integration tests covering auth, protected access, retail intelligence endpoints, proposal lifecycle, and audit traceability

## Roles

- `ROLE_ADMIN`
- `ROLE_STORE_MANAGER`
- `ROLE_REVIEWER`
- `ROLE_USER`

## Configuration (required)

Nothing has a default secret. Set these as environment variables (or in your IDE run configuration). See `.env.example`.

- `DB_PASSWORD`, optional `DB_USER`, `DB_URL`
- `JWT_SECRET`: random, at least 32 bytes (the app refuses to start otherwise)
- Dev demo data and users: `SEED_ENABLED=true` plus `SEED_ADMIN_PASSWORD`, `SEED_MANAGER_PASSWORD`, `SEED_REVIEWER_PASSWORD`, `SEED_USER_PASSWORD`. Leave `SEED_ENABLED` unset anywhere shared.

Public registration creates `ROLE_USER` only.

## Run locally

```bash
docker compose up -d    # needs DB_PASSWORD in .env; Postgres binds to 127.0.0.1 only
mvn spring-boot:run
mvn verify              # tests use an in-memory H2 with test-only settings
```

## Example calls

### Health

```bash
curl http://localhost:8080/api/health
```

### Login

```bash
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"<your SEED_ADMIN_PASSWORD>"}'
```

### Low stock

```bash
ACCESS_TOKEN=<paste the accessToken from the login response>
curl "http://localhost:8080/api/products/low-stock?storeId=1" \
  -H "Authorization: Bearer <accessToken>"
```

### Product details

```bash
curl "http://localhost:8080/api/products/1/details?storeId=1" \
  -H "Authorization: Bearer <accessToken>"
```

### Reconciliation

```bash
curl "http://localhost:8080/api/stores/1/reconciliation?date=2026-11-05" \
  -H "Authorization: Bearer <accessToken>"
```

### Discount suggestions

```bash
curl "http://localhost:8080/api/proposals/suggestions?storeId=1" \
  -H "Authorization: Bearer $ACCESS_TOKEN"
```

### Dashboard summary

```bash
curl "http://localhost:8080/api/dashboard/summary?storeId=1" \
  -H "Authorization: Bearer $ACCESS_TOKEN"
```

### Audit trail

```bash
curl "http://localhost:8080/api/audit/events" \
  -H "Authorization: Bearer $ACCESS_TOKEN"
```

### Create pricing proposal

```bash
curl -X POST http://localhost:8080/api/proposals \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"proposalType":"DISCOUNT","targetSku":"SKU-2002","creatorType":"STORE_MANAGER","rationale":"Low stock risk on USB-C cable","proposedValues":"{\"discountPercent\":10,\"durationDays\":7}","sourceRefs":"inventory.low-stock.store.1"}'
```

## Test

```bash
mvn test
```
