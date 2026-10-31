package com.travelpay.user.common.security;

import com.travelpay.user.application.service.CurrentMemberService;
import com.travelpay.user.common.security.access.CurrentMemberAuthorizationManager;
import com.travelpay.user.common.security.access.RedisSessionErrorResponseFilter;
import com.travelpay.user.common.security.access.RestAccessDeniedHandler;
import com.travelpay.user.common.security.access.RestAuthenticationEntryPoint;
import com.travelpay.user.common.security.access.SecurityErrorResponseWriter;
import jakarta.servlet.DispatcherType;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.DelegatingSecurityContextRepository;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.savedrequest.NullRequestCache;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.session.web.http.SessionRepositoryFilter;

/** User API의 비밀번호 해시·세션 인증·접근 권한·CSRF 및 보안 오류 응답 규칙입니다. */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfiguration {

    private static final String LOGOUT_PATH = "/api/v1/auth/logout";

    private final RestAuthenticationEntryPoint authenticationEntryPoint;
    private final RestAccessDeniedHandler accessDeniedHandler;

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, CsrfTokenRepository csrfTokenRepository,
            SecurityContextRepository securityContextRepository, CurrentMemberService currentMemberService)
            throws Exception {
        http
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(cache -> cache.requestCache(new NullRequestCache()))
                // 명세 6-5의 인증·개인정보 응답 no-store를 컨트롤러마다 두지 않고 모든 응답에 적용합니다.
                .headers(headers -> headers.cacheControl(Customizer.withDefaults()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .securityContext(context -> context.securityContextRepository(securityContextRepository))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .csrf(csrf -> csrf.csrfTokenRepository(csrfTokenRepository)
                        .requireCsrfProtectionMatcher(csrfProtectedRequests()))
                .authorizeHttpRequests(requests -> requests
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/auth/csrf", "/api/v1/terms").permitAll()
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**",
                                "/actuator/prometheus").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/users", "/api/v1/auth/login").permitAll()
                        // 팀 결정(2026-10-31): 서비스 간 호출은 서비스 인증 없이 내부망 격리로 보호하므로 세션 없이 엽니다.
                        // 외부 차단은 Gateway·배포 망이 맡고, I03 본인 확인·I02 호출 횟수 제한은 요청자 헤더 계약 후 추가합니다.
                        .requestMatchers(HttpMethod.GET, "/internal/v1/users/{userId}/real-name",
                                "/internal/v1/users/{userId}/funding-identity").permitAll()
                        .requestMatchers(HttpMethod.POST, LOGOUT_PATH)
                        .access(CurrentMemberAuthorizationManager.sessionMembers(currentMemberService))
                        .requestMatchers("/api/v1/admin/**")
                        .access(CurrentMemberAuthorizationManager.masters(currentMemberService))
                        .anyRequest().access(CurrentMemberAuthorizationManager.activeMembers(currentMemberService)));
        return http.build();
    }

    @Bean
    FilterRegistrationBean<RedisSessionErrorResponseFilter> redisSessionErrorResponseFilter(
            SecurityErrorResponseWriter errorResponseWriter) {
        var registration = new FilterRegistrationBean<>(new RedisSessionErrorResponseFilter(errorResponseWriter));
        registration.setOrder(SessionRepositoryFilter.DEFAULT_ORDER - 1);
        registration.setDispatcherTypes(DispatcherType.REQUEST);
        registration.addUrlPatterns("/*");
        return registration;
    }

    @Bean
    SecurityContextRepository securityContextRepository() {
        return new DelegatingSecurityContextRepository(
                new RequestAttributeSecurityContextRepository(), new HttpSessionSecurityContextRepository());
    }

    @Bean
    HttpSessionCsrfTokenRepository csrfTokenRepository() {
        var repository = new HttpSessionCsrfTokenRepository();
        repository.setHeaderName("Csrf-Token");
        return repository;
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new ValidatingPasswordEncoder(PasswordEncoderFactories.createDelegatingPasswordEncoder());
    }

    /** 세션이 없는 로그아웃은 바꿀 상태가 없으므로 CSRF 오류 대신 인가 단계에서 401로 응답합니다. */
    private static RequestMatcher csrfProtectedRequests() {
        var logout = PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, LOGOUT_PATH);
        return request -> CsrfFilter.DEFAULT_CSRF_MATCHER.matches(request)
                && !(logout.matches(request) && request.getSession(false) == null);
    }
}
