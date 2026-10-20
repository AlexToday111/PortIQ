package ru.ba6kir.tradelab.analysis.scoring.application.service;

import java.util.*;
import ru.ba6kir.tradelab.analysis.scoring.domain.model.ScoringResult;
import ru.ba6kir.tradelab.analysis.scoring.domain.profile.ScoringModel;

public interface ScoringResultRepository {
    class ModelVersionConflictException extends RuntimeException {
        public ModelVersionConflictException() { super("MODEL_VERSION_CONFLICT"); }
    }
    ScoringResult save(ScoringResult result,ScoringModel model);
    Optional<ScoringResult> latest(String instrumentUid);
    List<ScoringResult> history(String instrumentUid,int limit);
}
