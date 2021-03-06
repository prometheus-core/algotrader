package com.unisoft.algotrader.provider.ib.api.deserializer;

import com.unisoft.algotrader.model.event.execution.ExecutionReport;
import com.unisoft.algotrader.model.refdata.Instrument;
import com.unisoft.algotrader.persistence.RefDataStore;
import com.unisoft.algotrader.provider.ib.IBProvider;
import com.unisoft.algotrader.provider.ib.InputStreamUtils;
import com.unisoft.algotrader.provider.ib.api.event.ExecutionReportEvent;
import com.unisoft.algotrader.provider.ib.api.event.IBEventHandler;
import com.unisoft.algotrader.provider.ib.api.model.contract.OptionRight;
import com.unisoft.algotrader.provider.ib.api.model.contract.SecType;
import com.unisoft.algotrader.provider.ib.api.model.execution.IBSide;
import com.unisoft.algotrader.provider.ib.api.model.system.IncomingMessageId;

import java.io.InputStream;

import static com.unisoft.algotrader.provider.ib.InputStreamUtils.*;


/**
 * Created by alex on 8/13/15.
 */
public class ExecutionReportEventDeserializer extends Deserializer<ExecutionReportEvent> {

    private final RefDataStore refDataStore;

    public ExecutionReportEventDeserializer(RefDataStore refDataStore, int serverCurrentVersion){
        super(IncomingMessageId.EXECUTION_REPORT, serverCurrentVersion);
        this.refDataStore = refDataStore;
    }

    @Override
    public void consumeMessageContent(final int version, final InputStream inputStream, final IBEventHandler eventHandler) {
        int requestId = (version >= 7) ? readInt(inputStream) : -1;
        final int orderId = readInt(inputStream);
        final Instrument instrument = consumeInstrument(version, inputStream, eventHandler);
        final ExecutionReport executionReport = consumeExecutionReport(version, inputStream, orderId);
        eventHandler.onExecutionReportEvent(requestId, instrument, executionReport);
    }

    protected Instrument consumeInstrument(final int version, final InputStream inputStream, final IBEventHandler eventHandler) {
        final int instId = (version >= 5)? InputStreamUtils.readInt(inputStream) : 0;
        final String symbol = readString(inputStream);
        final Instrument.InstType instType = SecType.convert(readString(inputStream));
        final String expString = readString(inputStream);
        final double strike = readDouble(inputStream);
        final Instrument.PutCall putCall = OptionRight.convert(readString(inputStream));
        final String multiplier = (version >= 9)? readString(inputStream) : null;
        final String exchange = readString(inputStream);
        final String ccyCode = readString(inputStream);
        final String localSymbol = readString(inputStream);
        final String tradingClass = (version >= 10)? readString(inputStream) : null;

        Instrument instrument = refDataStore.getInstrumentBySymbolAndExchange(IBProvider.PROVIDER_ID.name(), symbol, exchange);
        if (instrument == null){
            throw new RuntimeException("Cannot find instrumnet symbol=" + symbol +", primaryExchange="+exchange);
        }

        return instrument;
    }

    protected ExecutionReport consumeExecutionReport(final int version, final InputStream inputStream, final int providerOrderId){
        ExecutionReport executionReport = new ExecutionReport();
        executionReport.orderId(providerOrderId);
        //TODO string to execID mapping?
        executionReport.execId(Long.parseLong(readString(inputStream)));
        String time = readString(inputStream);
        String account = readString(inputStream);
        String exchange = readString(inputStream);
        executionReport.side(IBSide.convert(readString(inputStream)));
        executionReport.lastQty(readInt(inputStream));
        executionReport.lastPrice(readDouble(inputStream));
        if (version >= 2) {
            int permanentId = readInt(inputStream);
        }
        if (version >= 3) {
            int clientId = readInt(inputStream);
        }
        if (version >= 4) {
            int liquidation = readInt(inputStream);
        }
        if (version >= 6) {
            executionReport.filledQty(readInt(inputStream));
            executionReport.avgPrice(readDouble(inputStream));
        }
        if (version >= 8) {
            String orderRef = readString(inputStream);
        }
        if (version >= 9) {
            String economicValueRule = readString(inputStream);
            double economicValueMultiplier = readDouble(inputStream);
        }
        return executionReport;
    }
}