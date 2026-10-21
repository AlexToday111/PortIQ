# Stock Analysis Engine — Task 5.3

Stage 5 now exposes cached company reports, deterministic explanations and comparable ranking groups through the existing private Telegram interface. It reuses the Task 5.2 scoring engine and its stored observations. There is no second financial scoring formula, LLM, price target, forecast, portfolio allocation, account synchronization or order operation.

## Report contract and sources

`StockAnalysisService.analyze(identifier)` accepts an exact instrument UID or a ticker resolved without ambiguity by the existing `InstrumentService`. A UID reads local data even with T-Invest disabled. Ticker resolution can call the existing metadata provider; the partial local catalog never proves ticker uniqueness. An unknown instrument is reported as unavailable.

`AnalysisSnapshotService` captures the instrument, latest stored score, the exact historical scoring model, cached last-trade quote, dividend view and applicable observations in one new **read-only REPEATABLE_READ** transaction. No source fundamental/dividend record is changed and there is no provider fallback. A database failure fails the report instead of inventing an empty financial dataset.

When a score exists, the report's fundamental observations and explanations come from its **frozen input**, not from a newer financial snapshot mixed into an old score. The scoring input/model fingerprints, source snapshot IDs, observation IDs, periods and timestamps preserve provenance. Current price, current security metadata, current classification and current dividend history are separate overlays read from the same database snapshot and explicitly dated. They are not claimed to be the historical inputs to that score. Changes to asset mapping or classification prevent ranking eligibility.

Without a stored score, the report displays available typed observations from `FinancialMetricService.cached`, missing-data markers and `NO_STORED_SCORE`. It does not silently create or persist a score. `/score <UID>` is the explicit scoring operation. A missing model configuration never results in invented rule thresholds.

The Russian report includes:

- Instrument name, ticker/class identity, instrument UID, asset UID, sector and business type.
- Metadata trading status/API flag and timestamp; these do not establish current session/account availability.
- Last available trade price, currency, original trade/retrieval times, source and freshness limitation. A last trade is never described as an executable quote.
- Profile-applicable metrics, original units/currencies, reporting periods, quality, observation IDs and sources. Bank reports do not treat industrial leverage/liquidity as unfavorable bank metrics.
- Recent recorded dividend events and separately announced future source events, queried coverage, cache freshness and reliable trailing yield when available. No event implies investor receipt; no future payment is forecast. Five events per section are displayed with an explicit omitted-event count and `/divhistory` reference when needed.
- Stored score status, nullable overall/Q/F/V/D points, pillar coverage, profile, model/configuration versions, input fingerprint and original evaluation/data timestamps.
- Current ranking eligibility with all machine-readable reasons, model factors, risk evidence and uncertainties.

Different reporting periods are shown individually and disclosed, never presented as one statement. Missing values remain missing. Old stored scores remain historical evidence; current eligibility can be stale or otherwise excluded.

## Explainability contract

`AnalysisExplanationService` uses deterministic localized templates over actual saved contributions and the exact saved model. Each `AnalysisExplanation` records:

- Explanation code and metric identifier.
- Raw observed value/unit/currency and separately normalized value.
- Applied rule ID and its direction, transformation and threshold curve.
- Contribution to its pillar, direction label, observation type/horizon/period/as-of date.
- Observation ID, provider/derived origin, financial snapshot/input references and Russian text.

The presentation labels values above/below **50/100 on the rule's existing scale** as positive/limiting factors. Fifty is only the displayed scale midpoint: it is not a new financial formula, a calibrated investment cutoff, a peer median or an industry percentile. All numerical points and contributions remain those of Task 5.2. Context-capped valuation contributions are explicitly explained as a model risk condition. Missing metrics produce `METRIC_UNAVAILABLE`, a null observation/value and the exact eligibility reason; they are not assigned favorable points.

Risk flags retain their original rule/condition, value/unit, timestamp and supporting observation IDs. Questionable source observations remain labeled with their quality; a source warning is not promoted to a verified fact. Reports distinguish financial observations, model interpretation and uncertainty. No generic claim such as “excellent investment” is generated.

## Telegram and operational flow

```text
/analyze SBER
/analyze <instrument UID>
/why SBER
/top
/top banks
/top oil
/top metals
/rankstatus
/rankrefresh
/help
```

All commands use the existing owner/private-chat authorization gate and dynamic `/help`. Telegram handlers orchestrate application services and format their results; they contain no scoring calculations. Reports use Russian text, decimal commas, explicit `%`/currencies and `Europe/Moscow` instant display. Technical rule/reason/metric identifiers remain machine-readable. Replies use plain text and the existing sender splits at Telegram's 4096 UTF-16-character boundary without breaking surrogate pairs; there is no end-of-report warning truncation. Individually bounded external display fields carry an explicit shortening marker.

Load real upstream data through the existing metadata/price/fundamental/dividend commands, inspect `/metrics` and `/scoreinfo`, then explicitly `/score` eligible instruments. `/rankrefresh` consumes saved scores and cached source evidence; it does not fill missing financial observations. `/analyze` outside the tracked universe is supported without changing that universe.

## Real-data and interpretation limits

Current T-Invest fundamentals often lack verified per-metric dates or percentage definitions. SBER's bank score additionally requires verified total regulatory capital/RWA and NPL/gross-loans measures that the current adapter does not provide. LKOH/GMKN and other industrial companies must remain unscored when required dated/defined inputs are unavailable. These are structural source limitations, **not live company audits**. No personal T-Invest/Telegram credentials were supplied or used for Task 5.3 verification.

The ranking is based on an **experimental fundamental scoring methodology rather than proven predictive investment performance**. Threshold calibration, source/accounting comparability, cycle behavior, coverage biases and predictive validity remain unvalidated. High scores do not guarantee gains; historical dividends do not guarantee future payments; cheap multiples do not establish intrinsic undervaluation. No return study or backtest was performed. Cross-sector calibration is unavailable; see [RANKING_METHODOLOGY.md](RANKING_METHODOLOGY.md).

## Files and verification

New production files:

- `analysis/reporting/application/AnalysisSnapshotService.java`, `StockAnalysisService.java`, `AnalysisExplanationService.java`.
- `analysis/reporting/domain/AnalysisExplanation.java`.
- `analysis/reporting/presentation/ResearchText.java`, `StockAnalysisFormatter.java`, `RankingFormatter.java`.
- `analysis/ranking/domain/RankingPolicy.java`, `RankingRun.java`.
- `analysis/ranking/application/RankingStore.java`, `RankingUniverseService.java`, `RankingEligibilityService.java`, `StockRankingService.java`.
- `analysis/ranking/persistence/CachedAnalysisSnapshotService.java`, `JdbcRankingStore.java`, `RankingProperties.java`, `RankingConfiguration.java`.
- `analysis/telegram/StockResearchCommand.java`, `StockResearchTelegramConfiguration.java`.
- `src/main/resources/db/migration/V8__create_stock_rankings.sql` and the two Task 5.3 documents.

Java paths above are relative to `src/main/java/ru/ba6kir/tradelab/`. Modified: scoring repository port/JDBC adapter to expose exact historical model configurations, application YAML, `.env.example`, Compose, README, architecture and the full-application migration assertions. `ScoringPersistenceIT` now gives its real dividend-cache service the fixture's fixed clock, removing an existing dependency on the calendar day of execution. No production freshness requirement was relaxed. No dependency was added and earlier applied migrations were not edited.

Five new files in `src/test/java/ru/ba6kir/tradelab/research/` contain explicitly synthetic fixtures, eligibility/ranking tests, report/command/authorization/chunking tests, configuration/universe tests and PostgreSQL persistence tests. Test fixtures are never registered as production responses.

Verification executed on **2026-10-21**: Maven `clean verify` finished with **BUILD SUCCESS** in **57.778 seconds**. All **347 tests passed: 290 unit/configuration tests and 57 integration tests**, with zero failures, errors or skipped tests. This includes **30 new unit/configuration tests and seven new PostgreSQL integration tests**, plus all 310 previous tests. Docker Desktop Engine 29.2.1 and disposable PostgreSQL 17 containers were used. The executable artifact is `target/tradelab-portfolio-0.0.1-SNAPSHOT.jar`.

Checks cover full/partial/missing-price/bank reports; exact rule/observation/threshold traceability; legitimate losses and distress caps; all scoring profiles, period groups, score/configuration mismatch, deterministic ties, exclusions and alternate securities; after-resolution cutoff; actual announced dividend events/stale yields; owner authorization, helpful filter errors, Russian formatting and lossless Telegram chunking. PostgreSQL tests cover V8/round trips/FKs, atomic rollback, durable concurrent admission/cooldown, appended history, genuinely unscored runs, historical model reads and read-only repeatable-read capture with no provider calls or source writes. Full-application tests also start all production feature beans with mock transport and validate V8.

The first targeted run correctly reported seven integration tests **skipped** while Docker was stopped; those were subsequently executed successfully after starting Docker and are not counted as passed until the final run. Final XML reports are in `target/surefire-reports/` and `target/failsafe-reports/`; the local build log is `.tools/research-final-verify.log`. Generated logs/reports/artifacts are ignored by Git. Live external smoke checks remain pending without personal credentials.
