package ru.ba6kir.tradelab.market.application;

import java.util.List;
import ru.ba6kir.tradelab.market.domain.StockInstrument;

public class AmbiguousInstrumentException extends RuntimeException {
    private final List<StockInstrument> matches;
    public AmbiguousInstrumentException(List<StockInstrument> matches) {
        super("Ambiguous stock ticker"); this.matches=List.copyOf(matches);
    }
    public List<StockInstrument> matches() { return matches; }
}
