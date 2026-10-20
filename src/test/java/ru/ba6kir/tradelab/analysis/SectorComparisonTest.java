package ru.ba6kir.tradelab.analysis;

import java.util.*;
import org.junit.jupiter.api.Test;
import ru.ba6kir.tradelab.analysis.domain.comparison.SectorComparison;
import ru.ba6kir.tradelab.analysis.domain.metric.*;
import ru.ba6kir.tradelab.analysis.domain.sector.*;
import static ru.ba6kir.tradelab.analysis.domain.metric.AnalysisMetric.*;
import static ru.ba6kir.tradelab.analysis.domain.metric.FinancialObservation.*;
import static org.assertj.core.api.Assertions.*;
import static ru.ba6kir.tradelab.analysis.AnalysisTestData.*;

class SectorComparisonTest {
    final SectorComparison calculator=new SectorComparison();
    SectorComparison.Company company(int i,String value) { return new SectorComparison.Company(classification(i),List.of(obs(asset(i),PE,value,"RUB",ObservationType.TTM)),true); }
    SectorComparison.Result compare(List<SectorComparison.Company> peers) { var subject=company(0,"5"); return calculator.compare(subject.observations().getFirst(),subject,peers,5,NOW); }
    @Test void medianAndMidrankUseFiveOtherCompaniesWithoutOverallScore() {
        var r=compare(List.of(company(1,"1"),company(2,"3"),company(3,"5"),company(4,"7"),company(5,"9"),company(0,"5")));
        assertThat(r.status()).isEqualTo("AVAILABLE"); assertThat(r.median()).isEqualByComparingTo("5");
        assertThat(r.relativePositionPercent()).isEqualByComparingTo("50"); assertThat(r.validPeers()).isEqualTo(5);
        assertThat(r.universe()).hasSize(6);
    }
    @Test void smallSamplesHaveDescriptiveMedianWithoutPercentile() {
        var r=compare(List.of(company(1,"1"),company(2,"4")));
        assertThat(r.status()).isEqualTo("INSUFFICIENT_PEERS"); assertThat(r.median()).isEqualByComparingTo("2.5"); assertThat(r.relativePositionPercent()).isNull();
    }
    @Test void missingNegativeAndStaleValuesAreExcludedWithoutTrimmingExtremes() {
        var r=compare(List.of(company(1,null),company(2,"-1"),new SectorComparison.Company(classification(3),List.of(obs(asset(3),PE,"1","RUB",ObservationType.TTM)),false),company(4,"999999")));
        assertThat(r.validPeers()).isEqualTo(1); assertThat(r.median()).isEqualByComparingTo("999999");
        assertThat(r.universe()).filteredOn(p -> p.exclusion()!=null).hasSize(3);
    }
    @Test void differentPeriodCurrencyAndBusinessGroupDoNotCompare() {
        var subject=new SectorComparison.Company(classification(0),List.of(obs(REVENUE,"100")),true,
                new MetricProfile("test",Set.of(REVENUE,PE),Set.of()));
        var peers=List.of(new SectorComparison.Company(classification(1),List.of(obs(asset(1),REVENUE,"50","USD",ObservationType.TTM)),true),
                new SectorComparison.Company(classification(2),List.of(obs(asset(2),REVENUE,"50","RUB",ObservationType.ANNUAL)),true));
        var r=calculator.compare(subject.observations().getFirst(),subject,peers,5,NOW);
        assertThat(r.validPeers()).isZero(); assertThat(r.median()).isNull();
        var bank=new SectorClassification(ASSET,"issuer:0",SectorClassification.Sector.FINANCIALS,SectorClassification.BusinessType.BANK,
                SectorClassification.Source.MANUAL_VERIFIED,NOW,false,"test",null);
        assertThat(calculator.compare(obs(NET_DEBT_EBITDA,"1"),new SectorComparison.Company(bank,List.of(),true),List.of(),5,NOW).status()).isEqualTo("UNSUPPORTED_PROFILE");
    }
    @Test void severalAssetsOfOneIssuerCannotInflateCompanyCountAndConflictsExcludeTheIssuer() {
        var one=classification(1); var duplicate=new SectorClassification(asset(2),one.issuerKey(),one.sector(),one.businessType(),one.source(),NOW,false,"test",null);
        var identical=new SectorComparison.Company(duplicate,List.of(obs(asset(2),PE,"3","RUB",ObservationType.TTM)),true);
        assertThat(compare(List.of(company(1,"3"),identical)).validPeers()).isEqualTo(1);
        var conflicting=new SectorComparison.Company(duplicate,List.of(obs(asset(2),PE,"4","RUB",ObservationType.TTM)),true);
        var result=compare(List.of(company(1,"3"),conflicting)); assertThat(result.validPeers()).isZero();
        assertThat(result.universe()).allMatch(p -> p.exclusion().equals("CONFLICTING_SECURITIES_FOR_ISSUER"));
    }
}
