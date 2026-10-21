# Read-only brokerage synchronization — Task 6

The optional broker adapter shares the existing authenticated production T-Invest transport, channel, token, finite per-attempt deadline, bounded retries and rate-limit cooldown. No second SDK dependency, account mutation, order, transfer, margin operation or stream is added.

## Verified contract and activation

The requested developer.tbank.ru documentation pages returned HTTP 502 during implementation. Method/field definitions were checked against the installed **java-sdk-grpc-contract 1.51** using `javap`, compiled generated stubs, and the official [Operations protobuf contract](https://raw.githubusercontent.com/RussianInvestments/investAPI/main/src/docs/contracts/operations.proto) / [Users protobuf contract](https://raw.githubusercontent.com/RussianInvestments/investAPI/main/src/docs/contracts/users.proto) (checked 2026-10-21). The adapter invokes exactly:

- `UsersService.GetAccounts`: discovery/access/status/opening date.
- `OperationsService.GetOperationsByCursor`: paginated original operations and actual executions.
- `OperationsService.GetPositions`: original currency/free/blocked observations and loading status.
- `OperationsService.GetPortfolio`: quantities **in shares**, including unsupported/virtual holdings.

It does not call deprecated `GetOperations`. Generated-stub tests assert exact method names and request fields, deadlines, actual quantityDone/executions, commissions, account identity, blocked/settled distinctions and read-only access flags.

Manual bookkeeping is available with the following defaults and needs no personal token:

```text
PORTFOLIO_BROKER_SYNC_ENABLED=false
PORTFOLIO_SYNC_COOLDOWN=60s
PORTFOLIO_SYNC_MAX_PAGES=20
PORTFOLIO_QUOTE_MAX_AGE=3d
```

To enable broker reads, configure the existing production **read-only** `INVEST_API_TOKEN`, set `INVEST_API_ENABLED=true` and explicitly set `PORTFOLIO_BROKER_SYNC_ENABLED=true`, then restart. A missing authenticated transport fails startup rather than falling back to another client/environment. The broker must confirm `ACCOUNT_ACCESS_LEVEL_READ_ONLY` for the selected open brokerage/IIS account; a full-access token/account is rejected for linking/synchronization. Raw token values must stay out of Git, arguments and diagnostics.

```text
/accounts discover
/accounts link <local discovery UUID> <ISO-history-start> <local-name>
/accounts select <returned local account UUID>
/sync
/reconcile
/portstatus
```

Discovery shows local selection codes, never assumes the first account, and expires after 15 minutes. Linking creates a separate account with a unique external reference. It does not transfer/merge existing manual history or operate on broker accounts. The explicit history start is the oldest requested interval, not an assertion that every earlier acquisition is known. A later start than the broker opening (or unknown opening date) preserves an incomplete-history warning.

If the selected portfolio already contains effective manual history, linking is rejected until account-source overlap has been reviewed. For the same actual broker account, use a separate portfolio or explicitly reverse verified duplicates; do not retain two copies in one total. If the manual histories belong to **other** real accounts, an explicit `/accounts link <candidate> <start> <name> distinct-manual-accounts` acknowledges that owner verification and stores `manual_overlap_reviewed_at`. The system never guesses equivalence from matching names/amounts or auto-merges existing manual facts.

## Operation and execution accounting

The adapter preserves original external/parent operation references, type/state, instrument kind/UID, date, signed payment, currency, price, commission, quantityDone and execution trade numbers/dates/quantities/prices plus original raw evidence. It does not use order requested quantity or current lot size as executed shares. Price/commission currency mismatches remain unsupported. Only finalized `OPERATION_STATE_EXECUTED` operations enter the ledger; pending/cancelled sources remain diagnostics. Partially filled pending/cancelled records with actual quantity, or a previously imported record later becoming non-final, require reconciliation rather than guessed cash reversal.

Mapping whitelist:

| Source operation | Ledger meaning |
| --- | --- |
| OPERATION_TYPE_BUY | BUY components from actual executions, or verified aggregate filled quantity |
| OPERATION_TYPE_SELL | SELL components from actual executions, or verified aggregate filled quantity |
| OPERATION_TYPE_INPUT | DEPOSIT |
| OPERATION_TYPE_OUTPUT | WITHDRAWAL |
| OPERATION_TYPE_DIVIDEND | DIVIDEND_RECEIVED cash credit; source does not establish tax gross base |
| OPERATION_TYPE_BROKER_FEE | Attributable trade commission when a reliable parent exists; otherwise standalone fee only without a parent |
| OPERATION_TYPE_SERVICE_FEE | Standalone BROKER_FEE |
| OPERATION_TYPE_TAX / DIVIDEND_TAX / TAX_PROGRESSIVE / DIVIDEND_TAX_PROGRESSIVE | Actual TAX_WITHHELD; no investor tax-rate calculation |

Only RUB share trades and compatible RUB cash flows are mapped. Every trade requires actual positive execution quantities/prices with an exact quantityDone match, valid UID/date and payment sign. Execution gross sum must reconcile exactly to the payment's gross or explicitly commission-adjusted net components. A mismatch is held for review rather than approximated into a trade. Zero commission must be explicitly present, or a reliable separate fee must supply it; a missing fee field is not guessed to be zero.

Each actual execution becomes a separate ledger component; unrelated trades are not merged by order. Verified total trade commission is allocated by execution gross value, with scale 18/HALF_EVEN and the last exact residual. Embedded commission and separately reported parent-related fee amounts are reconciled: equal amounts are one economic fee, not two. If embedded fee is absent/zero and an explicit related fee is available, that fee is capitalized/netted into the trade. Related fee rows remain source evidence labeled INCLUDED_IN_TRADE, not a duplicate standalone debit. Contradictory totals or orphan parent relationships remain unresolved and suppress misleading account P&L. Standalone service/unrelated fees retain their distinct expense cash flow.

## Identity, revisions and duplicates

The official contract notes that operation IDs can change. Therefore `(local account, external operation ID)` is not the only check:

1. Current source observations are unique by account/external ID; each distinct full-source fingerprint is retained in immutable source-version history.
2. Stable execution trade-number/UID/type evidence detects another provider ID representing the same executions. Equal complete financial evidence is an alias, never another purchase. Already imported canonical IDs retain priority so aliases cannot switch the ledger to a second cash flow.
3. Changed/conflicting execution aliases and overlapping execution groups require investigation; they are not silently merged. Identical cash/aggregate operations under different IDs have no sufficiently stable execution identity, so the possible duplicate is held for review. This conservative rule may also hold genuinely separate indistinguishable same-time cash flows; the application cannot prove which interpretation is correct.
4. Ledger components have account-scoped idempotency keys. Equal repeated components do nothing. Changed eligible components under a stable authoritative identity append an explicit reversal and replacement, retaining the original/correction link. A return to an older financial value creates another revision, not reuse of a reversed transaction.

Source alias/revision diagnostics are retained. The system cannot automatically resolve every changing-ID case, parent-ID change or missing historical execution identity. Such ambiguity is disclosed rather than allowing double deposits, purchases or fees. Manual history is never auto-matched into broker history; user opening supplements remain explicit separate ledger evidence.

## Lifecycle, pagination and recovery

`/sync` operates only on the explicitly selected linked account. Admission takes a short account-row lock and persists a sync run/lease. Default cooldown is 60 seconds (configurable 10 seconds–1 hour); the active lease is ten minutes and survives restarts. Concurrent runs for one account are rejected; other accounts are independent. Every completion verifies the original token/run and unexpired lease, so a late writer cannot commit over a newer run.

Synchronization requests at most a 31-day history window at a time. It starts at the declared history start, then at the last committed checkpoint minus a seven-day overlap (bounded by the history start). This deliberately revisits recent operations/corrections. Older source corrections outside the overlap are not automatically rediscovered; changing history coverage/resetting it is a future explicit administrative workflow, not an implicit destructive rescan. Long initial histories advance over repeated manual `/sync` calls; no scheduled loop is added.

Each page requests limit 100, all operation states and explicitly includes commissions, trades and overnights so unsupported items are not hidden. Cursor loops, contradictory same-ID records across pages, out-of-window operations, missing identities, oversized pages and the configured page limit abort safely. `PORTFOLIO_SYNC_MAX_PAGES` is 1–50 (default 20). One run is bounded to 5000 operations at most. The existing unary policy retries temporary failures at most three times, with new finite deadlines; authentication/rate limits are reported safely without retry storms.

External account verification, all pages and broker snapshot requests occur **outside database transactions**. The fetched window is then committed as one validated atomic batch under the account lock: original source/version evidence, explicit ledger changes, rebuilt lots/disposals/positions, broker snapshot, reconciliation and sync report/checkpoint. No successful checkpoint advances until all of that persists. If an API/page/database/mapping failure interrupts the run, the committed checkpoint and previous ledger/projections/reconciliation remain intact, a sanitized failure code is recorded, and retry starts the same overlap. The MVP restarts a failed bounded window instead of persisting an unverified halfway cursor.

Successful window completion can still be PARTIAL when source history, unsupported events or reconciliation are unresolved. The checkpoint means the requested window was fetched/persisted, not that unsupported operations became supported or all investment history was recovered. Counts explicitly describe created/unchanged/corrected **ledger components** (one operation can have several executions) and unresolved current source observations; they are not asserted to be distinct orders or complete broker-report totals.

## Missing history, unsupported instruments and corporate actions

No broker position or average/FIFO display price is turned into a fictional purchase. Current GetPortfolio/GetPositions observations remain separate from the transaction ledger. If a sale cannot be matched to known acquisitions, the cash flow is retained, inventory/cost/P&L are incomplete, and no short/negative position is manufactured. The owner may provide a dated opening quantity plus known total basis or explicitly unknown basis; it remains auditable and cannot create known cost from the latest price.

Everything outside the whitelist is preserved as original diagnostic evidence. In particular, splits/reverse splits, transfers/conversions, mergers/spin-offs, bond repayment/coupons, repo/overnight, margin/card/delivery trades, derivatives/virtual positions, FX, dividends sent outside the account, tax corrections/refunds and other fee/income variants are not guessed into ordinary equity accounting. Future operation types are unsupported by default. Unknown or malformed types/currencies/definitions remain exceptions requiring review, not dropped zero-value facts. Unsupported source operations/holdings make brokerage coverage partial and withhold misleading monetary/P&L totals.

Foreign currency, bonds, funds, futures, options and virtual holdings from broker snapshots are retained. They are not converted or added to RUB equity totals. Existing read-only instrument and market-price contracts remain the valuation source for supported ledger holdings; no implicit FX or broker-average-cost substitution occurs.

## Reconciliation

`PortfolioReconciliationEngine` compares exact security UIDs, internal reconstructed share quantities and broker GetPortfolio total share quantities. GetPositions free/blocked security quantities are retained separately as definition/timing evidence; blocked lots are never silently treated as shares. Settled/free-plus-blocked disagreement is flagged, not assumed to be a trade discrepancy that should be repaired. Pending settlement and sequential endpoint snapshots can explain differences but do not prove an adjustment amount.

Statuses include MATCHED, QUANTITY_MISMATCH, MISSING_INTERNAL_POSITION, MISSING_BROKER_POSITION, UNSUPPORTED_INSTRUMENT, INCOMPLETE_HISTORY, CASH_MISMATCH and CASH_DEFINITION_OR_CURRENCY_UNSUPPORTED. RUB money is compared only for an explicit zero-blocked/not-loading snapshot; otherwise cash definitions/currencies remain incomparable. Even an equal figure is a dated comparison, not verification of current available-to-withdraw cash. Source capture time and reconciliation calculation time are both retained and shown; snapshots older than 24 hours/future snapshots are flagged.

`/reconcile` uses the last stored broker observation, rebuilds internal accounting, appends a new comparison and makes **no provider call or accounting adjustment**. Manual accounts state that broker reconciliation is unavailable. No mismatch is repaired through synthetic trades, cash, acquisitions or corporate actions. Explicit future investigation/correction is required.

## Storage, privacy and verification

V9 separates immutable ledger/corrections, rebuildable projections, original source versions, current source diagnostics, sync runs/checkpoints/leases, broker observations and append-only reconciliations. Duplicate source copies serve distinct audit/current-projection purposes; only the transaction ledger is authoritative for internal accounting. External account/operation references and raw source payloads are personal financial evidence: they stay in the database and are not routine Telegram/log output. There is no export/transmission of portfolio data beyond the explicitly requested read-only broker methods.

Automated tests use synthetic data, scripted generated SDK channels, mocked broker APIs and disposable PostgreSQL 17. They verify explicit account selection/access, pagination/overlap, unchanged/changed/reverted records, aliases, commissions/executions, incomplete history, unsupported corporate/foreign assets, cursor failures, concurrency, atomic rollback, source/reconciliation persistence and absence of external calls during local valuation/reconciliation. Final executed counts are in [PORTFOLIO_ACCOUNTING.md](PORTFOLIO_ACCOUNTING.md).

**Live brokerage synchronization has not been verified without a personal read-only token/account.** No successful live account sync, tax-reporting fitness, complete historical coverage, real-time tradeability, broker NAV parity or universal operation-ID reconciliation is claimed. No order/transfer/account mutation, trading recommendation, allocation/optimization, tax liability, full FX performance, TWR/MWR/XIRR or Task 7 functionality is implemented.
