package ru.ba6kir.tradelab.market.application;

import java.util.Optional;
import ru.ba6kir.tradelab.market.domain.StockInstrument;

public interface InstrumentStore {
    Optional<StockInstrument> findByUid(String uid);
    void upsert(StockInstrument instrument);
}
