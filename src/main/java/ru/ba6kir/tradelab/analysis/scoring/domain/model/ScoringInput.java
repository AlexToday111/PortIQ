package ru.ba6kir.tradelab.analysis.scoring.domain.model;

import java.time.*;
import java.util.*;
import ru.ba6kir.tradelab.analysis.domain.metric.FinancialObservation;
import ru.ba6kir.tradelab.analysis.domain.sector.SectorClassification;

/** Frozen source evidence. Capture time is not substituted for a financial/publication date. */
public record ScoringInput(String instrumentUid,String assetUid,String name,SectorClassification classification,boolean classificationFresh,
        List<FinancialObservation> observations,DividendEvidence dividends,String tradingStatus,Boolean apiTradeAvailable,
        Instant metadataFetchedAt,List<String> warnings) {
    public ScoringInput {
        observations=observations.stream().sorted(Comparator.comparing(FinancialObservation::id,Comparator.nullsFirst(Comparator.naturalOrder()))).toList();
        warnings=warnings.stream().sorted().toList();
    }
    public record DividendEvidence(boolean reliable,LocalDate coverageFrom,LocalDate coverageTo,LocalDate asOf,
            int recordedEvents,int distinctRecordYears,List<String> eventHashes,Instant fetchedAt,String reason) {
        public DividendEvidence { eventHashes=eventHashes.stream().sorted().toList(); }
    }
}
