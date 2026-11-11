package com.travelpay.bank.presentation.fault;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * 붙잡기가 켜진 은행 코드의 출금(POST /internal/v1/bank/withdrawals)을 처리 전에 대기 시간만큼 잡아 둔 뒤 처리한다.
 * 은행이 연결은 받고 응답 없이 붙잡는 장애를 은행 하나만 재현한다(클라이언트가 읽기 시간 초과로 포기한 뒤 처리됨).
 * 환불·조회는 대상이 아니다. 은행 코드는 요청 본문에서 읽고, 읽은 본문은 그대로 다음 처리로 넘긴다.
 * mock-bank.fault.enabled=true이고 prod 프로필이 아닐 때만 빈을 만든다.
 */
@Slf4j
@Component
@Profile("!prod")
@ConditionalOnProperty(prefix = "mock-bank.fault", name = "enabled", havingValue = "true", matchIfMissing = false)
@RequiredArgsConstructor
public class BankWithdrawalHoldFilter extends OncePerRequestFilter {

    private final BankWithdrawalHolds holds;
    private final JsonMapper jsonMapper;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return holds.isEmpty()
                || !HttpMethod.POST.matches(request.getMethod())
                || !BankWithdrawalLostRateFilter.WITHDRAWALS_PATH.equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        CachedBodyRequest cached = new CachedBodyRequest(request);
        Optional<String> bankCode = bankCode(cached.body);
        Optional<BankWithdrawalHolds.Hold> hold = bankCode.flatMap(holds::find);
        if (hold.isPresent()) {
            log.warn("출금 붙잡기 bankCode={} delay={}", bankCode.get(), hold.get().delay());
            try {
                hold.get().await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        chain.doFilter(cached, response);
    }

    /** 본문 형식이 이상하면 붙잡지 않고 넘긴다(검증 오류는 컨트롤러가 400으로 응답) */
    private Optional<String> bankCode(byte[] body) {
        try {
            BankCodeOnly parsed = jsonMapper.readValue(body, BankCodeOnly.class);
            return Optional.ofNullable(parsed).map(BankCodeOnly::bankCode);
        } catch (JacksonException e) {
            return Optional.empty();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record BankCodeOnly(String bankCode) {
    }

    /** 본문을 한 번 읽어 두고 다음 처리에서 다시 읽을 수 있게 하는 요청 */
    private static final class CachedBodyRequest extends HttpServletRequestWrapper {

        private final byte[] body;

        private CachedBodyRequest(HttpServletRequest request) throws IOException {
            super(request);
            this.body = request.getInputStream().readAllBytes();
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream input = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public boolean isFinished() {
                    return input.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener readListener) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public int read() {
                    return input.read();
                }

                @Override
                public int read(byte[] buffer, int offset, int length) {
                    return input.read(buffer, offset, length);
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            String encoding = getCharacterEncoding();
            Charset charset = encoding != null ? Charset.forName(encoding) : StandardCharsets.UTF_8;
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }
    }
}
