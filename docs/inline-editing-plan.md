# Plan: Add Inline Entity Editing to LMS

## Context

Currently, all data changes in the LMS go through named activities (originate, record-payment, retract, etc.). There is no way to directly edit individual field values on contracts, installments, fees, payments, disbursements, or deposits. The user needs a general-purpose "edit any value" capability on the frontend. In production, permissions will be rooted in facts (e.g., can't edit principal after disbursement), but for now everything should be editable without restrictions.

**Design choices (confirmed by user):**
- Edit mode per entity: click "Edit" on a contract card / table row → all fields for that entity become inputs, with Save/Cancel
- Generic backend endpoint: single `POST /contracts/:id/update-entity` for all entity types
- Individual row editing for installments (no CSV re-upload)

---

## Implementation

### 1. Editable Attributes Registry (`operations.clj`)

Add a data-driven registry mapping each entity type to its editable fields. Each field has: form field name, Datomic attribute, value type, and label. This registry drives both parsing (handler) and rendering (views).

**Entity types and their editable (stored-fact) attributes:**

| Entity | Editable Fields |
|--------|----------------|
| contract | external-id, principal, security-deposit, net-disbursement, commodity-*, disbursement-iban/bank, virtual-iban, disbursed-at, written-off-at, days-to-first-installment, step-up-terms |
| installment | seq, due-date, principal-due, profit-due, remaining-principal |
| fee | type, amount, due-date, days-after-disbursement |
| payment | amount, date, reference |
| disbursement | type, amount, date, reference, iban, bank |
| deposit | type, amount, date, reference, source |

Also add: `entity-id-attrs` map (entity-type keyword -> Datomic identity attr like `:contract/id`), and a `parse-field-value` function to convert form strings to the correct type (bigdec, instant, long, keyword, string).

### 2. Generic Update Operation (`operations.clj`)

Add `update-entity` function:
- Takes: conn, entity-type, entity-id (UUID), updates map (Datomic attr -> parsed value), user-id, optional note
- Validates entity-type is known and updates only contain allowed attributes
- Transacts: `{:db/id [:<type>/id uuid] :attr1 val1 ...}` + `(db/recording-metadata user-id :note ...)`
- Datomic's upsert-via-identity-attr handles the rest

### 3. Two New Routes (`core.clj`)

Add inside `["/:id" ...]` block (after line 107):

```
["/edit-form"      {:get handlers/edit-entity-form-handler}]
["/update-entity"  {:post handlers/update-entity-handler}]
```

### 4. Two New Handlers (`handlers.clj`)

**`edit-entity-form-handler`** (GET):
- Query params: `entity-type`, `entity-id`, optional `cancel`
- If `cancel=true`: re-render the read-only view fragment and return it
- Otherwise: pull entity from Datomic, render edit form, return SSE fragment
- For contract: renders `contract-edit-form` replacing `#contract-summary`
- For installment: renders `installment-edit-row` replacing `#inst-row-<uuid>`
- For fee: renders `fee-edit-row` replacing `#fee-row-<uuid>`
- For payment/disbursement/deposit: renders `entity-edit-form` (generic card form)

**`update-entity-handler`** (POST):
- Form params: `entity-type`, `entity-id`, plus field-specific params
- Parses fields via the editable-attributes registry
- Calls `ops/update-entity`
- Returns `contract-fragments` re-rendering `:summary`, `:fees`, `:installments` (since any edit may affect derived totals)
- Flash success message

### 5. New View Functions (`views.clj`)

**`edit-field`** — generic field renderer: takes a field descriptor + current entity, returns Hiccup label+input. Handles :string (text), :bigdec/:long (number), :instant (date), :keyword (select dropdown with :options).

**`contract-edit-form`** — replaces `#contract-summary`:
- Hidden inputs for entity-type=contract and entity-id
- 2-column grid of all contract editable fields as inputs (pre-filled)
- `data-on:submit` → `@post('/contracts/:id/update-entity', {contentType: 'form'})`
- Save/Cancel buttons. Cancel → `@get('/contracts/:id/edit-form?entity-type=contract&cancel=true')`

**`installment-edit-row`** — replaces `#inst-row-<uuid>`:
- Single `<tr>` with `<td colspan="...">` containing a `<form>`
- Grid of inputs: seq, due-date, remaining-principal, principal-due, profit-due
- Same Datastar post pattern for save, get pattern for cancel

**`fee-edit-row`** — replaces `#fee-row-<uuid>`:
- Same colspan+form pattern as installment
- Inputs: type (select), amount, due-date, days-after-disbursement

**`entity-edit-form`** — generic form for payment/disbursement/deposit:
- Used in history tab. Renders as a card replacing the entity detail div
- Uses `edit-field` to render all fields for the entity type
- Save/Cancel buttons

### 6. Modifications to Existing Views (`views.clj`)

**`contract-summary`** (line 190):
- Add an "Edit" button in the header area
- `data-on:click` → `@get('/contracts/:id/edit-form?entity-type=contract')`
- Need to thread `contract-id` through (currently only receives `state`, can get `(get-in state [:contract :id])`)

**`fees-table`** (line 228):
- Change signature from `[fees]` to `[contract-id fees]`
- Add an "Actions" column header
- Add `{:id (str "fee-row-" (:id fee))}` to each `<tr>`
- Add "Edit" button cell per row

**`installments-table`** (line 259):
- Change signature from `[installments]` to `[contract-id installments]`
- Add an "Actions" column header
- Add `{:id (str "inst-row-" (:id inst))}` to each `<tr>`
- Add "Edit" button cell per row

**`contract-fragments`** in `handlers.clj` (line 83):
- Update calls to `fees-table` and `installments-table` to pass `contract-id`
- Already has `contract-id` in scope (line 92)

**`contract-detail-page`** (line 1202):
- Update calls to `fees-table` and `installments-table` to pass `contract-id`
- Already has `contract-id` in scope (line 1212)

**`history-transaction-card`** (line 483):
- Thread `contract-id` through from `history-tab-content`
- Add "Edit" button on expandable entity details for editable types (payment, disbursement, deposit)
- Add `{:id (str "entity-" (name entity-type) "-" entity-id)}` to entity group div

**`history-tab-content`** (line 557):
- Pass `contract-id` through to `history-transaction-card`

---

## Files to Modify

| File | Changes |
|------|---------|
| `src/lms/operations.clj` | Add `editable-attributes`, `entity-id-attrs`, `parse-field-value`, `update-entity` (~120 lines new) |
| `src/lms/core.clj` | Add 2 routes after line 107 (~2 lines) |
| `src/lms/handlers.clj` | Add `edit-entity-form-handler`, `update-entity-handler` (~100 lines new) |
| `src/lms/views.clj` | Add `edit-field`, `contract-edit-form`, `installment-edit-row`, `fee-edit-row`, `entity-edit-form` (~150 lines new). Modify `contract-summary`, `fees-table`, `installments-table`, `history-transaction-card`, `history-tab-content` (small edits each) |

---

## Verification

1. Start nREPL and load changes
2. Board a test contract with fees and installments
3. Navigate to contract detail page
4. Test contract summary edit: click Edit → modify principal → Save → verify summary updates
5. Test installment edit: click Edit on a row → modify profit-due → Save → verify totals update
6. Test fee edit: click Edit on a fee → change amount → Save → verify
7. Test cancel: click Edit → Cancel → verify original values restored
8. Test history tab entity edit: expand a payment → click Edit → change amount → Save
9. Verify flash messages appear on save
10. Verify derived values (outstanding, status, credit-balance) recompute correctly after edits
