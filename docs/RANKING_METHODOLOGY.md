# Ranking methodology — Task 5.3

This is research ordering over a **configured tracked universe**, not an exchange-wide scan or a list of stocks to buy. The ranking is based on an **experimental fundamental scoring methodology rather than proven predictive investment performance**. It introduces no new financial score. All points come from valid, immutable Task 5.2 score snapshots; see [SCORING_METHODOLOGY.md](SCORING_METHODOLOGY.md).

## Universe and share classes

The default universe reuses `INVEST_TRACKED_TICKERS` (up to 20 configured tickers). That supports the initial 20–30-security target without adding a second hardcoded tracking list. The repository supplies no assumed ticker identities, fabricated instruments or financial fixtures. An empty configured universe produces an explicitly empty run, not an exchange fetch. The operator selects Russian equities of interest; current liquidity, issuer domicile and historical ticker tradeability are not inferred from a name.

Alternatively set `RANKING_INSTRUMENT_UIDS` to at most 30 already resolved, locally stored security UIDs. This explicit list **replaces** ticker-based ranking membership and permits entirely offline regeneration. It does not change the price/dividend tracking lists. Missing UID catalog records remain exclusions with their requested identifiers preserved.

On explicit `/rankrefresh`, ticker identifiers are resolved through the existing instrument service outside database transactions. A missing or ambiguous ticker is an excluded member, not an invented UID. Provider configuration/authentication/rate-limit/outage errors fail generation and preserve the prior ranking; they do not publish a misleading all-unavailable replacement. Cached `/top` and `/rankstatus` do not resolve tickers, score securities or call providers. No full-exchange fundamentals request occurs.

Every run retains the exact requested identifiers, resolved UIDs, unresolved reasons, universe hash and member keys. On-demand `/analyze` does not mutate membership.

An eligible security is deduplicated by independently verified `issuerKey`, otherwise by exact `assetUid` with the identity limitation disclosed. Among multiple eligible classes/representations, the lexicographically smallest UID is selected **before score ordering**, rather than cherry-picking the highest security score. Others remain `NOT_COMPARABLE` with `ALTERNATE_SECURITY_SAME_ISSUER_OR_ASSET:<selected UID>`. An ineligible class does not prevent another eligible class from appearing. Unknown legal issuer links across distinct asset UIDs cannot be guessed; counts are securities, not asserted independent legal companies. Quality may be asset-level, while valuation/dividends retain the exact security-scoped input. Nothing is copied between share classes.

## Admission at a common cutoff

`RankingEligibilityService` checks each candidate at one UTC cutoff captured **after** optional ticker resolution and before the single read-only database capture. `CachedAnalysisSnapshotService` reads all candidates in one REPEATABLE_READ transaction, including the latest stored score and its exact model, security metadata and cached quote. No source records are mutated.

Admission requires:

1. Reliable local instrument identity and verified asset/classification consistent with the frozen score; changes in current asset mapping or sector/business/issuer classification require a new score.
2. Stored `SCORED` result with actual overall points and input evidence.
3. The exact active model version, configuration version and configuration fingerprint. Missing/different model configurations are not comparable.
4. Matching frozen input fingerprint and identity. The existing pure scoring engine revalidates that input at the common cutoff; its replayed contributions and total must match the saved result. **Replay is a validation check only; displayed/ranked points are always the persisted score.** There is no source fetch or score write during this check.
5. Original scoring coverage/unit/date/definition/context requirements, plus current classification and score-age limits.
6. A positive validated cached last-trade quote for the same instrument and currency, within both trade-age and retrieval-age limits. This proves suitable dated market evidence, not executability or current liquidity.
7. Fresh metadata with explicit `NORMAL_TRADING` (or SDK-prefixed equivalent) and API-trade flag true. Other/unknown statuses are excluded conservatively. This is a metadata policy, not an account permission or a real-time session check.
8. No input integrity, frozen replay or comparability mismatch.

Low financial points do **not** cause exclusion. Verified financial distress flags remain visible and can coexist with admission; their effect on valuation already belongs to the original scoring model. Missing evidence, unsupported methodology and unavailable trading metadata are separate outcomes.

Statuses are `ELIGIBLE`, `INSUFFICIENT_DATA`, `STALE_DATA`, `UNSUPPORTED_PROFILE`, `TRADING_UNAVAILABLE`, `NOT_COMPARABLE`. Every excluded entry retains **all** machine-readable reasons, even where multiple checks apply; the status is a summary, not a replacement for the reasons. Exclusion summaries count reason occurrences, so their sum may exceed the excluded-security count. No missing number is imputed and no score weight is renormalized.

## Comparable groups and sorting

Cross-sector score calibration has **not** been validated. `/top` therefore shows independent groups, never a global ordering where a bank's 85 proves superiority to an oil company's 80.

A group requires identical active model/configuration, scoring profile, sector and a fingerprint of selected rule/observation definitions: rule ID, provider/derived origin, source/methodology, units/currency, observation type/horizon, reporting start/end and valuation/dividend as-of day. Dividend history coverage/as-of must also match when the dividend pillar is active. Different reporting periods/definitions are separated into their own groups; no silent date averaging or currency conversion occurs. A singleton group is explicitly not evidence of superiority to peers. This conservative policy can create many small groups and does not establish that accounting definitions are economically interchangeable.

Within each group: saved overall score descending, then stable instrument UID ascending. Numbered ranks are sequential display positions even for tied scores; the UID tie-breaker has no investment meaning. Eligible groups precede excluded entries in storage/presentation. Group presentation order is deterministic and not an economic ranking across groups.

Filters map exactly as follows:

| Telegram filter | Scoring profile |
| --- | --- |
| `banks` | `BANK` |
| `oil` | `OIL_AND_GAS` |
| `metals` | `METALS_AND_MINING` |
| no filter | All independent profile/sector/period groups, including `GENERAL_NON_FINANCIAL` |

Unknown filters get a helpful error before data retrieval. Displayed total counts/reason summaries cover the **whole** universe; selected rows honor the filter. `/rankstatus` exposes the exact universe, every included/excluded member, score snapshot IDs, per-source timestamps and source references.

## Freshness, regeneration and failure handling

Defaults:

| Configuration | Default | Meaning |
| --- | --- | --- |
| `RANKING_POLICY_VERSION` | `profile-period-1` | Versioned eligibility policy |
| `RANKING_SNAPSHOT_TTL` | `1h` | Maximum cached ranking lifetime; bounded to 1 day |
| `RANKING_REFRESH_COOLDOWN` | `60s` | Durable admission cooldown, 10 seconds–1 hour |
| `RANKING_SCORE_MAX_AGE` | `24h` | Maximum original stored-score age, at most 30 days |
| `RANKING_QUOTE_MAX_AGE` | `3d` | Limits both last-trade and retrieval age, at most 7 days |
| `RANKING_METADATA_MAX_AGE` | `24h` | Metadata lifetime, at most 30 days |
| `ANALYSIS_CLASSIFICATION_MAX_AGE` | `365d` | Reused classification verification policy |
| `RANKING_INSTRUMENT_UIDS` | empty | Optional exact UID override, max 30 |

Scoring's stricter source/financial-period/dividend requirements still apply. `expiresAt` is the earliest of run TTL, eligible members' score/metadata/quote/classification/source/period deadlines and the next UTC day when dividend evidence is active. Required financial-context source deadlines are included. Overnight or holiday last trades may fit the explicit three-day market window; that never asserts a current tradeable session. A changed policy/configuration/universe invalidates current display even before TTL expiry.

Cached retrieval is immutable: it does not reconsider newly arrived source records or rerun eligibility. At expiration/configuration change, `/top` suppresses current positions and requests manual `/rankrefresh`; the historical run remains available in persistence and `/rankstatus`. New source data becomes visible in rankings only after explicit scoring/regeneration. There is no scheduler or notification.

Refresh admission is one atomic PostgreSQL update on a singleton state row. Cooldown and a ten-minute active lease survive restarts and prevent concurrent runs across processes. Resolution/capture/calculation happen outside the short write transaction. Successful completion locks and verifies the same lease token, rejects expired evidence, then atomically inserts the run and all entries and releases the lease. A SQL/serialization/calculation/provider failure leaves prior complete rankings intact; a sanitized last-failure code remains in status. Late/superseded writers cannot replace a newer lease. A crashed process's lease expires without an automatic job.

## Migration and reproducibility

Additive **V8** creates:

- `ranking_runs`: immutable generation/expiration instants, model/configuration hash, full versioned eligibility policy/hash, exact universe/hash, grouped scope, completion status and counts.
- `ranking_entries`: included **and excluded** members, nullable instrument/score foreign keys, sector/group, nullable rank/score, typed status, all reasons and compact immutable evidence. Eligible positions require a score reference and are unique per run/group. Financial points use `NUMERIC(12,6)`.
- `ranking_refresh_state`: durable attempt, lease, last-success timestamp and sanitized failure code.

All instants use `TIMESTAMPTZ`. The requested UID of an unresolved catalog member survives in JSON even though its nullable FK cannot reference a nonexistent instrument. The run's configuration hash is recorded even when no model/score row exists, allowing a genuine all-unscored run without inventing a model result. Admitted entries reference immutable scoring snapshots whose model configurations are already stored. Compact quote/metadata values, identity, flags and timestamps are captured in entry evidence because those cache rows can later change. No large financial dataset is duplicated and no separate analysis-report table is needed.

Stored score IDs/frozen inputs and exact models reproduce financial points/explanations. Run policy, universe, grouping and compact market/metadata evidence preserve the admission and ordering decision. Historical rankings are appended, not overwritten. Explanation templates and policy version should be maintained with the application revision; source financial snapshots are never silently migrated to new formulas.

## Limitations and verification

Production lists may be empty because the existing adapter cannot provide all required validated financial dates/definitions or bank measures. No validation is weakened to populate `/top`. Automated demonstrations use isolated synthetic fixtures only. Source completeness, thresholds, issuer linkage, comparability, coverage bias and predictive performance remain unvalidated. A ranking position is not a BUY/SELL/HOLD signal, expected return or portfolio allocation.

See [STOCK_ANALYSIS.md](STOCK_ANALYSIS.md) for changed files, report sources, commands and actual verification results. No personal portfolio management, backtesting, AI news analysis, recurring ranking generation or trading was implemented. Task 5.3 completes Stage 5 and stops here.
