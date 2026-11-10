package com.travelpay.user.common.config;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AdminPropertiesTest {

    @Test
    void bindsAnExplicitWholeSecondTransactionTimeout() {
        var environment = new MockEnvironment()
                .withProperty("travelpay.user.admin.transaction-timeout", "3s");

        var properties = Binder.get(environment).bind("travelpay.user.admin", AdminProperties.class).get();

        assertEquals(Duration.ofSeconds(3), properties.transactionTimeout());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0s", "-1s", "5", "1500ms", "2147483648s"})
    void rejectsTimeoutsThatCannotBeAppliedAsPositiveIntegerSeconds(String value) {
        var environment = new MockEnvironment().withProperty("travelpay.user.admin.transaction-timeout", value);

        assertThrows(BindException.class,
                () -> Binder.get(environment).bind("travelpay.user.admin", AdminProperties.class));
    }

    @Test
    void requiresTheTransactionTimeout() {
        assertThrows(IllegalArgumentException.class, () -> new AdminProperties(null));
    }
}
