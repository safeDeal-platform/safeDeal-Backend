package com.safedeal.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

// 메인 애플리케이션 클래스가 아닌 별도 설정으로 분리 — 테스트 슬라이스(@DataJpaTest 등)에서
// Auditing 컨텍스트를 필요할 때만 골라 로드할 수 있도록 격리한다.
@Configuration
@EnableJpaAuditing
public class JpaAuditingConfig {
}
