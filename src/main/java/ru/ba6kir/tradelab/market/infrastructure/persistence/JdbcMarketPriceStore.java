package ru.ba6kir.tradelab.market.infrastructure.persistence;

import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ru.ba6kir.tradelab.market.application.price.MarketPriceStore;
import ru.ba6kir.tradelab.market.domain.price.*;

public class JdbcMarketPriceStore implements MarketPriceStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final String source;
    public JdbcMarketPriceStore(JdbcTemplate jdbc, PlatformTransactionManager manager, String source) {
        this.jdbc = jdbc; this.transactions = new TransactionTemplate(manager); this.source=source;
    }
    private static OffsetDateTime utc(Instant value) { return value.atOffset(ZoneOffset.UTC); }
    private static Instant time(ResultSet r, String column) throws SQLException {
        var value = r.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
    @Override public Optional<MarketQuote> findQuote(String uid) {
        return jdbc.query("SELECT * FROM market_quotes WHERE instrument_uid = ?", (r, n) -> new MarketQuote(
                r.getString("instrument_uid"), r.getBigDecimal("price"), r.getString("currency"), time(r, "quote_time"),
                time(r, "fetched_at"), r.getString("source")), uid).stream().findFirst();
    }
    @Override public void saveQuote(MarketQuote q) {
        jdbc.update("""
                INSERT INTO market_quotes (instrument_uid, price, currency, quote_time, fetched_at, source)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (instrument_uid) DO UPDATE SET price=EXCLUDED.price, currency=EXCLUDED.currency,
                  quote_time=EXCLUDED.quote_time, fetched_at=EXCLUDED.fetched_at, source=EXCLUDED.source
                WHERE market_quotes.quote_time <= EXCLUDED.quote_time AND market_quotes.fetched_at <= EXCLUDED.fetched_at
                """, q.instrumentUid(), q.price(), q.currency(), utc(q.quoteTime()), utc(q.fetchedAt()), q.source());
    }
    @Override public List<DailyCandle> findCandles(String uid, LocalDate from, LocalDate to) {
        return jdbc.query("""
                SELECT * FROM daily_candles WHERE instrument_uid=? AND trading_date BETWEEN ? AND ? AND source=?
                ORDER BY candle_time
                """, this::candle, uid, from, to, source);
    }
    private DailyCandle candle(ResultSet r, int row) throws SQLException {
        return new DailyCandle(r.getString("instrument_uid"), time(r, "candle_time"), r.getObject("trading_date", LocalDate.class),
                r.getBigDecimal("open_price"), r.getBigDecimal("high_price"), r.getBigDecimal("low_price"),
                r.getBigDecimal("close_price"), r.getLong("volume"), r.getBoolean("is_complete"),
                r.getString("source"), time(r, "fetched_at"));
    }
    @Override public void saveCandles(List<DailyCandle> candles) {
        transactions.executeWithoutResult(status -> {
            for (var c : candles) jdbc.update("""
                    INSERT INTO daily_candles (instrument_uid, candle_time, trading_date, open_price, high_price,
                      low_price, close_price, volume, is_complete, source, fetched_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (instrument_uid, candle_time, source) DO UPDATE SET trading_date=EXCLUDED.trading_date,
                      open_price=EXCLUDED.open_price, high_price=EXCLUDED.high_price, low_price=EXCLUDED.low_price,
                      close_price=EXCLUDED.close_price, volume=EXCLUDED.volume, is_complete=EXCLUDED.is_complete,
                      fetched_at=EXCLUDED.fetched_at
                    WHERE daily_candles.fetched_at <= EXCLUDED.fetched_at
                      AND (NOT daily_candles.is_complete OR EXCLUDED.is_complete)
                    """, c.instrumentUid(), utc(c.candleTime()), c.tradingDate(), c.open(), c.high(), c.low(), c.close(),
                    c.volume(), c.complete(), c.source(), utc(c.fetchedAt()));
        });
    }
    @Override public Optional<Instant> earliestIncomplete(String uid, Instant from) {
        return Optional.ofNullable(jdbc.queryForObject("""
                SELECT min(candle_time) FROM daily_candles WHERE instrument_uid=? AND candle_time>=?
                  AND NOT is_complete AND source=?
                """, OffsetDateTime.class, uid, utc(from), source)).map(OffsetDateTime::toInstant);
    }
    @Override public Optional<SyncState> syncState(String uid) {
        return jdbc.query("SELECT * FROM market_sync_state WHERE instrument_uid=?", (r, n) -> new SyncState(
                time(r,"covered_from"), time(r,"covered_to"), time(r,"last_attempt"), time(r,"last_success"),
                time(r,"last_failure"), r.getString("failure_code")), uid).stream().findFirst();
    }
    @Override public boolean tryStart(String uid, Instant attempt, Duration cooldown) {
        return jdbc.update("""
                INSERT INTO market_sync_state (instrument_uid, last_attempt, lease_until) VALUES (?, ?, ?)
                ON CONFLICT (instrument_uid) DO UPDATE SET last_attempt=EXCLUDED.last_attempt, lease_until=EXCLUDED.lease_until
                WHERE market_sync_state.last_attempt <= ?
                  AND (market_sync_state.lease_until IS NULL OR market_sync_state.lease_until <= EXCLUDED.last_attempt)
                """, uid, utc(attempt), utc(attempt.plus(Duration.ofHours(1))), utc(attempt.minus(cooldown))) == 1;
    }
    @Override public void coverHistory(String uid, Instant from, Instant to) {
        jdbc.update("UPDATE market_sync_state SET covered_from=?, covered_to=? WHERE instrument_uid=?", utc(from), utc(to), uid);
    }
    @Override public void finish(String uid, Instant attempt, boolean success, String failureCode) {
        if (success) jdbc.update("UPDATE market_sync_state SET lease_until=NULL, last_success=?, failure_code=NULL WHERE instrument_uid=? AND last_attempt=?",
                utc(attempt), uid, utc(attempt));
        else jdbc.update("UPDATE market_sync_state SET lease_until=NULL, last_failure=?, failure_code=? WHERE instrument_uid=? AND last_attempt=?",
                utc(attempt), failureCode, uid, utc(attempt));
    }
    @Override public SyncStatus status() {
        var success = jdbc.queryForObject("SELECT max(last_success) FROM market_sync_state", OffsetDateTime.class);
        var failed = jdbc.query("SELECT last_failure, failure_code FROM market_sync_state WHERE last_failure IS NOT NULL ORDER BY last_failure DESC LIMIT 1",
                (r, n) -> new SyncStatus(null, time(r,"last_failure"), r.getString("failure_code"))).stream().findFirst();
        return new SyncStatus(success == null ? null : success.toInstant(), failed.map(SyncStatus::lastFailure).orElse(null),
                failed.map(SyncStatus::failureCode).orElse(null));
    }
}
