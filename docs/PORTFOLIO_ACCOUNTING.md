# Portfolio Management Engine — Task 6

The dedicated `portfolio` feature records existing investments. It contains immutable domain records, pure long-only FIFO accounting/reconciliation, application services and ports, PostgreSQL/T-Invest adapters and thin Russian Telegram commands. It reuses instrument metadata and **stored** market quotes. Domain accounting has no Telegram, SDK DTO, Spring, provider or market-price dependency. `ScoringFingerprint` is reused only as the existing canonical hashing utility; portfolio accounting does not change or invoke fundamental scoring formulas.

## Portfolios, accounts and authority

Multiple RUB portfolios and multiple separate accounts are supported. Creating a portfolio explicitly creates an **empty** manual account; startup creates no holdings, cash or example transactions. The one owner has a persisted active portfolio/account selection. Account identity is retained in transactions, lots, valuations and detailed holding reports; the same security can have different acquisition costs in different accounts. Only compatible RUB values are aggregated.

Account types are MANUAL, BROKERAGE and IIS. Sources are MANUAL and T_INVEST. Manual accounts work with broker integration disabled. A linked account is created separately through explicit account discovery/selection; it is not the first discovered account by default. Broker references stay in restricted persistence; routine replies use local selection UUIDs/names, not full broker account IDs.

Manual accounts use strict long-only inventory and chronological nonnegative cash validation. Broker-sourced accounts permit incomplete historical observations but never invent starting purchases or cash. Broker operations are authoritative there: manual trades, deposits, receipts and fees cannot silently overlap imported history. Explicit owner-supplied opening inventory and reversal of such an opening are the only manual historical supplements allowed on a linked account. An opening must precede the declared imported-history interval and include provenance. Converting an existing manual ledger into broker history or automatically merging apparent duplicates is not supported: keep accounts separate and reconcile deliberately. Linking into a portfolio with effective manual history is blocked by default (`MANUAL_HISTORY_REVIEW_REQUIRED`). Use a separate portfolio for the same actual account, or explicitly reverse verified duplicates. Only if the owner has reviewed that the manual accounts represent **different real accounts**, the `distinct-manual-accounts` link suffix permits aggregation and records the review timestamp. It is not an automatic duplicate-matching claim.

## Immutable ledger and confirmation

`LedgerTransaction` records ID/account, type, exact security UID, share quantity, execution price, gross/component amount, commission, currency, execution/recording instants, source, external reference, idempotency key, correction reference and confirmed status. `BigDecimal` is used throughout; no float/double or implicit lot multiplication is used.

Supported types:

| Type | Inventory | RUB cash | Interpretation |
| --- | --- | --- | --- |
| BUY | Create FIFO acquisition lot | `-(quantity × price + commission)` | Purchase fee capitalized |
| SELL | Consume earliest available lots | `quantity × price - commission` | Net proceeds compared with disposed cost |
| DEPOSIT | None | Positive amount | External capital, not profit |
| WITHDRAWAL | None | Negative amount | External capital withdrawal, not a loss |
| DIVIDEND_RECEIVED | None | Positive recorded receipt | Actual account entry, never an announcement |
| BROKER_FEE | None | Negative amount | Standalone fee, not another deduction of embedded trade fees |
| TAX_WITHHELD | None | Negative amount | Explicit actual withholding, no estimated tax |
| CASH_ADJUSTMENT | None | Explicit signed amount | Owner correction with explanation; strict manual cash constraints still apply |
| OPENING_POSITION | Explicit opening lot | None | Known total cost supplied, or null unknown cost |
| REVERSAL | Rebuild excluding the referenced original | Rebuild excluding original effect | Audited correction, not a fictional opposite market trade |

Manual money flows are RUB only. BUY/SELL require positive share quantity/price, explicit nonnegative commission and a consistent product. Cash amounts must be positive except a signed nonzero adjustment. Inputs are bounded decimal values; Telegram accepts decimal points/commas with up to nine fractional places and rejects exponents/negative trade quantities. Ledger values preserve up to 38 significant digits and 18 decimal places; products and projections use PostgreSQL NUMERIC without accidental database rounding. Dates are ISO with an explicit offset, or `YYYY-MM-DD` interpreted at midnight `Europe/Moscow`. Future execution dates are rejected. Omitted execution time is the preparation instant and is stated in the confirmation summary.

Modifying ledger commands prepare a durable 15-minute confirmation. Nothing enters the ledger until `/confirm <token>`. The draft is tied to its original local account, even if the active account changes. `RequestAwareTelegramCommand` receives an authorized stable Telegram update key from the existing dispatcher; it never receives a raw update or transport credentials. The request key plus payload hash deduplicates repeated updates. Reusing a key with different arguments is an error. Confirming the same token repeatedly/concurrently returns the same transaction.

Confirmation locks the account, revalidates the complete chronological ledger, appends the transaction and rebuilds projections atomically. Expired drafts do not write. A later concurrent trade can make an earlier draft invalid; validation occurs again on confirmation. Source calls, including optional ticker resolution, never run in that transaction. A UID can record an explicit owner-supplied security identity offline; without validated local RUB/MOEX metadata it cannot be included in market valuation. No provider identity or quote is fabricated for an unknown UID.

Database triggers forbid UPDATE/DELETE of confirmed ledger rows. To correct manual history, explicitly reverse the old record and prepare a replacement. A reversal references an original in the **same account**, can occur once per original, and cannot reverse another reversal. Reversing a purchase while dependent sales would exceed inventory is rejected until those dependencies are explicitly corrected. Broker corrections append a reversal and replacement in one transaction; A→B→A revisions remain separate audit history. Normal rows are never silently edited.

## FIFO and numerical policy

Effective transactions exclude reversed originals and reversal markers. They are sorted by execution instant; exact ties apply opening/deposit/adjustment before buy, dividend, sale, then other cash charges, with a stable idempotency-key tie-breaker. This explicit internal tie convention is not asserted to recover an unavailable exchange sequence. Late historical imports rebuild the affected account deterministically.

Each purchase creates a lot:

```text
originalAcquisitionCost = quantity × executionPrice + purchaseCommission
averageRemainingAcquisitionPrice = remainingCost / remainingShares
netSaleProceeds = soldShares × executionPrice - saleCommission
realizedTradePnL = netSaleProceeds - FIFOAcquisitionCostOfSoldShares
```

Partial FIFO consumption allocates a lot's current cost in proportion to consumed shares. Division uses scale 18 and HALF_EVEN. Allocation is bounded by the remaining cost; the final consumption takes the exact residual, so fully disposed costs sum to the original cost without rounding leakage. The sale commission is separately allocated across disposal rows proportionally to shares, with the final residual assigned exactly; this is trace evidence and is **not deducted again**. Broker execution-level commission allocation precedes FIFO and conserves the operation's verified fee total.

The remaining average cost is a weighted average over **remaining** FIFO inventory, not average historical purchase price and not the broker's displayed average. Unknown opening cost remains null. Selling unknown-cost inventory makes the relevant realized P&L unknown; the current remaining cost is unknown while unknown-cost shares remain. Closed positions remain available with zero quantity/cost and their historical lots/disposals. Fractional share quantities are preserved exactly and never multiplied by today's lot size.

Manual sales before available historical purchases or beyond inventory are rejected; no shorting/margin is introduced. For incomplete broker history, the actual sale/cash flow is retained, but an unavailable historical inventory becomes **unknown**, not negative or an automatic invented opening lot. The position and P&L are flagged incomplete. Explicit opening evidence can make quantity reconstructible later; unknown cost still cannot become known through a current quote.

FIFO is the MVP's internal performance-accounting convention. It is not a statement of the correct tax basis for every investor/jurisdiction. **Tax reporting must not be based on these calculations without additional verification.** There is no tax-rate assumption, tax-liability calculation or automatic corporate-action accounting.

## Cash, dividends and fees

Cash is reconstructed from journal cash movements. Deposits/withdrawals change capital, not P&L. Realized P&L is not added to cash a second time: the underlying sale proceeds already changed cash. Historical dividends are not added to current assets again: their recorded receipts already changed cash. Standalone fees and withheld taxes are shown separately. Trade commissions appear in fee totals for disclosure while their economic effect already belongs to cost basis/net proceeds; they are not deducted twice.

Stage 4 dividend events are corporate observations, **not account receipts**. This module has no event listener or conversion that credits an announcement, record date or proposed payment to account cash. `/income dividend` explicitly records owner-confirmed receipt. The amount is treated as the recorded credit; separate actual tax withholding requires `/cash tax` or a supported broker tax operation. For manual gross receipts plus explicit withholding, both original amounts remain auditable. Broker receipt payment does not by itself prove the tax gross base: receipt totals are labeled recorded receipts, with gross-base uncertainty disclosed. No expected dividend amount or investor-specific tax rate is invented.

## Valuation and completeness

`PortfolioValuationService` uses one read-only REPEATABLE_READ database snapshot. It rebuilds accounts from the authoritative ledger and reads existing `InstrumentService.getCached` / `MarketPriceService.cachedQuote` contracts. It does not fetch providers or resolve tickers. Financial amounts remain isolated by account/currency.

```text
marketValue = knownShareQuantity × acceptableStoredLastTradePrice
unrealizedPnL = marketValue - knownRemainingAcquisitionCost
unrealizedPercent = 100 × unrealizedPnL / positiveKnownRemainingCost
supportedAssetsPlusCash = fullyCoveredSupportedEquityValue + reliableRUBCash
```

Each valued row retains the original quote price/currency, trade/retrieval instants, source, valuation instant and quality status. `PORTFOLIO_QUOTE_MAX_AGE` defaults to 3 days and bounds **both** trade/retrieval age. Future, missing, stale, different-security or different-currency quotes are not used. The last trade is never described as an executable sale quote.

A missing price leaves position market value null, keeps acquisition cost visible and makes overall coverage partial. A known price with unknown cost can produce a dated market value, but no unrealized P&L/percentage. Zero positions need no current quote for their zero current value. Supported RUB equity subtotals and ledger cash fragments are separately labeled; a complete supported-assets-plus-cash figure remains null when required coverage is incomplete. Unsupported currencies are never added to RUB, and no FX rates are fabricated.

Linked-account history is not treated as reliable unless its requested start covers the known broker opening, its committed checkpoint is current within 24 hours, and unresolved accounting/source/reconciliation conditions are absent. The 24-hour condition is source-cache age, not proof that the API supplied every historical record. Missing acquisition history, unsupported events and broker mismatches suppress misleading broker P&L/position valuation; raw broker observations remain separately visible. This conservative MVP may withhold otherwise plausible subtotals rather than imply full brokerage NAV.

Realized trade P&L, unrealized P&L, recorded dividend credits, fees and tax withholding are separate measures. They are **not** time-weighted return, money-weighted return/XIRR or a total-portfolio performance calculation. Those remain future scope.

## Telegram workflow

```text
/portfolio create MyPortfolio
/portfolio list
/portfolio select <local portfolio UUID>
/accounts
/accounts create AnotherAccount
/accounts select <local account UUID>
/cash deposit 10000
/confirm <returned token>
/trade buy <security UID or unambiguous ticker> 10 250 2 [ISO-date]
/confirm <returned token>
/trade sell <security UID or unambiguous ticker> 5 270 2 [ISO-date]
/cash withdraw 5000 [ISO-date]
/cash fee 2 [ISO-date]
/cash tax 50 [ISO-date]
/cash adjust -5 explicit-explanation
/income dividend <security UID or unambiguous ticker> 350 [ISO-date]
/opening <security UID> 10 unknown 2026-01-01 evidence-note
/reverse <local original transaction UUID> correction-note
/portfolio
/positions
/position <security UID or unambiguous ticker>
/transactions [1..50]
/portstatus
/reconcile
```

All ledger modifications above require confirmation. `/trade` is **bookkeeping**, not an order. Share quantity is explicit. There is no lots-input mode to accidentally use the wrong historical lot size. Account/portfolio creation and selection are local metadata operations. `/positions` orders available values descending with missing values last and retains account identity. `/position` shows per-account FIFO lots plus a clearly labeled compatible RUB aggregate. `/transactions` defaults to 10, bounded to 50, includes local audit IDs/reversal references and hides external operation IDs. Replies use Russian plain text, decimal commas, currencies, units and `Europe/Moscow`, with existing lossless Telegram message splitting. Every command passes owner/private-chat authorization before parsing or receiving an update idempotency key.

## Migration and files

Additive **V9__create_portfolio_management.sql** creates portfolios/accounts/owner selection, broker discovery candidates, immutable ledger, pending confirmations, acquisition lots/disposals/position projections, source operations/versions, sync runs/state, broker snapshots and reconciliation history (15 tables). UUIDs identify aggregates/audit records, NUMERIC preserves financial values, TIMESTAMPTZ preserves actual instants. Account-scoped uniqueness/composite FKs protect transaction corrections and confirmations; source-operation uniqueness and source-version fingerprints protect imports. Ledger triggers enforce immutability. Projections are explicitly rebuildable derived facts and are not editable sources of truth. No previous migration/dependency was changed.

New files under `src/main/java/ru/ba6kir/tradelab/portfolio/`:

- `domain/model/Portfolio.java`, `PortfolioAccount.java`, `LedgerTransaction.java`.
- `domain/accounting/AccountingResult.java`, `FifoAccountingEngine.java`.
- `domain/valuation/PortfolioValuation.java`.
- `domain/reconciliation/ReconciliationResult.java`, `PortfolioReconciliationEngine.java`.
- `application/port/PortfolioStore.java`, `BrokerPortfolioProvider.java`.
- `application/service/PortfolioService.java`, `PortfolioTransactionService.java`, `PortfolioValuationService.java`, `BrokerOperationMapper.java`, `PortfolioSynchronizationService.java`.
- `infrastructure/persistence/JdbcPortfolioStore.java`, `PortfolioProperties.java`, `PortfolioConfiguration.java`.
- `infrastructure/tinvest/TInvestBrokerPortfolioProvider.java`.
- `telegram/PortfolioInput.java`, `PortfolioFormatter.java`, `PortfolioCommand.java`, `PortfolioTelegramConfiguration.java`.

Also added `telegram/command/RequestAwareTelegramCommand.java` in the existing Telegram feature, V9 and the two Task 6 documents. Modified existing `TelegramCommandDispatcher` to pass stable authorized request keys for opted-in commands, `TInvestStockInstrumentProvider` to expose infrastructure-only Users/Operations gateways over the same client, application YAML, `.env.example`, Compose, README, architecture and `TradeLabApplicationIT` migration assertions. Stage 5.3 work already in the tree remains intact; no fundamental scoring formula was changed.

Seven new test files in `src/test/java/ru/ba6kir/tradelab/portfolio/`: `PortfolioTestData`, `FifoAccountingTest`, `PortfolioValuationTest`, `BrokerOperationMapperTest`, `TInvestBrokerAdapterTest`, `PortfolioCommandConfigurationTest`, `PortfolioManagementIT`. Synthetic data is isolated to tests.

## Verification and limits

The required synthetic example is tested only in fixtures: deposit 2000, buy 10 × 100 plus 10 fee, sell 5 × 120 less 5 fee produces initial cost 1010, average 101, remaining cost 505, net proceeds 595, realized P&L 90, cash 1585; a stored price of 120 gives remaining market value 600, unrealized P&L 95 and supported value 2185. None of those numbers is inserted into production portfolios.

Verification executed on **2026-10-21**: Maven `clean verify` completed with **BUILD SUCCESS** in **1 minute 28 seconds**. All **406 tests passed: 329 unit/configuration tests and 77 integration tests**, with **zero failures, errors or skipped tests**. This includes **39 new portfolio unit/configuration/SDK/command tests and 20 new PostgreSQL integration tests**, plus all 347 previous tests. The executable artifact is `target/tradelab-portfolio-0.0.1-SNAPSHOT.jar`.

The tests cover the exact required numerical fixture, FIFO/fees/residuals/fractional shares, chronology/oversell/cash validation, unknown cost/history, missing/stale/currency-mismatched quotes, real SDK RPC signatures/partial executions/access/deadlines/retries/rate limits, confirmations/update idempotency/authorization/input parsing, and configuration guards. PostgreSQL 17 Testcontainers verify V9/FKs/immutable ledger triggers, atomic rollback, concurrent confirmations, projection rebuilding, separate accounts, explicit broker account choice, paginated/overlapping imports, A→B→A corrections, changed-ID aliases, interrupted/cursor-loop recovery, unsupported corporate/foreign holdings, explicit unknown-cost opening evidence, read-only/expired discovery checks, manual-source overlap review, source/sync/reconciliation persistence and no provider requests during local valuation/reconciliation. Existing full-application tests load the actual new feature beans with mock transport and validate migration version 9.

Final XML reports are in `target/surefire-reports/` and `target/failsafe-reports/`; the local final log is `.tools/portfolio-final-verify.log`. Reports/logs/artifacts are generated and ignored by Git. Test code never uses the configured personal database or a real external broker/Telegram endpoint. Live brokerage synchronization remains pending: no personal broker token or real owner account was used. [BROKER_SYNCHRONIZATION.md](BROKER_SYNCHRONIZATION.md) details source contracts, unsupported operations, recovery and reconciliation.

There are no investment recommendations, portfolio weights/optimization, orders, transfers, expected returns, price targets, full FX analytics, backtesting or AI news processing. Task 6 stops before Task 7.
