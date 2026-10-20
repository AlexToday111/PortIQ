package ru.ba6kir.tradelab.analysis.scoring.application.service;

import ru.ba6kir.tradelab.analysis.scoring.domain.model.ScoringInput;

public interface ScoringInputProvider {
    ScoringInput instrument(String instrumentUid);
    ScoringInput asset(String assetUid);
}
