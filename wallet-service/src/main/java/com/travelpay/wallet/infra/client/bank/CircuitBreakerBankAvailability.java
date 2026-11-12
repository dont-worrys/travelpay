package com.travelpay.wallet.infra.client.bank;

import com.travelpay.wallet.application.charge.BankAvailability;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.stream.Collectors;

/** 은행 서킷 레지스트리에서 OPEN·FORCED_OPEN인 서킷의 이름(은행 코드). HALF_OPEN은 시험 호출을 받아야 하므로 제외하지 않는다 */
@Component
@RequiredArgsConstructor
public class CircuitBreakerBankAvailability implements BankAvailability {

    private static final Set<CircuitBreaker.State> NOT_SENDING =
            Set.of(CircuitBreaker.State.OPEN, CircuitBreaker.State.FORCED_OPEN);

    private final CircuitBreakerRegistry registry;

    @Override
    public Set<String> openBankCodes() {
        return registry.getAllCircuitBreakers().stream()
                .filter(circuitBreaker -> NOT_SENDING.contains(circuitBreaker.getState()))
                .map(CircuitBreaker::getName)
                .collect(Collectors.toUnmodifiableSet());
    }
}
