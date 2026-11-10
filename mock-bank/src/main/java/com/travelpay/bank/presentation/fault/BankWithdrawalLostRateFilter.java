package com.travelpay.bank.presentation.fault;

import com.travelpay.common.error.CommonErrorCode;
import com.travelpay.common.error.ErrorResponse;
import com.travelpay.common.response.ApiResponse;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 출금(POST /internal/v1/bank/withdrawals) 정상 응답을 설정 비율만큼 유실시킨다 (Mock-Fault: LOST와 같은 500).
 * 처리·커밋이 끝난 뒤 2xx(신규 성공·업무 실패 FAILED·같은 requestId 재요청)일 때만 판정하고, 400·409 등은 그대로 둔다.
 * Mock-Fault 헤더가 있으면 BankFaultInjectionFilter에 맡기고 판정하지 않는다.
 * mock-bank.fault.enabled=true이고 prod 프로필이 아닐 때만 빈을 만든다(아니면 카운터도 등록하지 않음).
 */
@Slf4j
@Component
@Profile("!prod")
@ConditionalOnProperty(prefix = "mock-bank.fault", name = "enabled", havingValue = "true", matchIfMissing = false)
public class BankWithdrawalLostRateFilter extends OncePerRequestFilter {

    static final String WITHDRAWALS_PATH = "/internal/v1/bank/withdrawals";

    /** 비율 주입으로 유실시킨 출금 응답 수. Prometheus: mock_bank_fault_withdrawal_lost_rate_total (헤더 LOST는 세지 않음) */
    static final String LOST_METRIC = "mock.bank.fault.withdrawal.lost.rate";

    private final double rate;
    private final JsonMapper jsonMapper;
    private final Counter lost;

    public BankWithdrawalLostRateFilter(BankWithdrawalLostRateProperties properties, JsonMapper jsonMapper,
                                        MeterRegistry meterRegistry) {
        this.rate = properties.withdrawalLostRate();
        this.jsonMapper = jsonMapper;
        this.lost = Counter.builder(LOST_METRIC)
                .description("비율 주입으로 유실시킨 출금 응답 수")
                .register(meterRegistry);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return rate <= 0
                || !HttpMethod.POST.matches(request.getMethod())
                || !WITHDRAWALS_PATH.equals(request.getRequestURI())
                || request.getHeader(BankFaultInjectionFilter.HEADER) != null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        ContentCachingResponseWrapper buffered = new ContentCachingResponseWrapper(response);
        chain.doFilter(request, buffered);

        if (!HttpStatus.valueOf(buffered.getStatus()).is2xxSuccessful()
                || ThreadLocalRandom.current().nextDouble() >= rate) {
            buffered.copyBodyToResponse();
            return;
        }

        log.warn("출금 응답 유실 주입 requestId={}, 원래 상태={}", requestId(buffered), buffered.getStatus());
        lost.increment();
        buffered.resetBuffer();
        response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        jsonMapper.writeValue(response.getOutputStream(),
                ApiResponse.failure(ErrorResponse.of(CommonErrorCode.INTERNAL_SERVER_ERROR)));
    }

    /** 2xx 출금 응답은 항상 ApiResponse.success(BankTransactionResponse)라 data.requestId가 있다. */
    @SuppressWarnings("unchecked")
    private Object requestId(ContentCachingResponseWrapper buffered) {
        Map<String, Object> body = jsonMapper.readValue(buffered.getContentAsByteArray(), Map.class);
        Object data = body.get("data");
        return data instanceof Map<?, ?> map ? map.get("requestId") : null;
    }
}
