package com.anywhere.spike.achstripe.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class MoneyTest {

    @Test
    void converts_between_major_and_minor_units() {
        // given
        BigDecimal amount = Money.parse("49.9");

        // when
        long minor = Money.toMinorUnits(amount);

        // then
        assertThat(minor).isEqualTo(4990L);
        assertThat(Money.fromMinorUnits(minor)).isEqualByComparingTo("49.90");
    }
}
