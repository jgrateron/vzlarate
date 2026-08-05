package com.vzlarate.model;

import java.time.LocalDateTime;

public record ExchangeRates(
        double usdRate,
        double eurRate,
        LocalDateTime lastUpdated,
        boolean fromCache
) {
    public ExchangeRates(double usdRate, double eurRate, LocalDateTime lastUpdated) {
        this(usdRate, eurRate, lastUpdated, false);
    }

    public ExchangeRates withFromCache(boolean fromCache) {
        return new ExchangeRates(usdRate, eurRate, lastUpdated, fromCache);
    }
}
