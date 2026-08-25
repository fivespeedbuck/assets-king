# Assets King domain context

This file records the bounded-context vocabulary used by the recovery worktree. The auditable product source remains the matching notes in `D:\obsidian\obsidian\02-项目\资产大王`.

## Credit-card debt context

| Term | Meaning in this codebase | Must not be confused with |
| --- | --- | --- |
| Credit-card purchase | A posted expense that increases the card liability when it occurs. | The later card payment. |
| Post-purchase installment | Repayment terms applied to principal that already exists in a posted credit-card purchase. | A new purchase, a loan draw, or a second liability. |
| Installment allocation | The auditable amount of an original posted purchase assigned to one active installment plan. | A balance mutation. Creating or cancelling it has no ledger effect. |
| Installment schedule | Future principal, interest and fee expectations by due date. | An actual transaction. |
| Accrued interest / fee | Cost that the card issuer has actually posted. It increases expense and card liability once. | Future expected interest / fee. |
| Card payment | A transfer from a cash account to the credit-card account that reduces liability and cash. | Expense. |
| Current liability | The card account's posted outstanding balance, including accrued costs. | Current balance plus installment principal again. |
| Future expected cost | Unaccrued installment interest or fees used only for forecasting. | Current liability or current expense. |
| Payment-only quote | Principal, period count and a fixed per-period payment supplied by the user. | Proof of the issuer's nominal interest rate or fee classification. |
| Estimated effective annual cost (IRR) | The annualized internal rate implied by a fixed monthly payment cash-flow forecast. | Contractual nominal APR; the estimate assumes monthly payments in arrears. |
| Unclassified forecast charge | The portion of a quoted fixed payment above forecast principal when the issuer has not separated interest from fees. | An accrued interest/fee expense. It remains forecast-only until a real posting arrives. |

## Decisions

- `CreditCardInstallmentPlan` is a separate bounded context from `LoanPlan`; it must not be mapped to a loan draw.
- Creating a post-purchase installment never changes the original transaction, account balances, spending, cash flow, or total debt.
- Active allocations cannot exceed either the unrefunded, unallocated source expense or the card's still-outstanding unallocated liability.
- Plans, allocations and schedules are never hard-deleted after creation. Cancellation releases future allocation capacity while preserving the audit trail.
- Future schedule rows do not post money. Actual card payments remain transfers; actually posted interest and fees remain expense events.
- The default UI may accept only a fixed per-period payment and calculate estimated total charges/IRR. Unknown costs stay `expectedUnclassifiedChargeCents`; they must not be silently relabelled as actual interest or fees.
- Different pricing structures require different inputs. The current UI supports fixed-payment inference and separately supplied per-period interest/fee; additional issuer-specific methods must be added as explicit modes, not squeezed through the fixed-payment formula.
- Migrated legacy preview rows are labelled `LEGACY_UNLINKED`; no source transaction is guessed.

## Boundary

The current application still uses account checkpoints plus transaction/transfer events rather than a full double-entry journal. This recovery batch may add a safe installment seam, but it must not claim that the complete V3 posting-engine migration is finished.

## Ledger-deletion language

**Posted ledger event**:
A confirmed transaction or transfer that participates in balances and derived debt state.
_Avoid_: Notification, pending item, raw message

**Pending evidence**:
An unconfirmed bank, payment-app, or SMS observation that has not entered the posted ledger.
_Avoid_: Transaction, deleted transaction

**Trash a ledger event**:
Temporarily deactivate one posted ledger event, reverse only its owned effects, and retain enough state for a seven-day restoration window.
_Avoid_: Ignore evidence, hard delete, roll back the whole account

**Deletion projection**:
The per-account current balance and projected balance after the selected posted events are deactivated.
_Avoid_: Reconciliation checkpoint

**Dependency snapshot**:
The before/after state owned by a loan, reimbursement, or installment-payment relationship, used to prove that restoration will not overwrite later related changes.
_Avoid_: Whole-database backup

**Restore a ledger event**:
Reactivate the trashed event in the current timeline; unrelated events posted after deletion remain intact.
_Avoid_: Restore the account snapshot, rewind the ledger

**Evidence tombstone**:
A durable ignored marker for raw evidence whose posted event is in trash or permanently purged, preventing notification rescans from recreating it.
_Avoid_: Trash entry

**Audit-locked source expense**:
A credit-card purchase referenced by an installment allocation; it remains immutable so the installment audit chain never points at a missing or rewritten source event.
_Avoid_: Ordinary deletable expense

**Internal transfer**:
One movement between two accounts owned by the user; it changes account locations but is neither income nor expense.
_Avoid_: Any bank transfer, external payment, external receipt

**External payment or receipt**:
Money moving between a user-owned account and another person or organization; its ledger type is determined by economic purpose such as expense, income, receivable, borrowing, repayment, refund, or reimbursement.
_Avoid_: Internal transfer
