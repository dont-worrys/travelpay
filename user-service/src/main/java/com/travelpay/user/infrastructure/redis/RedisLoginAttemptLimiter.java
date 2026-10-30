package com.travelpay.user.infrastructure.redis;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;

import com.travelpay.user.application.service.LoginAttemptLimiter;
import com.travelpay.user.common.config.LoginAttemptProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * 로그인 시도 횟수를 세션 Redis에 저장합니다. 확인과 변경을 Lua 스크립트 하나로 실행해 동시 요청도 한도를 넘지 않습니다.
 * 진행 중인 시도는 실패로 확정하기 전까지 따로 세어, 겹친 요청이 실패 횟수를 부풀려 차단을 앞당기지 않게 합니다.
 * 세션 Redis는 메모리가 차도 키를 지우지 않는 설정이라 횟수와 차단이 조용히 사라지지 않습니다.
 */
@Component
@EnableConfigurationProperties(LoginAttemptProperties.class)
public class RedisLoginAttemptLimiter implements LoginAttemptLimiter {

    private static final String PREFIX = "travelpay:user:login-attempt:";

    // 결과를 기록하기 전에 서버가 멈춰도 진행 중 표시가 남지 않게 합니다. 시도가 이보다 오래 걸려도 실패는 그대로 기록됩니다.
    private static final Duration IN_FLIGHT_TTL = Duration.ofMinutes(1);

    // 모든 스크립트의 KEYS: 1 이메일+IP 실패 횟수, 2 이메일+IP 진행 중 시도, 3 이메일+IP 차단,
    //                     4 이메일 실패 횟수, 5 이메일 진행 중 시도, 6 이메일 차단

    // ARGV: 이메일+IP 한도, 이메일 한도, 진행 중 표시 유지 시간(ms)
    private static final RedisScript<Long> START = RedisScript.of("""
            local function used(failures, inFlight)
                return tonumber(redis.call('GET', failures) or '0') + tonumber(redis.call('GET', inFlight) or '0')
            end
            if redis.call('EXISTS', KEYS[3], KEYS[6]) > 0
                    or used(KEYS[1], KEYS[2]) >= tonumber(ARGV[1])
                    or used(KEYS[4], KEYS[5]) >= tonumber(ARGV[2]) then
                return 0
            end
            for _, key in ipairs({KEYS[2], KEYS[5]}) do
                redis.call('INCR', key)
                redis.call('PEXPIRE', key, ARGV[3])
            end
            return 1
            """, Long.class);

    // ARGV: 이메일+IP 한도, 이메일 한도, 집계 시간(ms), 차단 시간(ms)
    private static final RedisScript<Long> FAIL = RedisScript.of("""
            local function fail(failures, inFlight, blocked, limit)
                if tonumber(redis.call('GET', inFlight) or '0') > 0 then
                    redis.call('DECR', inFlight)
                end
                local count = redis.call('INCR', failures)
                if count == 1 then
                    redis.call('PEXPIRE', failures, ARGV[3])
                end
                if count >= tonumber(limit) then
                    redis.call('SET', blocked, '1', 'PX', ARGV[4])
                    redis.call('DEL', failures)
                end
            end
            fail(KEYS[1], KEYS[2], KEYS[3], ARGV[1])
            fail(KEYS[4], KEYS[5], KEYS[6], ARGV[2])
            return 1
            """, Long.class);

    // 성공하면 이메일+IP 실패 횟수만 지우고, 이메일 실패 횟수는 여러 IP에 나눈 공격을 계속 세도록 남깁니다.
    private static final RedisScript<Long> SUCCEED = RedisScript.of("""
            redis.call('DEL', KEYS[1])
            for _, key in ipairs({KEYS[2], KEYS[5]}) do
                if tonumber(redis.call('GET', key) or '0') > 0 then
                    redis.call('DECR', key)
                end
            end
            return 1
            """, Long.class);

    private static final RedisScript<Long> CANCEL = RedisScript.of("""
            for _, key in ipairs({KEYS[2], KEYS[5]}) do
                if tonumber(redis.call('GET', key) or '0') > 0 then
                    redis.call('DECR', key)
                end
            end
            return 1
            """, Long.class);

    private final StringRedisTemplate redis;
    private final LoginAttemptProperties properties;

    public RedisLoginAttemptLimiter(StringRedisTemplate redis, LoginAttemptProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    @Override
    public boolean tryStart(String email, String clientIp) {
        var started = redis.execute(START, keys(email, clientIp), String.valueOf(properties.pairLimit()),
                String.valueOf(properties.accountLimit()), String.valueOf(IN_FLIGHT_TTL.toMillis()));
        return Long.valueOf(1).equals(started);
    }

    @Override
    public void recordFailure(String email, String clientIp) {
        redis.execute(FAIL, keys(email, clientIp), String.valueOf(properties.pairLimit()),
                String.valueOf(properties.accountLimit()), String.valueOf(properties.window().toMillis()),
                String.valueOf(properties.block().toMillis()));
    }

    @Override
    public void recordSuccess(String email, String clientIp) {
        redis.execute(SUCCEED, keys(email, clientIp));
    }

    @Override
    public void cancel(String email, String clientIp) {
        redis.execute(CANCEL, keys(email, clientIp));
    }

    /** 스크립트의 KEYS 순서대로 키를 만듭니다. Redis 키 목록에 이메일·IP 원문이 드러나지 않도록 해시 값을 씁니다. */
    private static List<String> keys(String email, String clientIp) {
        var emailHash = sha256(email);
        var pair = PREFIX + "pair:" + emailHash + ":" + sha256(clientIp);
        var account = PREFIX + "account:" + emailHash;
        return List.of(pair + ":failures", pair + ":in-flight", pair + ":block",
                account + ":failures", account + ":in-flight", account + ":block");
    }

    private static String sha256(String value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        }
        catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", exception);
        }
    }
}
