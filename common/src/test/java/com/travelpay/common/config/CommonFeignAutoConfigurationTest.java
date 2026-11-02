package com.travelpay.common.config;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;
import com.travelpay.common.auth.AuthHeaders;
import com.travelpay.common.auth.AuthRole;
import com.travelpay.common.auth.AuthenticatedUser;
import com.travelpay.common.auth.CurrentUserProvider;
import com.travelpay.common.auth.web.RequestAttributeCurrentUserProvider;
import com.travelpay.common.feign.InternalHeaderRelayInterceptor;
import feign.Retryer;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.assertj.core.api.Assertions.assertThat;

class CommonFeignAutoConfigurationTest {

    private static final AuthenticatedUser CURRENT_USER = new AuthenticatedUser(
            UUID.fromString("550e8400-e29b-41d4-a716-446655440000"), AuthRole.USER);
    private static final String EXPLICIT_USER_ID = "4c6a0178-266b-4f1e-b811-a2767026be70";
    private static final BlockingQueue<Headers> RECEIVED = new LinkedBlockingQueue<>();

    private static HttpServer server;

    private final WebApplicationContextRunner servlet = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    CommonAuthWebAutoConfiguration.class, CommonFeignAutoConfiguration.class));

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/echo", exchange -> {
            RECEIVED.add(exchange.getRequestHeaders());
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
    }

    @AfterAll
    static void stopServer() {
        server.stop(0);
    }

    @AfterEach
    void clearRequest() {
        RequestContextHolder.resetRequestAttributes();
        RECEIVED.clear();
    }

    @Test
    void servletApplicationWithFeignGetsTheHeaderRelayInterceptor() {
        servlet.run(context -> assertThat(context).hasNotFailed()
                .hasSingleBean(InternalHeaderRelayInterceptor.class));
    }

    @Test
    void aServiceDefinedInterceptorReplacesTheDefaultOne() {
        servlet.withUserConfiguration(CustomInterceptorConfiguration.class)
                .run(context -> assertThat(context).hasNotFailed()
                        .hasSingleBean(InternalHeaderRelayInterceptor.class)
                        .hasBean("customInterceptor"));
    }

    @Test
    void applicationWithoutFeignDoesNotRegisterTheInterceptor() {
        servlet.withClassLoader(new FilteredClassLoader("feign"))
                .run(context -> assertThat(context).hasNotFailed()
                        .doesNotHaveBean(InternalHeaderRelayInterceptor.class));
    }

    @Test
    void applicationWithoutACurrentUserProviderDoesNotRegisterTheInterceptor() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(CommonFeignAutoConfiguration.class))
                .run(context -> assertThat(context).hasNotFailed()
                        .doesNotHaveBean(InternalHeaderRelayInterceptor.class));
    }

    @Test
    void reactiveAndNonWebApplicationsDoNotRegisterTheInterceptorEvenWithTheirOwnProvider() {
        var configurations = AutoConfigurations.of(CommonFeignAutoConfiguration.class);

        new ReactiveWebApplicationContextRunner().withConfiguration(configurations)
                .withUserConfiguration(CustomProviderConfiguration.class)
                .run(context -> assertThat(context).hasNotFailed()
                        .doesNotHaveBean(InternalHeaderRelayInterceptor.class));
        new ApplicationContextRunner().withConfiguration(configurations)
                .withUserConfiguration(CustomProviderConfiguration.class)
                .run(context -> assertThat(context).hasNotFailed()
                        .doesNotHaveBean(InternalHeaderRelayInterceptor.class));
    }

    @Test
    void missingServletRequestClassDoesNotRegisterTheInterceptorEvenWithAProvider() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(CommonFeignAutoConfiguration.class))
                .withUserConfiguration(CustomProviderConfiguration.class)
                .withClassLoader(new FilteredClassLoader(HttpServletRequest.class))
                .run(context -> assertThat(context).hasNotFailed()
                        .doesNotHaveBean(InternalHeaderRelayInterceptor.class));
    }

    @Test
    void springFeignClientsSendTheRequesterAndRequestIdOfTheCurrentRequest() {
        feignApplication().run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(InternalHeaderRelayInterceptor.class);
            startRequest();

            context.getBean(HeaderEchoClient.class).echo();

            Headers headers = RECEIVED.poll(5, TimeUnit.SECONDS);
            assertThat(headers).isNotNull();
            assertThat(headers.get(AuthHeaders.USER_ID)).containsExactly(CURRENT_USER.userId().toString());
            assertThat(headers.get(AuthHeaders.USER_ROLE)).containsExactly("USER");
            assertThat(headers.get(AuthHeaders.REQUEST_ID)).containsExactly("request-1");
        });
    }

    @Test
    void springFeignClientsWithTheirOwnConfigurationAlsoSendTheHeaders() {
        feignApplication().run(context -> {
            startRequest();

            context.getBean(ConfiguredEchoClient.class).echo();

            Headers headers = RECEIVED.poll(5, TimeUnit.SECONDS);
            assertThat(headers).isNotNull();
            assertThat(headers.get(AuthHeaders.USER_ID)).containsExactly(CURRENT_USER.userId().toString());
            assertThat(headers.get(AuthHeaders.REQUEST_ID)).containsExactly("request-1");
        });
    }

    @Test
    void springFeignClientsKeepTheRequesterTheMethodSet() {
        feignApplication().run(context -> {
            startRequest();

            context.getBean(HeaderEchoClient.class).echoAs(EXPLICIT_USER_ID, "MASTER");

            Headers headers = RECEIVED.poll(5, TimeUnit.SECONDS);
            assertThat(headers).isNotNull();
            assertThat(headers.get(AuthHeaders.USER_ID)).containsExactly(EXPLICIT_USER_ID);
            assertThat(headers.get(AuthHeaders.USER_ROLE)).containsExactly("MASTER");
            assertThat(headers.get(AuthHeaders.REQUEST_ID)).containsExactly("request-1");
        });
    }

    @Test
    void springFeignClientsSendNoInternalHeadersOutsideARequest() {
        feignApplication().run(context -> {
            context.getBean(HeaderEchoClient.class).echo();

            Headers headers = RECEIVED.poll(5, TimeUnit.SECONDS);
            assertThat(headers).isNotNull();
            assertThat(headers.containsKey(AuthHeaders.USER_ID)).isFalse();
            assertThat(headers.containsKey(AuthHeaders.USER_ROLE)).isFalse();
            assertThat(headers.containsKey(AuthHeaders.REQUEST_ID)).isFalse();
        });
    }

    private static WebApplicationContextRunner feignApplication() {
        return new WebApplicationContextRunner()
                .withUserConfiguration(FeignApplication.class)
                .withPropertyValues("test.header-echo.url=http://127.0.0.1:" + server.getAddress().getPort());
    }

    /** 로그인 확인을 마쳐서 요청자가 저장된 사용자 요청을 흉내 냅니다. */
    private static void startRequest() {
        var request = new MockHttpServletRequest();
        request.addHeader(AuthHeaders.REQUEST_ID, "request-1");
        request.setAttribute(RequestAttributeCurrentUserProvider.CURRENT_USER_ATTRIBUTE, CURRENT_USER);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @EnableFeignClients(clients = {HeaderEchoClient.class, ConfiguredEchoClient.class})
    static class FeignApplication {
    }

    @FeignClient(name = "header-echo", url = "${test.header-echo.url}")
    interface HeaderEchoClient {

        @GetMapping("/echo")
        void echo();

        @GetMapping("/echo")
        void echoAs(@RequestHeader(AuthHeaders.USER_ID) String userId,
                    @RequestHeader(AuthHeaders.USER_ROLE) String role);
    }

    /** 서비스의 클라이언트처럼 전용 설정 클래스를 가진 클라이언트입니다. */
    @FeignClient(name = "configured-echo", url = "${test.header-echo.url}", configuration = NeverRetryConfiguration.class)
    interface ConfiguredEchoClient {

        @GetMapping("/echo")
        void echo();
    }

    static class NeverRetryConfiguration {

        @Bean
        Retryer retryer() {
            return Retryer.NEVER_RETRY;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomProviderConfiguration {

        @Bean
        CurrentUserProvider customProvider() {
            return Optional::empty;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomInterceptorConfiguration {

        @Bean
        InternalHeaderRelayInterceptor customInterceptor() {
            return new InternalHeaderRelayInterceptor(Optional::empty);
        }
    }
}
