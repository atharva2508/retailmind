-- Rules the database enforces by itself, so a bug or a direct SQL session cannot store nonsense.
-- Runs on every start, after Hibernate has created or updated the tables. Every statement is repeatable.
-- Statement separator is ";;" (see spring.sql.init.separator) because the trigger function contains ";".
-- Constraints are NOT VALID: they are enforced for every new or changed row, but rows that already
-- exist are not re-checked, so an old database with odd data still starts.
-- ponytail: stopgap until Flyway. Move this file into a migration when Flyway lands.

-- A SKU is one product, whatever its letter case. Fails at start if case twins already exist: merge them first.
create unique index if not exists ux_products_sku_lower on products (lower(sku));;

alter table products drop constraint if exists ck_products_price;;
alter table products add constraint ck_products_price check (price > 0) not valid;;

alter table stock_snapshots drop constraint if exists ck_snapshots_quantities;;
alter table stock_snapshots add constraint ck_snapshots_quantities
    check (bal >= 0 and aud >= 0 and rac >= 0 and held >= 0) not valid;;
alter table stock_snapshots drop constraint if exists ck_snapshots_type;;
alter table stock_snapshots add constraint ck_snapshots_type check (snapshot_type in ('SOD', 'EOD')) not valid;;

alter table order_lines drop constraint if exists ck_order_lines_quantities;;
alter table order_lines add constraint ck_order_lines_quantities
    check (qty_ordered >= 0 and qty_confirmed >= 0 and qty_cancelled >= 0 and qty_shipped >= 0) not valid;;

alter table return_lines drop constraint if exists ck_return_lines_qty;;
alter table return_lines add constraint ck_return_lines_qty check (qty >= 0) not valid;;

alter table pricing_promotion_proposals drop constraint if exists ck_proposals_status;;
alter table pricing_promotion_proposals add constraint ck_proposals_status
    check (status in ('SUGGESTED', 'PENDING_REVIEW', 'APPROVED', 'REJECTED', 'APPLIED')) not valid;;
alter table pricing_promotion_proposals drop constraint if exists ck_proposals_type;;
alter table pricing_promotion_proposals add constraint ck_proposals_type
    check (proposal_type in ('DISCOUNT', 'PROMOTION', 'CLEARANCE')) not valid;;

alter table app_users drop constraint if exists ck_users_role;;
alter table app_users add constraint ck_users_role
    check (role in ('ROLE_ADMIN', 'ROLE_STORE_MANAGER', 'ROLE_REVIEWER', 'ROLE_USER')) not valid;;

-- A store id must point at a real store. Null stays allowed (user with no store yet).
alter table app_users drop constraint if exists fk_users_store;;
alter table app_users add constraint fk_users_store foreign key (store_id) references stores (id) not valid;;
alter table pricing_promotion_proposals drop constraint if exists fk_proposals_store;;
alter table pricing_promotion_proposals add constraint fk_proposals_store foreign key (store_id) references stores (id) not valid;;
alter table pricing_promotion_proposals drop constraint if exists fk_proposals_product;;
alter table pricing_promotion_proposals add constraint fk_proposals_product foreign key (target_product_id) references products (id) not valid;;
alter table inventory_imports drop constraint if exists fk_imports_store;;
alter table inventory_imports add constraint fk_imports_store foreign key (store_id) references stores (id) not valid;;

-- The audit trail is append-only, also for anyone with a SQL prompt.
create or replace function audit_events_append_only() returns trigger as $$
begin
    raise exception 'audit_events is append-only';
end;
$$ language plpgsql;;
drop trigger if exists trg_audit_events_append_only on audit_events;;
create trigger trg_audit_events_append_only before update or delete on audit_events
    for each row execute function audit_events_append_only();;
