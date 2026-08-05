# ADR-0001: Double-entry ledger over a mutable balance column

## Status
Accepted

## Context
The simplest way to track money is a `balance` column updated in place. It is fast to
build and it is what most services start with.

It also destroys information. When a balance is wrong — and eventually one is — there is
no record of how it got there. Reconstructing history from application logs is a forensic
exercise with no guarantee of completeness, and there is no structural reason for the sum
of all balances to be meaningful.

## Decision
Money movement is recorded as immutable, balanced entries. Each transaction produces two
or more entries whose signed amounts sum to zero. `account_balance` exists only as a
projection maintained in the same transaction as the entries, and its correctness is
continuously verifiable by re-aggregating the entry log.

## Consequences
- Every balance is explainable: the entries that produced it are the audit trail.
- Global integrity is a single query — `SUM(signed_minor_units)` over all entries must be
  zero. `ConcurrentPostingIT` asserts this after every concurrency scenario.
- Corrections are reversing transactions, so a mistake and its fix are both visible.
- Cost: more rows, and a projection that must be kept consistent. Accepted, because the
  alternative is a number nobody can defend.
