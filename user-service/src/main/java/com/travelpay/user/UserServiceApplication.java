package com.travelpay.user;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;

/**
 * 로그인은 LoginService가 직접 확인하므로 Spring Security의 기본 사용자 계정은 쓰지 않습니다.
 * 기본 계정을 만들면 사용하지 않는 비밀번호가 시작 로그에 남으므로 해당 자동 설정을 제외합니다.
 */
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class UserServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(UserServiceApplication.class, args);
    }
}
