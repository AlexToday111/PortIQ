package ru.ba6kir.tradelab.dividends.application.port;

import java.time.Instant;
import java.util.List;
import ru.ba6kir.tradelab.dividends.domain.model.DividendEvent;

public interface DividendProvider {
    List<DividendEvent> fetchDividends(String instrumentUid, Instant from, Instant to);
}
