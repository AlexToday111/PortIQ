package ru.ba6kir.tradelab.analysis.reporting.application;

import java.math.BigDecimal;
import java.util.*;
import ru.ba6kir.tradelab.analysis.reporting.domain.AnalysisExplanation;
import ru.ba6kir.tradelab.analysis.scoring.domain.model.ScoringResult;
import ru.ba6kir.tradelab.analysis.scoring.domain.profile.ScoringModel;
import static ru.ba6kir.tradelab.analysis.reporting.domain.AnalysisExplanation.Direction;

/** Localized deterministic templates. The midpoint is a presentation label, not a new score or a peer reference. */
public class AnalysisExplanationService {
    public List<AnalysisExplanation> explain(ScoringResult result,ScoringModel model) {
        if (result==null || result.input()==null) return List.of();
        var output=new ArrayList<AnalysisExplanation>();var profile=model==null?null:model.profiles().get(result.profileId());
        for (var c:result.contributions()) {
            var o=result.input().observations().stream().filter(x -> Objects.equals(x.id(),c.observationId())).findFirst().orElse(null);
            var rule=profile==null?null:profile.rules().stream().filter(x -> x.id().equals(c.ruleId())).findFirst().orElse(null);
            String threshold=rule==null?"Точная конфигурация правила недоступна":rule.direction()+"; "+rule.transformation()+"; "+rule.points().stream()
                    .map(p -> p.value().toPlainString()+" → "+p.score().toPlainString()).reduce((a,b) -> a+"; "+b).orElse("");
            Direction direction=c.metricScore()==null?Direction.UNAVAILABLE:c.metricScore().compareTo(BigDecimal.valueOf(50))>0?Direction.POSITIVE:
                    c.metricScore().compareTo(BigDecimal.valueOf(50))<0?Direction.NEGATIVE:Direction.NEUTRAL;
            String code=c.metricScore()==null?"METRIC_UNAVAILABLE":c.status().equals("CONTEXT_CAPPED")?"CONTEXT_CAP":direction==Direction.POSITIVE?"ABOVE_SCALE_MIDPOINT":direction==Direction.NEGATIVE?"BELOW_SCALE_MIDPOINT":"AT_SCALE_MIDPOINT";
            String text=c.metricScore()==null?"Метрика не участвует в расчёте: отсутствует пригодное подтверждённое наблюдение ["+c.reason()+"].":
                    c.status().equals("CONTEXT_CAPPED")?"Балл оценки стоимости ограничен нулём из-за подтверждённого условия финансового риска модели ["+c.reason()+"].":
                    "Метрика получила "+c.metricScore().stripTrailingZeros().toPlainString()+" из 100 по сохранённому правилу; вклад в столп: "
                            +c.contribution().stripTrailingZeros().toPlainString()+". "+switch(direction) {
                        case POSITIVE -> "Балл выше середины шкалы модели; это не сравнение с медианой отрасли.";
                        case NEGATIVE -> "Балл ниже середины шкалы модели и ограничивает оценку столпа.";
                        default -> "Балл соответствует середине шкалы модели.";
                    };
            output.add(new AnalysisExplanation(code,c.metric(),o==null?null:o.value(),o==null?c.metric().unit():o.unit(),o==null?null:o.currency(),
                    c.normalizedValue(),c.ruleId(),threshold,direction,c.contribution(),o==null?null:String.valueOf(o.observationType()),o==null?null:o.reportingPeriod(),
                    o==null?null:o.periodStart(),o==null?null:o.reportingEndDate(),o==null?null:o.providerAsOfDate(),c.observationId(),
                    o==null?null:o.source()+"/"+o.origin()+"; snapshot="+o.snapshotId()+"; inputs="+String.join(",",o.inputIds()),text));
        }
        return List.copyOf(output);
    }
}
