package ru.ba6kir.tradelab.market.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import ru.ba6kir.tradelab.market.application.InstrumentStore;
import ru.ba6kir.tradelab.market.domain.StockInstrument;

public class JdbcInstrumentStore implements InstrumentStore {
    private final JdbcTemplate jdbc;

    public JdbcInstrumentStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public Optional<StockInstrument> findByUid(String uid) {
        return jdbc.query("SELECT * FROM instruments WHERE uid = ?", this::map, uid).stream().findFirst();
    }

    @Override
    public void upsert(StockInstrument s) {
        jdbc.update("""
                INSERT INTO instruments (uid, ticker, class_code, name, figi, isin, currency, exchange,
                  lot_size, trading_status, api_trade_available, updated_at, source)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (uid) DO UPDATE SET ticker = EXCLUDED.ticker, class_code = EXCLUDED.class_code,
                  name = EXCLUDED.name, figi = EXCLUDED.figi, isin = EXCLUDED.isin, currency = EXCLUDED.currency,
                  exchange = EXCLUDED.exchange, lot_size = EXCLUDED.lot_size,
                  trading_status = EXCLUDED.trading_status, api_trade_available = EXCLUDED.api_trade_available,
                  updated_at = EXCLUDED.updated_at, source = EXCLUDED.source
                WHERE instruments.updated_at <= EXCLUDED.updated_at
                """, s.uid(), s.ticker(), s.classCode(), s.name(), s.figi(), s.isin(), s.currency(), s.exchange(),
                s.lotSize(), s.tradingStatus(), s.apiTradeAvailable(), s.updatedAt().atOffset(ZoneOffset.UTC), s.source());
    }

    private StockInstrument map(ResultSet r, int row) throws SQLException {
        return new StockInstrument(r.getString("uid"), r.getString("ticker"), r.getString("class_code"),
                r.getString("name"), r.getString("figi"), r.getString("isin"), r.getString("currency"),
                r.getString("exchange"), r.getObject("lot_size", Integer.class), r.getString("trading_status"),
                r.getObject("api_trade_available", Boolean.class), r.getObject("updated_at", OffsetDateTime.class).toInstant(),
                r.getString("source"));
    }
}
