package com.anywhere.spike.achstripe.common;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** USD-only helper (subscription billing is USD; see SUBSCRIPTION_CURRENCY in fullpayments). */
public final class Money {

    private Money() {}

    public static long toMinorUnits(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact();
    }

    public static BigDecimal fromMinorUnits(long minor) {
        return BigDecimal.valueOf(minor).movePointLeft(2).setScale(2, RoundingMode.UNNECESSARY);
    }

    public static BigDecimal parse(String amount) {
        return new BigDecimal(amount).setScale(2, RoundingMode.HALF_UP);
    }
}
