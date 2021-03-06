package com.unisoft.algotrader.provider.ib;


import com.unisoft.algotrader.model.event.Event;
import com.unisoft.algotrader.model.event.bus.EventBusManager;
import com.unisoft.algotrader.model.event.data.Bar;
import com.unisoft.algotrader.model.event.data.DataType;
import com.unisoft.algotrader.model.event.data.MarketDataContainer;
import com.unisoft.algotrader.model.event.execution.ExecutionReport;
import com.unisoft.algotrader.model.event.execution.Order;
import com.unisoft.algotrader.model.refdata.Instrument;
import com.unisoft.algotrader.model.trading.ExecType;
import com.unisoft.algotrader.model.trading.OrdStatus;
import com.unisoft.algotrader.persistence.RefDataStore;
import com.unisoft.algotrader.provider.ProviderId;
import com.unisoft.algotrader.provider.ProviderManager;
import com.unisoft.algotrader.provider.data.*;
import com.unisoft.algotrader.provider.execution.ExecutionProvider;
import com.unisoft.algotrader.provider.ib.api.event.*;
import com.unisoft.algotrader.provider.ib.api.exception.RequestException;
import com.unisoft.algotrader.provider.ib.api.model.data.BookSide;
import com.unisoft.algotrader.provider.ib.api.model.data.Operation;
import com.unisoft.algotrader.provider.ib.api.model.data.TickType;
import com.unisoft.algotrader.provider.ib.api.model.fa.FinancialAdvisorDataType;
import com.unisoft.algotrader.provider.ib.api.model.order.OrderStatus;
import com.unisoft.algotrader.provider.ib.api.model.system.ClientMessageCode;
import com.unisoft.algotrader.utils.threading.disruptor.MultiEventProcessor;
import com.unisoft.algotrader.utils.threading.disruptor.waitstrategy.NoWaitStrategy;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Created by alex on 6/20/15.
 */
@Singleton
public class IBProvider extends MultiEventProcessor implements IBEventHandler, RealTimeDataProvider, HistoricalDataProvider, ExecutionProvider{

    private static final Logger LOG = LogManager.getLogger(IBProvider.class);

    public static final ProviderId PROVIDER_ID = ProviderId.IB;

    private final IBConfig config;
    private final RefDataStore refDataStore;
    public final EventBusManager eventBusManager;

    private final IBSocket ibSocket;

    private OrderRegistry orderRegistry= new OrderRegistry();
    private SubscriptionRegistry subscriptionRegistry = new SubscriptionRegistry();

    private AtomicInteger requestId = new AtomicInteger(1);
    private AtomicInteger orderId = null;

    @Inject
    public IBProvider(ProviderManager providerManager, IBConfig config, RefDataStore refDataStore, EventBusManager eventBusManager){
        super(new NoWaitStrategy(), eventBusManager.getOrderEventRB());
        this.refDataStore = refDataStore;
        this.config = config;
        this.eventBusManager = eventBusManager;
        providerManager.addExecutionProvider(this);
        providerManager.addHistoricalDataProvider(this);
        providerManager.addRealTimeDataProvider(this);
        this.ibSocket = new IBSocket(config, this, refDataStore);
    }

    @Override
    public void onEvent(Event event) {
        event.on(this);
    }

    @Override
    public void connect() {
        ibSocket.connect();
        synchronized (this) {
            while (this.orderId == null) {
                try {
                    this.wait();
                } catch (InterruptedException ignored) {
                }
            }
        }
    }

    @Override
    public boolean connected() {
        return ibSocket != null && ibSocket.isConnected();
    }

    @Override
    public void disconnect() {
        ibSocket.disconnect();
    }

    @Override
    public ProviderId providerId() {
        return PROVIDER_ID;
    }

    public RefDataStore getRefDataStore() {
        return refDataStore;
    }

    public EventBusManager getEventBusManager() {
        return eventBusManager;
    }

    public IBConfig getConfig() {
        return config;
    }

    public IBSocket getIbSocket() {
        return ibSocket;
    }

    private int nextRequestId(){
        return requestId.getAndIncrement();
    }

    private int nextOrderId(){
        return orderId.getAndIncrement();
    }

    public void onNextValidOrderIdEvent(final int nextValidOrderId){
        this.orderId = new AtomicInteger(nextValidOrderId);
        synchronized (this) {
            this.notify();
        }
    }

    @Override
    //TODO thread safe, lock
    public boolean subscribeMarketData(SubscriptionKey subscriptionKey){
        if (!subscriptionRegistry.hasSubscription(subscriptionKey)) {
            long requestId = nextRequestId();
            subscriptionRegistry.addSubscription(requestId, subscriptionKey);

            if (subscriptionKey instanceof MarketDepthSubscriptionKey) {
                ibSocket.subscribeMarketDepth(requestId, (MarketDepthSubscriptionKey) subscriptionKey);
            } else if (subscriptionKey.subscriptionType.type == DataType.Quote || subscriptionKey.subscriptionType.type == DataType.Trade) {
                ibSocket.subscribeMarketData(requestId, subscriptionKey, false);
            } else if (subscriptionKey.subscriptionType.type == DataType.Bar) {
                ibSocket.subscribeRealTimeData(requestId, subscriptionKey);
            } else {
                LOG.warn("unsupported type {}", subscriptionKey);
                throw new IllegalArgumentException("unsupported type " + subscriptionKey);
            }
            return true;
        }
        return false;
    }

    @Override
    public boolean unSubscribeMarketData(SubscriptionKey subscriptionKey){
        Long requestId = subscriptionRegistry.getSubscriptionId(subscriptionKey);
        if (requestId != null) {
            ibSocket.unsubscribeRealTimeData(requestId);
            subscriptionRegistry.removeSubscription(requestId);
            return true;
        }
        return false;
    }

    @Override
    public List<MarketDataContainer> loadHistoricalData(HistoricalSubscriptionKey subscriptionKey) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean subscribeHistoricalData(HistoricalSubscriptionKey subscriptionKey) {
        long requestId = nextRequestId();

        subscriptionRegistry.addSubscription(requestId, subscriptionKey);
        ibSocket.subscribeHistoricalData(requestId, subscriptionKey);
        return true;
    }

    public boolean unSubscribeHistoricalData(HistoricalSubscriptionKey subscriptionKey){
        Long requestId = subscriptionRegistry.getSubscriptionId(subscriptionKey);
        if (requestId != null) {
            ibSocket.unsubscribeHistoricalData(requestId);
            subscriptionRegistry.removeSubscription(requestId);
            return true;
        }
        return false;
    }

    private void requestFA(){
        ibSocket.requestFA(FinancialAdvisorDataType.GROUPS);
        ibSocket.requestFA(FinancialAdvisorDataType.PROFILE);
        ibSocket.requestFA(FinancialAdvisorDataType.ACCOUNT_ALIASES);
    }


    @Override
    public void onNewOrderRequest(Order order) {
        order.orderId(nextOrderId());
        orderRegistry.addOrder(order);
        ibSocket.placeOrder(order);
    }

    @Override
    public void onOrderUpdateRequest(Order updatedOrder){
        Optional<Order> optional = orderRegistry.getByClOrderId(updatedOrder.clOrderId);
        if(optional.isPresent()) {
            updatedOrder.orderId(optional.get().orderId());
            orderRegistry.addOrder(updatedOrder);
            ibSocket.placeOrder(updatedOrder);
        }
        else{
            throw new RequestException(ClientMessageCode.INTERNAL_ERROR, "Fail to replace order, exisitng order not found, clOrderId="+updatedOrder.clOrderId);
        }
    }

    @Override
    public void onOrderCancelRequest(Order order){
        Optional<Order> optional = orderRegistry.getByClOrderId(order.clOrderId);
        if (optional.isPresent()) {
            ibSocket.cancelOrder(optional.get().orderId());
        }
        else{
            throw new RequestException(ClientMessageCode.INTERNAL_ERROR, "Fail to cancel order, exisitng order not found, clOrderId="+order.clOrderId);
        }
    }


    private void emitMarketDataSnapshot(TickType tickType, DataRecord record) {
        switch (tickType) {
            case BID_PRICE:
            case BID_SIZE:
            case ASK_SIZE:
            case ASK_PRICE:
                if (record.quoteRequested) {
                    if (record.bid > 0 && record.bidSize > 0 && record.ask > 0 && record.askSize > 0) {
                        eventBusManager.getMarketDataEventBus().publishQuote(record.instId, System.currentTimeMillis(), record.bid, record.ask, record.bidSize, record.askSize);
                    }
                }

            case LAST_PRICE:
            case LAST_SIZE:
                if (record.tradeRequested) {
                    if (record.last > 0 && record.lastSize > 0) {
                        eventBusManager.getMarketDataEventBus().publishTrade(record.instId, System.currentTimeMillis(), record.last, record.lastSize);
                    }
                }

        }
    }


    @Override
    public void onTickSizeEvent(TickSizeEvent e) {
        onTickSizeEvent(e.requestId, e.type, e.size);
    }

    @Override
    public void onTickSizeEvent(long requestId, TickType tickType, int size) {
        Optional<DataRecord> optional = subscriptionRegistry.getDataRecord(requestId);
        if (optional.isPresent()){
            DataRecord record = optional.get();
            int prevSize = size;
            switch (tickType){
                case BID_SIZE:
                    prevSize = record.bidSize;
                    record.bidSize = size;
                    break;
                case ASK_SIZE:
                    prevSize = record.askSize;
                    record.askSize = size;
                    break;
                case LAST_SIZE:
                    prevSize = record.lastSize;
                    record.lastSize = size;
                    break;
                case VOLUME:
                    prevSize = record.volume;
                    record.volume = size;
                    break;
            }
            if(prevSize != size){
                emitMarketDataSnapshot(tickType, record);
            }

        }
    }

    @Override
    public void onTickPriceEvent(TickPriceEvent e) {
        onTickPriceEvent(e.requestId, e.type, e.price, e.autoExecute);
    }

    @Override
    public void onTickPriceEvent(long requestId, TickType tickType, double price, boolean autoExecute) {
        Optional<DataRecord> optional = subscriptionRegistry.getDataRecord(requestId);
        if (optional.isPresent()){
            DataRecord record = optional.get();
            double prevPrice = price;
            switch (tickType){
                case BID_PRICE:
                    prevPrice = record.bid;
                    record.bid = price;
                    break;
                case ASK_PRICE:
                    prevPrice = record.ask;
                    record.ask = price;
                    break;
                case LAST_PRICE:
                    prevPrice = record.last;
                    record.last = price;
                    break;
                case DAY_HIGH:
                    prevPrice = record.high;
                    record.high = price;
                    break;
                case DAY_LOW:
                    prevPrice = record.low;
                    record.low = price;
                    break;
                case CLOSE:
                    prevPrice = record.close;
                    record.close = price;
                    break;
            }
            if(prevPrice != price){
                emitMarketDataSnapshot(tickType, record);
            }

        }
    }


    @Override
    public void onRealTimeBarEvent(RealTimeBarEvent e) {
        onRealTimeBarEvent(e.requestId, e.timestamp, e.open, e.high, e.low, e.close, e.volume, e.weightedAveragePrice, e.tradeNumber);
    }

    @Override
    public void onRealTimeBarEvent(long requestId, long timestamp, double open, double high, double low, double close, long volume, double weightedAveragePrice, int tradeNumber) {
        Optional<DataRecord> optional = subscriptionRegistry.getDataRecord(requestId);
        SubscriptionKey key = subscriptionRegistry.getSubscriptionKey(requestId);
        if (optional.isPresent()) {
            DataRecord record = optional.get();
            record.open = open;
            record.high = high;
            record.low = low;
            record.close = close;
            //TODO
            record.volume = (int)volume;
            eventBusManager.getMarketDataEventBus().publishBar(record.instId, key.subscriptionType.barSize, System.currentTimeMillis(), open, high, low, close, volume, 0);
        }
    }

    @Override
    public void onMarketDepthLevelTwoUpdateEvent(MarketDepthLevelTwoUpdateEvent e) {
        onMarketDepthLevelTwoUpdateEvent(e.requestId, e.rowId, e.marketMakerName, e.operation, e.bookSide, e.price, e.size);
    }

    @Override
    public void onMarketDepthLevelTwoUpdateEvent(long requestId, int rowId, String marketMakerName, Operation operation, BookSide bookSide, double price, int size) {
        SubscriptionKey key = subscriptionRegistry.getSubscriptionKey(requestId);
        if (key != null) {
            //TODO fix providerID
            eventBusManager.getMarketDepthEventBus().publishMarketDepth(key.instId, System.currentTimeMillis(), 0, rowId, operation.mdOperation, bookSide.mdSide, price, size);
        }
    }

    @Override
    public void onMarketDepthUpdateEvent(MarketDepthUpdateEvent e) {
        onMarketDepthUpdateEvent(e.requestId, e.rowId, e.operation, e.bookSide, e.price, e.size);

    }

    @Override
    public void onMarketDepthUpdateEvent(long requestId, int rowId, Operation operation, BookSide bookSide, double price, int size) {
        SubscriptionKey key = subscriptionRegistry.getSubscriptionKey(requestId);
        if (key != null) {
            //TODO fix providerID
            eventBusManager.getMarketDepthEventBus().publishMarketDepth(key.instId, System.currentTimeMillis(), 0, rowId, operation.mdOperation, bookSide.mdSide, price, size);
        }
    }

    @Override
    public void onHistoricalDataEvent(HistoricalDataEvent e) {
        onHistoricalDataEvent(e.requestId, e.dateTime, e.open, e.high, e.low, e.close, e.volume, e.tradeNumber, e.weightedAveragePrice, e.hasGap);
    }

    @Override
    public void onHistoricalDataEvent(long requestId, Bar bar) {
        Optional<DataRecord> optional = subscriptionRegistry.getDataRecord(requestId);
        SubscriptionKey key = subscriptionRegistry.getSubscriptionKey(requestId);
        if (optional.isPresent()) {
            DataRecord record = optional.get();
            record.open = bar.open;
            record.high = bar.high;
            record.low = bar.low;
            record.close = bar.close;
            //TODO
            record.volume = (int)bar.volume;
            eventBusManager.getMarketDataEventBus().publishBar(record.instId, key.subscriptionType.barSize, System.currentTimeMillis(), bar.open, bar.high, bar.low, bar.close, bar.volume, 0);
        }
    }

    @Override
    public void onHistoricalDataEvent(long requestId, String dateTime, double open, double high, double low, double close, int volume, int tradeNumber, double weightedAveragePrice, boolean hasGap) {
        Optional<DataRecord> optional = subscriptionRegistry.getDataRecord(requestId);
        SubscriptionKey key = subscriptionRegistry.getSubscriptionKey(requestId);
        if (optional.isPresent()) {
            DataRecord record = optional.get();
            record.open = open;
            record.high = high;
            record.low = low;
            record.close = close;
            record.volume = volume;
            //TODO dateTime to time
            eventBusManager.getMarketDataEventBus().publishBar(record.instId, key.subscriptionType.barSize, System.currentTimeMillis(), open, high, low, close, volume, 0);
        }
    }

    @Override
    public void onHistoricalDataListEvent(HistoricalDataListEvent e) {
        for(HistoricalDataEvent de : e.historicalDataEvents){
            onHistoricalDataEvent(e.requestId, de.dateTime, de.open, de.high, de.low, de.close, de.volume, de.tradeNumber, de.weightedAveragePrice, de.hasGap);
        }
    }

    @Override
    public void onHistoricalDataListEvent(long requestId, List<Bar> bars) {
        for(Bar bar : bars){
            onHistoricalDataEvent(requestId, bar);
        }
    }

    @Override
    public void onExecutionReportEvent(ExecutionReportEvent e) {
        onExecutionReportEvent(e.requestId, e.instrument, e.executionReport);
    }

    @Override
    public void onExecutionReportEvent(long orderId, Instrument instrument, ExecutionReport executionReport) {
        Optional<Order> optional = orderRegistry.getByOrderId(orderId);
        if(optional.isPresent()){
            Order order = optional.get();
            if (order.ordStatus == OrdStatus.PendingNew){
                ExecutionReport er = new ExecutionReport();
                er.transactionTime = System.currentTimeMillis();
                er.clOrderId = order.clOrderId;
                er.orderId = order.orderId;
                er.execId = order.orderId; //TODO fix it
                er.instId = order.instId;
                er.ordType = order.ordType;
                er.side = order.side;
                er.tif = order.tif;
                er.limitPrice = order.limitPrice;
                er.stopPrice = order.stopPrice;
                er.ordQty = order.ordQty;
                er.lastPrice = 0;
                er.lastQty = 0;
                er.filledQty = order.filledQty;
                er.avgPrice = order.avgPrice;
                er.ordStatus = OrdStatus.New;
                er.execType = ExecType.New;

                eventBusManager.getExecutionEventBus().publishExecutionReport(er);
            }

            order.add(executionReport);

            //TODO update order with executionReport, calc avgPrice, cumQty, leaveQty

            if (order.leaveQty() > 0 ){
                executionReport.execType = ExecType.PartialFill;
                executionReport.ordStatus = OrdStatus.PartiallyFilled;
            }
            else {
                executionReport.execType = ExecType.Fill;
                executionReport.ordStatus = OrdStatus.Filled;
            }
            eventBusManager.getExecutionEventBus().publishExecutionReport(executionReport);
        }
    }

    @Override
    public void onOrderStatusUpdateEvent(OrderStatusUpdateEvent e) {
        onOrderStatusUpdateEvent(e.requestId, e.orderStatus, e.filledQuantity, e.remainingQuantity, e.averageFilledPrice, e.permanentId, e.parentOrderId, e.lastFilledPrice, e.clientId, e.heldCause);
    }

    @Override
    public void onOrderStatusUpdateEvent(long orderId, OrderStatus orderStatus, int filledQuantity, int remainingQuantity, double averageFilledPrice, int permanentId, int parentOrderId, double lastFilledPrice, int clientId, String heldCause) {
        Optional<Order> optional = orderRegistry.getByOrderId(orderId);
        if(optional.isPresent()){
            //todo create exec report

            boolean createReport = false;
            ExecType execType = ExecType.Undefined;
            OrdStatus ordStatus = OrdStatus.Undefined;

            Order order = optional.get();
            switch (orderStatus){
                case SUBMITTED:
                    if (order.filledQty ==0){
                        execType = ExecType.New;
                        ordStatus = OrdStatus.New;
                        createReport = true;
                    }
                    break;
                case PENDING_CANCEL:
                    execType = ExecType.PendingCancel;
                    ordStatus = OrdStatus.PendingCancel;
                    createReport = true;
                    break;
                case CANCELLED:
                    execType = ExecType.Cancelled;
                    ordStatus = OrdStatus.Cancelled;
                    createReport = true;
                    break;
                case INACTIVE:
                    execType = ExecType.Rejected;
                    ordStatus = OrdStatus.Rejected;
                    createReport =order.ordStatus != OrdStatus.Rejected;
                    break;
                default:
                    break;
            }

            if(createReport){
                ExecutionReport er = new ExecutionReport();
                er.transactionTime = System.currentTimeMillis();
                er.clOrderId = order.clOrderId;
                er.orderId = order.orderId;
                er.execId = order.orderId; //TODO fix it
                er.instId = order.instId;
                er.ordType = order.ordType;
                er.side = order.side;
                er.tif = order.tif;
                er.limitPrice = order.limitPrice;
                er.stopPrice = order.stopPrice;
                er.ordQty = order.ordQty;
                er.lastPrice = order.lastPrice;
                er.lastQty = order.lastQty;
                er.filledQty = order.filledQty;
                er.avgPrice = order.avgPrice;
                er.ordStatus = ordStatus;
                er.execType = execType;
                eventBusManager.getExecutionEventBus().publishExecutionReport(er);
            }
        }
    }


    @Override
    public void onAccountUpdateTimeEvent(AccountUpdateTimeEvent accountUpdateTimeEvent) {
        LOG.debug(accountUpdateTimeEvent);
    }

    @Override
    public void onAccountUpdateTimeEvent(String time) {
        LOG.debug("onAccountUpdateTimeEvent time {}", time);
    }


    @Override
    public void onAccountUpdateValueEvent(AccountUpdateValueEvent accountUpdateValueEvent) {
        LOG.debug(accountUpdateValueEvent);
    }

    @Override
    public void onAccountUpdateValueEvent(String key, String value, String currency, String accountName) {
        LOG.debug("onAccountUpdateValueEvent key {}, value {}, currency {}, accountName {}", key, value, currency, accountName);
    }


    @Override
    public void onPortfolioUpdateEvent(PortfolioUpdateEvent portfolioUpdateEvent) {
        LOG.debug(portfolioUpdateEvent);
    }

    @Override
    public void onPortfolioUpdateEvent(Instrument instrument, int marketPosition, double marketPrice, double marketValue, double averageCost, double unrealizedProfitAndLoss, double realizedProfitAndLoss, String accountName) {
        LOG.debug("onPortfolioUpdateEvent instrument {}, marketPosition {}, marketPrice {}, marketValue {}, averageCost {}, unrealizedProfitAndLoss {}, realizedProfitAndLoss {}, accountName {}",
                instrument, marketPosition, marketPrice, marketValue, averageCost, unrealizedProfitAndLoss, realizedProfitAndLoss, accountName);
    }
    @Override
    public void onManagedAccountListEvent(ManagedAccountListEvent managedAccountListEvent) {
        LOG.debug(managedAccountListEvent);
    }

    @Override
    public void onManagedAccountListEvent(String commaSeparatedAccountList) {
        LOG.debug("onManagedAccountListEvent commaSeparatedAccountList {}", commaSeparatedAccountList);
    }

    @Override
    public void onFinancialAdvisorConfigurationEvent(FinancialAdvisorConfigurationEvent financialAdvisorConfigurationEvent) {
        LOG.debug(financialAdvisorConfigurationEvent);
    }

    @Override
    public void onFinancialAdvisorConfigurationEvent(FinancialAdvisorDataType dataTypeValue, String xml) {
        LOG.debug("onFinancialAdvisorConfigurationEvent dataTypeValue {}, xml {}", dataTypeValue, xml);
    }
}
