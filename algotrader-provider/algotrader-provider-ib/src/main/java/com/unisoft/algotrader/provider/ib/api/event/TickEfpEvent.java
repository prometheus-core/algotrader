package com.unisoft.algotrader.provider.ib.api.event;


import com.unisoft.algotrader.provider.ib.api.model.data.TickType;

/**
 * Created by alex on 8/26/15.
 */
public class TickEfpEvent extends IBEvent<TickEfpEvent> {

    public final TickType type;
    public final double basisPoints;
    public final String formattedBasisPoints;
    public final double impliedFuturePrice;
    public final int holdDays;
    public final String futureExpiry;
    public final double dividendImpact;
    public final double dividendToExpiry;

    public TickEfpEvent(final long requestId, final TickType type, final double basisPoints,
                        final String formattedBasisPoints, final double impliedFuturePrice, final int holdDays,
                        final String futureExpiry, final double dividendImpact, final double dividendToExpiry) {
        super(requestId);
        this.type = type;
        this.basisPoints = basisPoints;
        this.formattedBasisPoints = formattedBasisPoints;
        this.impliedFuturePrice = impliedFuturePrice;
        this.holdDays = holdDays;
        this.futureExpiry = futureExpiry;
        this.dividendImpact = dividendImpact;
        this.dividendToExpiry = dividendToExpiry;
    }

    @Override
    public void on(IBEventHandler handler) {
        handler.onTickEfpEvent(this);
    }

    @Override
    public String toString() {
        return "TickEfpEvent{" +
                "type=" + type +
                ", basisPoints=" + basisPoints +
                ", formattedBasisPoints='" + formattedBasisPoints + '\'' +
                ", impliedFuturePrice=" + impliedFuturePrice +
                ", holdDays=" + holdDays +
                ", futureExpiry='" + futureExpiry + '\'' +
                ", dividendImpact=" + dividendImpact +
                ", dividendToExpiry=" + dividendToExpiry +
                "} " + super.toString();
    }
}
