package ru.ba6kir.tradelab.analysis.ranking.application;

import java.util.*;
import ru.ba6kir.tradelab.analysis.ranking.domain.RankingRun.Member;
import ru.ba6kir.tradelab.market.application.*;

/** Ticker resolution is explicit refresh work, never part of cached ranking retrieval. */
public class RankingUniverseService {
    private final InstrumentService instruments;private final List<String> tickers,uids;
    public RankingUniverseService(InstrumentService instruments,List<String> tickers,List<String> uids) { this.instruments=instruments;this.tickers=List.copyOf(tickers);this.uids=List.copyOf(uids); }
    public List<String> identifiers() { return uids.isEmpty()?tickers.stream().map(t -> "ticker:"+t).sorted().toList():uids.stream().map(u -> "uid:"+u).sorted().toList(); }
    public List<Member> resolve() {
        var members=new ArrayList<Member>();
        for (String identifier:identifiers()) {
            String value=identifier.substring(identifier.indexOf(':')+1);
            if (identifier.startsWith("uid:")) {
                members.add(new Member(identifier,value,value,instruments.getCached(value).isEmpty()?"UID_NOT_IN_LOCAL_CATALOG":null));continue;
            }
            try { members.add(new Member(identifier,value,instruments.resolve(value).uid(),null)); }
            catch (AmbiguousInstrumentException e) { members.add(new Member(identifier,value,null,"AMBIGUOUS_TICKER_USE_UID")); }
            catch (MarketException e) {
                if (e.reason()!=MarketException.Reason.NOT_FOUND) throw e; // Preserve the previous run on provider/configuration failure.
                members.add(new Member(identifier,value,null,"INSTRUMENT_NOT_FOUND"));
            }
        }
        return List.copyOf(members);
    }
}
