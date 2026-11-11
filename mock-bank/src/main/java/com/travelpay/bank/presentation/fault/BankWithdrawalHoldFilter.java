package com.travelpay.bank.presentation.fault;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.travelpay.common.error.CommonErrorCode;
import com.travelpay.common.error.ErrorResponse;
import com.travelpay.common.response.ApiResponse;
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
import org.springframework.http.MediaType;
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
 * 본문을 메모리에 담으므로 {@link #MAX_BODY_BYTES}를 넘으면 담지 않고 413으로 응답한다.
 * mock-bank.fault.enabled=true이고 prod 프로필이 아닐 때만 빈을 만든다.
 */
@Slf4j
@Component
@Profile("!prod")
@ConditionalOnProperty(prefix = "mock-bank.fault", name = "enabled", havingValue = "true", matchIfMissing = false)
@RequiredArgsConstructor
public class BankWithdrawalHoldFilter extends OncePerRequestFilter {

    /**
     * 붙잡기가 켜져 있을 때 담아 두는 출금 본문 최대 크기(바이트).
     * BankWithdrawalRequest 검증을 통과하는 가장 큰 본문이 약 1.3KB다:
     * requestId·holderName 각 100자를 모두 유니코드 이스케이프(글자당 6B)로 쓰면 600B씩(1,200B),
     * bankCode 3B, accountNumber 20B, amount(long) 최대 20B, 필드 이름·따옴표·구분자 약 75B.
     * 지갑이 실제로 보내는 본문은 이보다 훨씬 작다(테스트의 정상 출금 본문 약 120B).
     * 공백·줄바꿈·모르는 필드 여유를 두고 그 6배 남짓인 8KiB로 정한다. 이를 넘는 본문은 정상 출금일 수 없다.
     */
    static final int MAX_BODY_BYTES = 8 * 1024;

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
        // Content-Length만 믿지 않는다: chunked는 길이가 없으므로 읽으면서 한도+1바이트까지만 읽어 넘는지 본다
        if (request.getContentLengthLong() > MAX_BODY_BYTES) {
            payloadTooLarge(response);
            return;
        }
        byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) {
            payloadTooLarge(response);
            return;
        }
        CachedBodyRequest cached = new CachedBodyRequest(request, body);
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

    private void payloadTooLarge(HttpServletResponse response) throws IOException {
        log.warn("출금 붙잡기 본문 크기 초과 maxBytes={}", MAX_BODY_BYTES);
        response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        jsonMapper.writeValue(response.getOutputStream(),
                ApiResponse.failure(ErrorResponse.of(CommonErrorCode.PAYLOAD_TOO_LARGE)));
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

    /** 한 번 읽어 둔 본문을 다음 처리에서 다시 읽을 수 있게 하는 요청 */
    private static final class CachedBodyRequest extends HttpServletRequestWrapper {

        private final byte[] body;

        private CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
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
