package com.safedeal.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 현재 시각을 읽는 시계(CLK-01). 서비스가 {@code Instant.now()} 대신 이 빈을 받아 {@code Instant.now(clock)}을 쓰면
 * 테스트가 시각을 정하고 움직일 수 있다(한국 자정 경계·잠금 대기 뒤 시각 검증).
 *
 * <p>UTC로 둔다 — {@code Instant}는 시간대와 상관없고, 한국 날짜가 필요한 곳(하루 신고 한도 키)은 그쪽에서 바꾼다.
 * 지금은 신고 도메인만 쓴다(QC-1).
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
