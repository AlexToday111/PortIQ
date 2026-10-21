package ru.ba6kir.tradelab.portfolio.infrastructure.persistence;

import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.function.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import ru.ba6kir.tradelab.portfolio.application.port.*;
import ru.ba6kir.tradelab.portfolio.domain.model.*;
import ru.ba6kir.tradelab.portfolio.domain.accounting.AccountingResult;
import ru.ba6kir.tradelab.portfolio.domain.reconciliation.ReconciliationResult;

public class JdbcPortfolioStore implements PortfolioStore {
    private final JdbcTemplate jdbc;private final ObjectMapper json;private final TransactionTemplate tx,read;private final Clock clock;
    public JdbcPortfolioStore(JdbcTemplate jdbc,ObjectMapper json,PlatformTransactionManager manager,Clock clock) {
        this.jdbc=jdbc;this.json=json;this.clock=clock;tx=new TransactionTemplate(manager);read=new TransactionTemplate(manager);
        read.setReadOnly(true);read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);read.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(java.io.IOException e){throw new IllegalStateException("PORTFOLIO_SERIALIZATION_FAILED");}}
    private <T>T decode(String text,Class<T> type){try{return json.readValue(text,type);}catch(java.io.IOException e){throw new IllegalStateException("PORTFOLIO_READ_FAILED");}}
    private static OffsetDateTime utc(Instant t){return t==null?null:t.atOffset(ZoneOffset.UTC);}
    private static Instant instant(ResultSet r,String key)throws SQLException{var t=r.getObject(key,OffsetDateTime.class);return t==null?null:t.toInstant();}
    private static UUID uuid(ResultSet r,String key)throws SQLException{return r.getObject(key,UUID.class);}
    private static Instant sourceTime(Object value){return value==null?null:value instanceof OffsetDateTime t?t.toInstant():((Timestamp)value).toInstant();}
    private Portfolio portfolio(ResultSet r,int n)throws SQLException{return new Portfolio(uuid(r,"id"),r.getString("name"),r.getString("description"),r.getString("base_currency"),r.getString("status"),instant(r,"created_at"),instant(r,"updated_at"));}
    private PortfolioAccount mapAccount(ResultSet r,int n)throws SQLException{return new PortfolioAccount(uuid(r,"id"),uuid(r,"portfolio_id"),r.getString("name"),PortfolioAccount.AccountType.valueOf(r.getString("account_type")),PortfolioAccount.Source.valueOf(r.getString("source_type")),r.getString("broker"),r.getString("external_account_id"),r.getBoolean("sync_enabled"),instant(r,"history_from"),instant(r,"broker_opened_at"),instant(r,"checkpoint"),instant(r,"last_successful_sync_at"),instant(r,"created_at"),instant(r,"updated_at"));}
    @Override public Optional<Selection> selected(){return readConsistently(() -> jdbc.query("SELECT portfolio_id,account_id FROM portfolio_owner_selection WHERE id=1",(r,n)->{
        var p=uuid(r,"portfolio_id");var a=uuid(r,"account_id");return p==null || a==null?null:new Selection(jdbc.queryForObject("SELECT * FROM portfolios WHERE id=?",this::portfolio,p),account(a));}).stream().filter(Objects::nonNull).findFirst());}
    @Override public List<Portfolio> portfolios(){return jdbc.query("SELECT * FROM portfolios ORDER BY created_at,id",this::portfolio);}
    @Override public Selection createPortfolio(String name,String key,Instant at){return Objects.requireNonNull(tx.execute(s -> {
        jdbc.update("INSERT INTO portfolios(id,name,creation_key,created_at,updated_at) VALUES(?,?,?,?,?) ON CONFLICT(creation_key) DO NOTHING",UUID.randomUUID(),name,key,utc(at),utc(at));
        var p=Objects.requireNonNull(jdbc.queryForObject("SELECT * FROM portfolios WHERE creation_key=?",this::portfolio,key));
        if(!name.equals(p.name())) throw new IllegalArgumentException("IDEMPOTENCY_CONFLICT");
        var a=createAccount(p.id(),"Ручной счёт",PortfolioAccount.AccountType.MANUAL,key+":manual",at);selectAccount(a.id());return new Selection(p,a);
    }));}
    @Override public void selectPortfolio(UUID id){var accounts=accounts(id);if(accounts.isEmpty())throw new IllegalArgumentException("PORTFOLIO_NOT_FOUND");selectAccount(accounts.getFirst().id());}
    @Override public List<PortfolioAccount> accounts(UUID id){return jdbc.query("SELECT * FROM portfolio_accounts WHERE portfolio_id=? ORDER BY created_at,id",this::mapAccount,id);}
    @Override public PortfolioAccount account(UUID id){return jdbc.query("SELECT * FROM portfolio_accounts WHERE id=?",this::mapAccount,id).stream().findFirst().orElseThrow(()->new IllegalArgumentException("ACCOUNT_NOT_FOUND"));}
    @Override public PortfolioAccount createAccount(UUID portfolio,String name,PortfolioAccount.AccountType type,String key,Instant at){
        jdbc.update("INSERT INTO portfolio_accounts(id,portfolio_id,name,account_type,source_type,creation_key,created_at,updated_at) VALUES(?,?,?,?,'MANUAL',?,?,?) ON CONFLICT(creation_key) DO NOTHING",UUID.randomUUID(),portfolio,name,type.name(),key,utc(at),utc(at));
        var a=Objects.requireNonNull(jdbc.queryForObject("SELECT * FROM portfolio_accounts WHERE creation_key=?",this::mapAccount,key));
        if(!a.portfolioId().equals(portfolio)||!a.name().equals(name)||a.accountType()!=type)throw new IllegalArgumentException("IDEMPOTENCY_CONFLICT");return a;
    }
    @Override public void selectAccount(UUID id){var a=account(id);jdbc.update("UPDATE portfolio_owner_selection SET portfolio_id=?,account_id=? WHERE id=1",a.portfolioId(),id);}
    @Override public List<UUID> discover(List<BrokerPortfolioProvider.BrokerAccount> accounts,Instant at){return Objects.requireNonNull(tx.execute(s -> accounts.stream().map(a->{
        jdbc.update("""
                INSERT INTO broker_account_candidates(id,external_id,name,account_type,status,read_only,opened_at,discovered_at) VALUES(?,?,?,?,?,?,?,?)
                ON CONFLICT(external_id) DO UPDATE SET name=EXCLUDED.name,account_type=EXCLUDED.account_type,status=EXCLUDED.status,read_only=EXCLUDED.read_only,opened_at=EXCLUDED.opened_at,discovered_at=EXCLUDED.discovered_at
                """,UUID.randomUUID(),a.externalId(),a.name(),a.accountType(),a.status(),a.readOnly(),utc(a.openedAt()),utc(at));
        return jdbc.queryForObject("SELECT id FROM broker_account_candidates WHERE external_id=?",UUID.class,a.externalId());}).toList()));}
    @Override public List<Map<String,Object>> discovered(){return jdbc.queryForList("SELECT id,name,account_type,status,read_only,discovered_at FROM broker_account_candidates ORDER BY id");}
    @Override public PortfolioAccount link(UUID portfolio,UUID candidate,Instant from,String name,boolean reviewed,Instant at){return Objects.requireNonNull(tx.execute(s->{
        var c=jdbc.queryForMap("SELECT * FROM broker_account_candidates WHERE id=? FOR UPDATE",candidate);
        if(!Boolean.TRUE.equals(c.get("read_only"))||!"ACCOUNT_STATUS_OPEN".equals(c.get("status"))
                ||!Set.of("ACCOUNT_TYPE_TINKOFF","ACCOUNT_TYPE_TINKOFF_IIS").contains(c.get("account_type")))throw new IllegalArgumentException("BROKER_ACCOUNT_NOT_READ_ONLY_OR_SUPPORTED");
        var discovered=sourceTime(c.get("discovered_at"));if(!discovered.plus(Duration.ofMinutes(15)).isAfter(at))throw new IllegalArgumentException("ACCOUNT_DISCOVERY_EXPIRED");
        String external=(String)c.get("external_id");
        var existing=jdbc.query("SELECT * FROM portfolio_accounts WHERE external_account_id=?",this::mapAccount,external);
        if(!existing.isEmpty()){if(!existing.getFirst().portfolioId().equals(portfolio))throw new IllegalArgumentException("BROKER_ACCOUNT_ALREADY_LINKED");return existing.getFirst();}
        var id=UUID.randomUUID();jdbc.update("""
                INSERT INTO portfolio_accounts(id,portfolio_id,name,account_type,source_type,broker,external_account_id,sync_enabled,history_from,broker_opened_at,creation_key,created_at,updated_at,manual_overlap_reviewed_at)
                VALUES(?,?,?,?,'T_INVEST','T_INVEST',?,TRUE,?,?,?,?,?,?)
                """,id,portfolio,name,"ACCOUNT_TYPE_TINKOFF_IIS".equals(c.get("account_type"))?"IIS":"BROKERAGE",external,utc(from),utc(sourceTime(c.get("opened_at"))),"broker:"+candidate,utc(at),utc(at),reviewed?utc(at):null);return account(id);
    }));}
    @Override public <T>T locked(UUID id,Function<LockedAccount,T> work){return tx.execute(s -> {var a=jdbc.query("SELECT * FROM portfolio_accounts WHERE id=? FOR UPDATE",this::mapAccount,id).stream().findFirst().orElseThrow(()->new IllegalArgumentException("ACCOUNT_NOT_FOUND"));return work.apply(new Scope(a));});}
    @Override public <T>T readConsistently(Supplier<T> work){return read.execute(s -> work.get());}
    @Override public List<LedgerTransaction> transactions(UUID id){return jdbc.query("SELECT evidence FROM portfolio_transactions WHERE account_id=? ORDER BY executed_at,id",(r,n)->decode(r.getString(1),LedgerTransaction.class),id);}
    private Pending pending(ResultSet r,int n)throws SQLException{return new Pending(uuid(r,"token"),r.getString("request_key"),r.getString("payload_hash"),decode(r.getString("draft"),LedgerTransaction.class),instant(r,"expires_at"),uuid(r,"confirmed_transaction_id"));}
    @Override public Optional<Pending> pendingByRequest(String key){return jdbc.query("SELECT * FROM portfolio_requests WHERE request_key=?",this::pending,key).stream().findFirst();}
    @Override public Optional<Pending> pendingByToken(UUID token){return jdbc.query("SELECT * FROM portfolio_requests WHERE token=?",this::pending,token).stream().findFirst();}
    private final class Scope implements LockedAccount {
        final PortfolioAccount a;Scope(PortfolioAccount a){this.a=a;}
        public PortfolioAccount account(){return a;}
        public List<LedgerTransaction> transactions(){return JdbcPortfolioStore.this.transactions(a.id());}
        public LedgerTransaction append(LedgerTransaction t){
            if(!a.id().equals(t.accountId()))throw new IllegalArgumentException("ACCOUNT_MISMATCH");
            jdbc.update("""
                    INSERT INTO portfolio_transactions(id,account_id,transaction_type,instrument_uid,quantity,price,gross_amount,commission,currency,executed_at,recorded_at,source_type,external_operation_id,source_reference,idempotency_key,correction_reference,status,evidence)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,CAST(? AS JSONB)) ON CONFLICT(account_id,idempotency_key) DO NOTHING
                    """,t.id(),a.id(),t.type().name(),t.instrumentUid(),t.quantity(),t.price(),t.grossAmount(),t.commission(),t.currency(),utc(t.executedAt()),utc(t.recordedAt()),t.source().name(),t.externalOperationId(),t.sourceReference(),t.idempotencyKey(),t.correctionReference(),t.status(),encode(t));
            var saved=jdbc.queryForObject("SELECT evidence FROM portfolio_transactions WHERE account_id=? AND idempotency_key=?",(r,n)->decode(r.getString(1),LedgerTransaction.class),a.id(),t.idempotencyKey());
            if(!t.equals(saved))throw new IllegalArgumentException("IDEMPOTENCY_CONFLICT");return saved;
        }
        public void projection(AccountingResult result){
            if(!a.id().equals(result.accountId()))throw new IllegalArgumentException("ACCOUNT_MISMATCH");
            jdbc.update("DELETE FROM lot_disposals WHERE acquisition_lot_id IN(SELECT origin_transaction_id FROM acquisition_lots WHERE account_id=?)",a.id());
            jdbc.update("DELETE FROM acquisition_lots WHERE account_id=?",a.id());jdbc.update("DELETE FROM portfolio_position_projections WHERE account_id=?",a.id());
            for(var l:result.lots())jdbc.update("INSERT INTO acquisition_lots VALUES(?,?,?,?,?,?,?,?,?)",l.transactionId(),a.id(),l.instrumentUid(),l.currency(),l.initialQuantity(),l.remainingQuantity(),l.originalCost(),l.remainingCost(),utc(l.acquiredAt()));
            for(var d:result.disposals())jdbc.update("INSERT INTO lot_disposals VALUES(?,?,?,?,?)",d.saleTransactionId(),d.acquisitionTransactionId(),d.quantity(),d.allocatedCost(),d.allocatedSaleCommission());
            for(var p:result.positions())jdbc.update("INSERT INTO portfolio_position_projections VALUES(?,?,?,?,?,?,?,?)",a.id(),p.instrumentUid(),p.currency(),p.quantity(),p.remainingCost(),p.averageCost(),p.costQuality(),utc(result.calculatedAt()));
        }
        public Pending pending(String key){return pendingByRequest(key).orElse(null);}public Pending pending(UUID token){return pendingByToken(token).orElse(null);}
        public Pending pending(Pending p){jdbc.update("INSERT INTO portfolio_requests(token,account_id,request_key,payload_hash,draft,expires_at) VALUES(?,?,?,?,CAST(? AS JSONB),?) ON CONFLICT(request_key) DO NOTHING",p.token(),a.id(),p.requestKey(),p.payloadHash(),encode(p.transaction()),utc(p.expiresAt()));var stored=pending(p.requestKey());if(!stored.payloadHash().equals(p.payloadHash())||!stored.transaction().accountId().equals(a.id()))throw new IllegalArgumentException("IDEMPOTENCY_CONFLICT");return stored;}
        public void confirm(UUID token,UUID id){jdbc.update("UPDATE portfolio_requests SET confirmed_transaction_id=? WHERE token=? AND account_id=?",id,token,a.id());}
        public List<SourceOperation> sources(){return jdbc.query("SELECT * FROM broker_source_operations WHERE account_id=? ORDER BY external_operation_id",(r,n)->new SourceOperation(uuid(r,"id"),r.getString("external_operation_id"),r.getString("identity_key"),r.getString("source_fingerprint"),decode(r.getString("evidence"),BrokerPortfolioProvider.Operation.class),r.getString("mapping_status"),r.getString("reason")),a.id());}
        public void source(SourceOperation o,Instant at){
            jdbc.update("INSERT INTO broker_operation_versions VALUES(?,?,?,?,CAST(? AS JSONB)) ON CONFLICT DO NOTHING",a.id(),o.externalId(),o.fingerprint(),utc(at),encode(o.operation()));
            jdbc.update("""
                    INSERT INTO broker_source_operations VALUES(?,?,?,?,?,CAST(? AS JSONB),?,?,?,?) ON CONFLICT(account_id,external_operation_id)
                    DO UPDATE SET identity_key=EXCLUDED.identity_key,source_fingerprint=EXCLUDED.source_fingerprint,evidence=EXCLUDED.evidence,mapping_status=EXCLUDED.mapping_status,reason=EXCLUDED.reason,last_seen=EXCLUDED.last_seen
                    """,o.id(),a.id(),o.externalId(),o.identityKey(),o.fingerprint(),encode(o.operation()),o.mappingStatus(),o.reason(),utc(at),utc(at));
        }
        public void completeSync(Instant token,SyncReport report,BrokerPortfolioProvider.Snapshot snapshot){
            var state=syncState(a.id());if(!Objects.equals(token,state.lastAttempt())||state.leaseUntil()==null||!state.leaseUntil().isAfter(clock.instant())||!Objects.equals(state.runId(),report.id()))throw new IllegalStateException("SYNC_LEASE_SUPERSEDED");
            jdbc.update("INSERT INTO broker_account_snapshots VALUES(?,?,?,CAST(? AS JSONB))",UUID.randomUUID(),a.id(),utc(snapshot.capturedAt()),encode(snapshot));reconciliation(report.reconciliation());
            jdbc.update("UPDATE broker_sync_runs SET finished_at=?,status=?,operations_created=?,operations_unchanged=?,operations_corrected=?,operations_unsupported=?,result=CAST(? AS JSONB) WHERE id=?",utc(report.completedAt()),report.status(),report.created(),report.unchanged(),report.corrected(),report.unsupported(),encode(report),report.id());
            jdbc.update("UPDATE portfolio_accounts SET checkpoint=?,last_successful_sync_at=?,updated_at=? WHERE id=?",utc(report.to()),utc(report.completedAt()),utc(report.completedAt()),a.id());
            jdbc.update("UPDATE broker_sync_state SET lease_until=NULL,last_error=NULL WHERE account_id=?",a.id());
        }
        public void reconciliation(ReconciliationResult r){jdbc.update("INSERT INTO broker_reconciliation_results VALUES(?,?,?,CAST(? AS JSONB))",r.id(),a.id(),utc(r.checkedAt()),encode(r));}
    }
    @Override public Optional<SyncState> startSync(UUID account,Instant token,Duration cooldown){return locked(account,a->{
        jdbc.update("INSERT INTO broker_sync_state(account_id) VALUES(?) ON CONFLICT DO NOTHING",account);var state=syncState(account);
        if(state.leaseUntil()!=null && state.leaseUntil().isAfter(token) || state.lastAttempt()!=null && state.lastAttempt().plus(cooldown).isAfter(token))return Optional.empty();
        UUID run=UUID.randomUUID();jdbc.update("INSERT INTO broker_sync_runs(id,account_id,started_at,status) VALUES(?,?,?,'RUNNING')",run,account,utc(token));
        jdbc.update("UPDATE broker_sync_state SET run_id=?,last_attempt=?,lease_until=?,last_error=NULL WHERE account_id=?",run,utc(token),utc(token.plus(Duration.ofMinutes(10))),account);return Optional.of(syncState(account));
    });}
    @Override public void failSync(UUID account,Instant token,String code){locked(account,a->{var state=syncState(account);if(token.equals(state.lastAttempt())){
        jdbc.update("UPDATE broker_sync_runs SET finished_at=?,status='FAILED',error_summary=? WHERE id=?",utc(clock.instant()),code,state.runId());jdbc.update("UPDATE broker_sync_state SET lease_until=NULL,last_error=? WHERE account_id=?",code,account);}return null;});}
    @Override public SyncState syncState(UUID account){var a=account(account);return jdbc.query("SELECT * FROM broker_sync_state WHERE account_id=?",(r,n)->new SyncState(uuid(r,"run_id"),instant(r,"last_attempt"),instant(r,"lease_until"),a.checkpoint(),a.lastSuccessfulSyncAt(),r.getString("last_error")),account).stream().findFirst().orElse(new SyncState(null,null,null,a.checkpoint(),a.lastSuccessfulSyncAt(),null));}
    @Override public Optional<SyncReport> lastSync(UUID account){return jdbc.query("SELECT result FROM broker_sync_runs WHERE account_id=? AND result IS NOT NULL ORDER BY finished_at DESC LIMIT 1",(r,n)->decode(r.getString(1),SyncReport.class),account).stream().findFirst();}
    @Override public Optional<BrokerPortfolioProvider.Snapshot> brokerSnapshot(UUID account){return jdbc.query("SELECT evidence FROM broker_account_snapshots WHERE account_id=? ORDER BY captured_at DESC,id DESC LIMIT 1",(r,n)->decode(r.getString(1),BrokerPortfolioProvider.Snapshot.class),account).stream().findFirst();}
    @Override public Optional<ReconciliationResult> reconciliation(UUID account){return jdbc.query("SELECT evidence FROM broker_reconciliation_results WHERE account_id=? ORDER BY checked_at DESC,id DESC LIMIT 1",(r,n)->decode(r.getString(1),ReconciliationResult.class),account).stream().findFirst();}
}
