package com.travelpay.gateway.error;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import com.travelpay.common.error.BusinessException;
import com.travelpay.common.error.CommonErrorCode;
import com.travelpay.common.error.ErrorResponse;
import com.travelpay.common.response.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.Order;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

@Component
@Order(-2)
public class GatewayErrorHandler implements WebExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GatewayErrorHandler.class);

    private final JsonMapper mapper;

    public GatewayErrorHandler(JsonMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable exception) {
        var response = exchange.getResponse();
        if (response.isCommitted()) {
            return Mono.error(exception);
        }

        HttpStatusCode status = HttpStatusCode.valueOf(500);
        ErrorResponse error = ErrorResponse.fromHttpStatus(500);
        HttpHeaders headers = new HttpHeaders();
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable current = exception; current != null && visited.add(current); current = current.getCause()) {
            if (current instanceof BusinessException businessException) {
                var errorCode = businessException.getErrorCode();
                status = HttpStatusCode.valueOf(errorCode.getStatus());
                error = ErrorResponse.of(errorCode);
                break;
            }
            if (current instanceof org.springframework.web.ErrorResponse frameworkError) {
                status = frameworkError.getStatusCode();
                error = ErrorResponse.fromHttpStatus(status.value());
                headers.putAll(frameworkError.getHeaders());
                break;
            }
            var responseStatus = AnnotatedElementUtils.findMergedAnnotation(current.getClass(), ResponseStatus.class);
            if (responseStatus != null) {
                status = responseStatus.code();
                error = ErrorResponse.fromHttpStatus(status.value());
                break;
            }
            // 다른 서비스와 연결을 맺지 못한 실패(거부·연결 시간 초과·경로 없음)는 요청을 보내기 전이라 다시 시도해도 되는
            // 503입니다. Netty의 연결 시간 초과도 ConnectException이며, 보낸 뒤 응답이 늦은 경우(504)와 구분됩니다.
            if (current instanceof ConnectException || current instanceof NoRouteToHostException) {
                status = HttpStatusCode.valueOf(CommonErrorCode.SERVICE_UNAVAILABLE.getStatus());
                error = ErrorResponse.of(CommonErrorCode.SERVICE_UNAVAILABLE);
                break;
            }
        }

        if (status.is5xxServerError()) {
            log.error("Unexpected exception type: {}", exception.getClass().getName());
        }
        response.setStatusCode(status);
        response.getHeaders().putAll(headers);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        // 프레임워크 헤더를 복사한 뒤, HEAD를 포함한 오류 응답의 저장 금지를 명시합니다.
        response.getHeaders().setCacheControl(CacheControl.noStore());
        if (exchange.getRequest().getMethod() == HttpMethod.HEAD) {
            return response.setComplete();
        }

        byte[] payload = mapper.writeValueAsBytes(ApiResponse.failure(error));
        return response.writeWith(Mono.fromSupplier(() -> response.bufferFactory().wrap(payload)));
    }
}
