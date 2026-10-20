package ru.ba6kir.tradelab.market.application;

import java.util.List;
import java.util.Optional;
import ru.ba6kir.tradelab.market.domain.StockInstrument;

public interface StockInstrumentProvider {
    List<StockInstrument> findShares(String query);
    Optional<StockInstrument> findByUid(String uid);
}
