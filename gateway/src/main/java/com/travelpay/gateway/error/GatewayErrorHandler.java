package com.travelpay.gateway.error;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import com.travelpay.common.error.BusinessException;
import com.travelpay.common.error.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.Order;
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
        ErrorResponse body = ErrorResponse.fromHttpStatus(500);
        HttpHeaders headers = new HttpHeaders();
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable current = exception; current != null && visited.add(current); current = current.getCause()) {
            if (current instanceof BusinessException businessException) {
                var errorCode = businessException.getErrorCode();
                status = HttpStatusCode.valueOf(errorCode.getStatus());
                body = ErrorResponse.of(errorCode);
                break;
            }
            if (current instanceof org.springframework.web.ErrorResponse frameworkError) {
                status = frameworkError.getStatusCode();
                body = ErrorResponse.fromHttpStatus(status.value());
                headers.putAll(frameworkError.getHeaders());
                break;
            }
            var responseStatus = AnnotatedElementUtils.findMergedAnnotation(current.getClass(), ResponseStatus.class);
            if (responseStatus != null) {
                status = responseStatus.code();
                body = ErrorResponse.fromHttpStatus(status.value());
                break;
            }
        }

        if (status.is5xxServerError()) {
            log.error("Unexpected exception type: {}", exception.getClass().getName());
        }
        response.setStatusCode(status);
        response.getHeaders().putAll(headers);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        if (exchange.getRequest().getMethod() == HttpMethod.HEAD) {
            return response.setComplete();
        }

        byte[] payload = mapper.writeValueAsBytes(body);
        return response.writeWith(Mono.fromSupplier(() -> response.bufferFactory().wrap(payload)));
    }
}
