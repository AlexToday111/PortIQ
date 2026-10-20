package ru.ba6kir.tradelab.analysis.application.port;

import java.util.*;
import ru.ba6kir.tradelab.analysis.domain.sector.SectorClassification;
import ru.ba6kir.tradelab.analysis.domain.metric.FinancialObservation;

public interface AnalysisStore {
    Optional<SectorClassification> classification(String assetUid);
    List<SectorClassification> classifications();
    void saveClassification(SectorClassification classification);
    void saveDerived(List<FinancialObservation> observations);
}
