package com.travelpay.user.common.security.access;

import java.io.IOException;

import com.travelpay.common.error.CommonErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.serializer.SerializationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.session.data.redis.RedisSessionRepository;
import org.springframework.web.filter.OncePerRequestFilter;

/** Redis 세션의 읽기·저장 오류를 인증 실패와 구분해 처리합니다. */
@RequiredArgsConstructor
public class RedisSessionErrorResponseFilter extends OncePerRequestFilter {

    private final SecurityErrorResponseWriter errorResponseWriter;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        try {
            filterChain.doFilter(request, response);
        }
        catch (DataAccessException | SerializationException exception) {
            SecurityContextHolder.clearContext();
            // 예외 메시지나 세션·요청 원문 대신 종류만 기록합니다.
            logger.error("세션 저장소 오류: " + exception.getClass().getSimpleName());
            if (response.isCommitted()) {
                throw exception;
            }
            errorResponseWriter.write(response, CommonErrorCode.SERVICE_UNAVAILABLE);
        }
        catch (IllegalStateException exception) {
            if (!isConcurrentSessionChange(exception) || response.isCommitted()) {
                throw exception;
            }
            SecurityContextHolder.clearContext();
            errorResponseWriter.write(response, CommonErrorCode.CONFLICT);
        }
    }

    /** Spring Session 4.1.1의 save 전용 오류이며, 업그레이드 시 동시 요청 시험으로 확인합니다. */
    private boolean isConcurrentSessionChange(IllegalStateException exception) {
        var stack = exception.getStackTrace();
        return "Session was invalidated".equals(exception.getMessage()) && stack.length > 0
                && RedisSessionRepository.class.getName().equals(stack[0].getClassName())
                && "save".equals(stack[0].getMethodName());
    }
}
