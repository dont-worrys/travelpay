package com.travelpay.bank.presentation.fault;

import com.travelpay.common.error.CommonErrorCode;
import com.travelpay.common.error.ErrorResponse;
import com.travelpay.common.response.ApiResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;

/**
 * Mock-Fault 헤더로 응답 장애를 흉내 낸다 (POST만).
 * 처리와 커밋이 끝난 뒤 적용하도록 응답을 버퍼에 받아 둔다.
 * DELAY: 설정 시간만큼 늦게 원래 응답 / LOST: 원래 응답을 버리고 500
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BankFaultInjectionFilter extends OncePerRequestFilter {

    public static final String HEADER = "Mock-Fault";

    private final BankFaultProperties properties;
    private final JsonMapper jsonMapper;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !HttpMethod.POST.matches(request.getMethod()) || request.getHeader(HEADER) == null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        BankFault fault = BankFault.from(request.getHeader(HEADER));
        if (fault == null) {
            chain.doFilter(request, response);
            return;
        }

        ContentCachingResponseWrapper buffered = new ContentCachingResponseWrapper(response);
        chain.doFilter(request, buffered);
        log.info("장애 주입 fault={}, uri={}, 원래 상태={}", fault, request.getRequestURI(), buffered.getStatus());

        switch (fault) {
            case DELAY -> {
                sleep();
                buffered.copyBodyToResponse();
            }
            case LOST -> {
                buffered.resetBuffer();
                response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.setCharacterEncoding("UTF-8");
                jsonMapper.writeValue(response.getOutputStream(),
                        ApiResponse.failure(ErrorResponse.of(CommonErrorCode.INTERNAL_SERVER_ERROR)));
            }
        }
    }

    private void sleep() {
        try {
            Thread.sleep(properties.delay());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private enum BankFault {
        DELAY,
        LOST;

        static BankFault from(String value) {
            for (BankFault fault : values()) {
                if (fault.name().equalsIgnoreCase(value.strip())) {
                    return fault;
                }
            }
            return null;
        }
    }
}
