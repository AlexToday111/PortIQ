package ru.ba6kir.tradelab.portfolio.telegram;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import ru.ba6kir.tradelab.analysis.scoring.application.service.ScoringFingerprint;
import ru.ba6kir.tradelab.market.application.*;
import ru.ba6kir.tradelab.portfolio.application.service.*;
import ru.ba6kir.tradelab.portfolio.domain.model.LedgerTransaction.Type;
import ru.ba6kir.tradelab.telegram.command.RequestAwareTelegramCommand;
import static ru.ba6kir.tradelab.portfolio.telegram.PortfolioInput.*;
import static ru.ba6kir.tradelab.portfolio.telegram.PortfolioFormatter.*;

public class PortfolioCommand implements RequestAwareTelegramCommand {
    private final String name;private final PortfolioService portfolios;private final PortfolioTransactionService transactions;private final PortfolioSynchronizationService sync;private final ScoringFingerprint hashes;
    private final PortfolioFormatter text=new PortfolioFormatter();
    public PortfolioCommand(String name,PortfolioService portfolios,PortfolioTransactionService transactions,PortfolioSynchronizationService sync,ScoringFingerprint hashes){this.name=name;this.portfolios=portfolios;this.transactions=transactions;this.sync=sync;this.hashes=hashes;}
    @Override public String name(){return name;}
    @Override public boolean acceptsArguments(){return true;}
    @Override public String description(){return switch(name){case "portfolio"->"портфель; /portfolio create <имя>, /portfolio select <локальный UUID>";case "positions"->"остатки и локальная оценка по счетам";case "position"->"позиция и FIFO-лоты: /position <тикер|UID>";case "transactions"->"журнал: /transactions [1..50]";case "trade"->"запись учёта: /trade buy|sell <тикер|UID> <акции> <цена> <комиссия> [ISO-дата]";case "cash"->"/cash deposit|withdraw|fee|tax <RUB> [ISO-дата]; adjust <сумма> <основание>";case "income"->"полученные деньги: /income dividend <тикер|UID> <RUB> [ISO-дата]";case "opening"->"/opening <UID> <акции> <общая стоимость RUB|unknown> <ISO-дата> <основание>";case "reverse"->"сторно записи: /reverse <локальный UUID операции> <основание>";case "confirm"->"подтвердить подготовленную запись: /confirm <код>";case "accounts"->"счета; create <имя>, select <UUID>, discover, link <код> <ISO-начало истории> [имя]";case "sync"->"read-only синхронизация выбранного связанного счёта";case "reconcile"->"сверка сохранённых остатков без корректировок";default->"состояние портфеля, истории, сверки и цен";};}
    @Override public String execute(List<String> args,String requestKey){
        try{
            if(requestKey==null || requestKey.length()>180)throw new IllegalArgumentException("INVALID_REQUEST_KEY");
            return switch(name){
                case "portfolio"->portfolio(args,requestKey);
                case "accounts"->accounts(args,requestKey);
                case "positions"->{size(args,0,0);yield text.positions(portfolios.value());}
                case "position"->{size(args,1,1);yield text.detail(portfolios.position(args.getFirst()));}
                case "transactions"->{size(args,0,1);yield text.transactions(portfolios.transactions(args.isEmpty()?10:Integer.parseInt(args.getFirst())));}
                case "trade","cash","income","opening","reverse"->text.confirmation(transactions.prepare(parse(args),requestKey,hashes.hash(List.of(name,args))));
                case "confirm"->{size(args,1,1);var saved=transactions.confirm(uuid(args.getFirst()));yield "Запись подтверждена: "+saved.id()+"; "+type(saved.type())+". Повторное подтверждение не создаёт новую запись. /portfolio /transactions";}
                case "sync"->{size(args,0,0);var result=sync.synchronize();yield result.report()==null?"Синхронизация: "+result.status()+" ["+safe(result.reason())+"]. Предыдущие записи сохранены.":
                    "Read-only синхронизация: "+result.status()+"\nСоздано компонентов журнала: "+result.report().created()+"; без изменений: "+result.report().unchanged()+"; исправлено: "+result.report().corrected()+"; неподдерживаемых/неразрешённых источников: "+result.report().unsupported()+"\nИнтервал: "+time(result.report().from())+" — "+time(result.report().to())+"\n"+text.reconcile(result.report().reconciliation());}
                case "reconcile"->{size(args,0,0);yield text.reconcile(sync.reconcile());}
                default->{size(args,0,0);var status=portfolios.status();yield text.summary(status.valuation())+"\nАктивный счёт: "+safe(status.selected().account().name())+"; источник: "+status.selected().account().source()+"\nПоследняя синхронизация: "+time(status.synchronization().lastSuccess())+"; checkpoint: "+time(status.synchronization().checkpoint())+"; ошибка: "+safe(status.synchronization().lastError())+"\nСверка: "+(status.reconciliation()==null?"нет наблюдений":time(status.reconciliation().checkedAt())+"; /reconcile")+"\nПозиции пересчитаны из журнала на дату оценки.";}
            };
        }catch(AmbiguousInstrumentException e){var message=new StringBuilder("Тикер неоднозначен; используйте точный UID:\n");e.matches().stream().limit(10).forEach(i->message.append(i.uid()).append(" ").append(safe(i.ticker())).append("/").append(safe(i.classCode())).append("\n"));return message.toString();}
        catch(MarketException e){return "Данные инструмента недоступны ["+e.reason()+"]. Для ручного учёта без API укажите точный UID; без локальных метаданных оценка будет неполной.";}
        catch(IllegalArgumentException e){String code=e.getMessage()!=null && e.getMessage().matches("[A-Z_0-9]{1,100}")?e.getMessage():"INVALID_ARGUMENTS";return "Запись не выполнена ["+code+"]. "+hint(code)+"\n"+description();}
        catch(RuntimeException e){return "Портфельный учёт временно недоступен [INTERNAL]. Запись не подтверждена; проверьте /transactions перед повтором.";}
    }
    private String portfolio(List<String> args,String key){
        if(args.isEmpty())return portfolios.selected().isEmpty()?"Нет активного портфеля. Создайте: /portfolio create <имя>. Будет создан пустой ручной счёт без вымышленных остатков.":text.summary(portfolios.value());
        if(args.getFirst().equals("create")){size(args,2,20);var selected=portfolios.create(PortfolioInput.name(String.join(" ",args.subList(1,args.size()))),key);return "Создан портфель "+safe(selected.portfolio().name())+" и пустой ручной счёт. /cash deposit <RUB> начнёт подтверждение пополнения.";}
        if(args.getFirst().equals("select")){size(args,2,2);portfolios.selectPortfolio(uuid(args.get(1)));return "Портфель выбран.";}
        if(args.getFirst().equals("list")){var out=new StringBuilder("Локальные портфели:\n");portfolios.portfolios().forEach(p->out.append(safe(p.name())).append(" /portfolio select ").append(p.id()).append("\n"));return out.toString();}
        throw new IllegalArgumentException("INVALID_ARGUMENTS");
    }
    private String accounts(List<String> args,String key){
        if(args.isEmpty())return text.accounts(portfolios.accounts());
        return switch(args.getFirst()){
            case "create"->{size(args,2,20);var account=portfolios.createAccount(String.join(" ",args.subList(1,args.size())),key);yield "Ручной счёт создан. Выбрать: /accounts select "+account.id();}
            case "select"->{size(args,2,2);portfolios.selectAccount(uuid(args.get(1)));yield "Локальный счёт выбран. Последующие подтверждения сохраняются в счёт, указанный в их сводке.";}
            case "discover"->{size(args,1,1);var found=sync.discover();var out=new StringBuilder("Счета брокера: выберите явно, первый не выбирается автоматически. Идентификаторы ниже локальные.\n");for(var candidate:found)out.append(safe(candidate.get("name"))).append("; ").append(safe(candidate.get("account_type"))).append("; read-only: ").append(candidate.get("read_only")).append("; /accounts link ").append(candidate.get("id")).append(" <ISO-начало истории> <имя>\n");yield out.toString();}
            case "link"->{size(args,3,20);boolean reviewed=args.getLast().equals("distinct-manual-accounts");int end=args.size()-(reviewed?1:0);var account=sync.link(uuid(args.get(1)),PortfolioInput.time(args.get(2)),end>3?PortfolioInput.name(String.join(" ",args.subList(3,end))):"Счёт T-Invest",reviewed);yield "Связан отдельный счёт read-only. Ручная история не переносится и не объединяется с брокерской. Не дублируйте один реальный счёт в общей оценке. Выбрать: /accounts select "+account.id();}
            default->throw new IllegalArgumentException("INVALID_ARGUMENTS");
        };
    }
    private PortfolioTransactionService.Command parse(List<String> args){
        if(name.equals("trade")){size(args,5,6);Type type=switch(args.getFirst()){case "buy"->Type.BUY;case "sell"->Type.SELL;default->throw new IllegalArgumentException("BUY_OR_SELL_REQUIRED");};
            return new PortfolioTransactionService.Command(type,args.get(1),decimal(args.get(2),false),decimal(args.get(3),false),null,decimal(args.get(4),false),args.size()==6?PortfolioInput.time(args.get(5)):null,null,null);}
        if(name.equals("income")){size(args,3,4);if(!args.getFirst().equals("dividend"))throw new IllegalArgumentException("DIVIDEND_RECEIPT_ONLY");return new PortfolioTransactionService.Command(Type.DIVIDEND_RECEIVED,args.get(1),null,null,decimal(args.get(2),false),BigDecimal.ZERO,args.size()==4?PortfolioInput.time(args.get(3)):null,"Owner confirms actual account receipt",null);}
        if(name.equals("opening")){size(args,5,30);return new PortfolioTransactionService.Command(Type.OPENING_POSITION,args.getFirst(),decimal(args.get(1),false),null,args.get(2).equals("unknown")?null:decimal(args.get(2),false),BigDecimal.ZERO,PortfolioInput.time(args.get(3)),String.join(" ",args.subList(4,args.size())),null);}
        if(name.equals("reverse")){size(args,2,20);return new PortfolioTransactionService.Command(Type.REVERSAL,null,null,null,null,BigDecimal.ZERO,null,String.join(" ",args.subList(1,args.size())),uuid(args.getFirst()));}
        size(args,2,20);Type type=switch(args.getFirst()){case "deposit"->Type.DEPOSIT;case "withdraw"->Type.WITHDRAWAL;case "fee"->Type.BROKER_FEE;case "tax"->Type.TAX_WITHHELD;case "adjust"->Type.CASH_ADJUSTMENT;default->throw new IllegalArgumentException("INVALID_CASH_OPERATION");};
        if(type==Type.CASH_ADJUSTMENT){size(args,3,20);return new PortfolioTransactionService.Command(type,null,null,null,decimal(args.get(1),true),BigDecimal.ZERO,null,String.join(" ",args.subList(2,args.size())),null);}
        size(args,2,3);return new PortfolioTransactionService.Command(type,null,null,null,decimal(args.get(1),false),BigDecimal.ZERO,args.size()==3?PortfolioInput.time(args.get(2)):null,null,null);
    }
    private static void size(List<String> args,int min,int max){if(args.size()<min ||args.size()>max)throw new IllegalArgumentException("INVALID_ARGUMENTS");}
    private String hint(String code){return switch(code){case "INSUFFICIENT_CASH_AT_EXECUTION"->"Недостаточно денег на дату операции. Проверьте пополнения и даты.";case "SALE_EXCEEDS_AVAILABLE_SHARES"->"Продажа превышает доступные акции на эту дату; короткие позиции не поддерживаются.";case "NO_ACTIVE_PORTFOLIO"->"Создайте /portfolio create <имя>.";case "MANUAL_ACCOUNT_RECONCILIATION_UNAVAILABLE"->"У ручного счёта нет брокерского источника для сверки.";case "MANUAL_HISTORY_REVIEW_REQUIRED"->"В портфеле есть ручная история. Для того же реального счёта создайте отдельный портфель или явно устраните дубли через сторно. Флаг distinct-manual-accounts допускается только после сверки, что ручные счета относятся к другим реальным счетам.";case "BROKER_SYNC_DISABLED"->"Синхронизация брокера выключена в конфигурации; ручной учёт доступен.";case "BROKER_ACCOUNT_MANUAL_OVERLAP_FORBIDDEN"->"Для связанного счёта источником операций является брокер; ручное дублирование запрещено.";case "INVALID_DATE_USE_ISO_OFFSET","FUTURE_EXECUTION_DATE"->"Дата: YYYY-MM-DD (Europe/Moscow) или ISO-время с часовым смещением; будущая дата не допускается.";case "CONFIRMATION_EXPIRED"->"Срок подтверждения истёк; подготовьте запись заново.";default->"Проверьте ввод и состояние журнала; суммы указываются явно в RUB.";};}
}
