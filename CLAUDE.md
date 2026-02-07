# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Clojure Parenthesis Repair

The command `clj-paren-repair` is installed on your path.

Examples:
`clj-paren-repair <files>`
`clj-paren-repair path/to/file1.clj path/to/file2.clj path/to/file3.clj`

**IMPORTANT:** Do NOT try to manually repair parenthesis errors.
If you encounter unbalanced delimiters, run `clj-paren-repair` on the file
instead of attempting to fix them yourself. If the tool doesn't work,
report to the user that they need to fix the delimiter error manually.

The tool automatically formats files with cljfmt when it processes them.


## Rules for Implementation Decisions

These rules guide all architectural and implementation decisions in this codebase:

### 1. Store facts, derive state

If you can compute it, don't store it. Facts are things that happened. State is what you conclude from facts.

```
Fact:    Payment of 10,000 received on March 15
State:   Installment 3 is paid (derived)
```

### 2. Is it the same thing or a different thing?

If two things change for different reasons, they're different things. Don't unify them. If they always change together, they might be the same thing.

```
Fee and Installment: different (different reasons to exist)
Installment status and paid amount: same (status IS the paid amount interpreted)
```

### 3. What is it?

Name what the thing actually is, not what it does or where it goes. If you struggle to name it simply, you're probably complecting.

```
Bad:  PaymentProcessor, FeeHandler, InstallmentManager
Good: Payment, Fee, Installment, Waterfall
```

### 4. Can I pass it to a function?

If you can't pass it as an argument, it's hiding something. Data beats objects. Functions over data beat methods on objects.

```
Bad:  installment.applyPayment(amount)
Good: (apply-waterfall installments amount)
```

### 5. When would this change?

If two pieces of information change at different times or for different reasons, don't store them together. If the answer is "never changes" — maybe it's not state at all, it's configuration or a rule.

```
Step-up terms: never change after signing → configuration (EDN)
Profit-due: changes when rate adjusts → stored on installment
Paid amount: changes every payment → derived from payments
```

### 6. What are the facts?

Before implementing, ask: what actually happened? Store that. Everything else is interpretation.

```
Facts:     Contract signed. Payment received. Rate changed.
Not facts: Installment is partial. Customer is delinquent. Balance is X.
```

### 7. Entity vs TX metadata

Business facts — things that happened in the real world — are entities. Recording facts — how something entered the system — are TX metadata.

```
Entity:      Payment of 50,000 received on Jan 15 via bank transfer FT-123
TX metadata: Recorded by user ahmed-k on Jan 16 at 10:23am via API

Test: Would this fact exist if we used a different database?
  Yes → entity.  No → TX metadata.
```

### 8. One namespace per domain concept

A Datomic attribute namespace groups attributes that describe the same concept. One namespace per domain concept that has its own identity.

```
contract/*     — the financing agreement
installment/*  — a scheduled repayment
fee/*          — a charge
payment/*      — money movement (type: :received, :disbursed; sign for reversals)
deposit/*      — collateral movement (type: :received, :refund, :offset, :transfer)
tx/*           — recording metadata (who, when, what operation)
```

If two things have identical attributes and only differ by direction, they're the same concept — use a type discriminator, not separate namespaces. If they affect different ledgers or have different rules, they're different concepts.

```
Payment in / disbursement out: same concept (payment/*), different type
Payment / deposit:             different concept (different ledger, different rules)
```

### 9. Reversal vs retraction

A reversal is a business event: the original happened, then was undone. A retraction is a data correction: the original was recorded in error.

```
Test: Did money actually move in the real world?
  Yes → reversal.  No → retraction.

Additional test: Even if money didn't move, did external systems consume it?
  GL closed, SIMAH reported, bank notified → reversal (can't retract what's already reported)
```

**Retraction** (recording was wrong, nothing real happened):

```
Wrong amount (typo):     retract + assert correct amount
Wrong contract:          retract + assert on correct contract
Split needed:            retract + assert two payments
Accidental/duplicate:    retract
```

Retraction uses [:db/retractEntity ...] with tx metadata explaining the correction:

```clojure
{:db/id "datomic.tx"
 :tx/reason :correction        ;; why
 :tx/corrects [:payment/id x]  ;; what was wrong
 :tx/operator [:user/id y]}    ;; who fixed it
```

Datomic history preserves retracted datoms — "retract" means "no longer asserted as true",
not "hidden forever". Queryable via (d/history db) for forensics.

**Reversal** (real-world event being undone, or external systems already saw it):

```
Check bounced:           negative payment + /reverses ref
Customer refund:         new disbursement (not a reversal — different event)
Already in GL/SIMAH:     reversal even if original was wrong (can't unsee)
```

| Scenario         | Real event? | Externally visible? | Action             |
|------------------|-------------|---------------------|--------------------|
| Typo on amount   | No          | No                  | Retract + correct  |
| Wrong contract   | No          | No                  | Retract + correct  |
| Accidental entry | No          | No                  | Retract            |
| Duplicate        | No          | No                  | Retract duplicate  |
| Check bounced    | Yes         | Yes                 | Reversal           |
| Already in GL    | Maybe not   | Yes                 | Reversal           |
| Reported to SIMAH| —           | Yes                 | Reversal           |
