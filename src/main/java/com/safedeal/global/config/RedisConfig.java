package com.safedeal.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.scripting.support.ResourceScriptSource;

/**
 * Redis 인프라 설정.
 *
 * 범용 {@code RedisTemplate<String, Object>}는 만들지 않는다 — 값 타입을 Object로 열어두면
 * 직렬화 방식이 흐려지고 캐시/락/카운터 등 서로 다른 용도가 뒤섞인다. 지금 필요한 용도
 * (챌린지 코드 TTL, 카운터, 분산 락, rate limit Lua)는 전부 문자열 값으로 충분하므로
 * {@link StringRedisTemplate} 하나만 제공한다.
 *
 * 캐시 도입 시 규칙 (RedisCacheManager는 아직 만들지 않았다 — 필요해지면 아래를 지켜 추가할 것):
 *  - 캐시별 TTL 필수 (무기한 캐시 금지)
 *  - null 값 캐싱 금지 (캐시 스탬피드/오염 방지, 존재하지 않는 값은 별도 sentinel이나 짧은 TTL로 처리)
 *  - JPA 엔티티를 캐시 값으로 직접 넣지 말 것 (지연 로딩 프록시 직렬화 문제 — DTO로 변환 후 캐싱)
 *  - 캐시 key에 스키마 버전을 포함해 배포 간 캐시 오염을 방지 (아래 네이밍 규칙 참고)
 *
 * Redis 키 네이밍 규칙:
 *  - 인증 challenge: auth:challenge:v1:{purpose}:{targetHash}
 *  - rate limit:     rate:v1:{actorId}:{route}:{window}
 *  - 조회 캐시(도입 시): cache:{domain}:v1:{publicId}
 *
 * 직렬화 참고 (Spring Boot 4 / Spring Data Redis 4는 Jackson 3 기반으로 전환됨):
 *  - 신규: {@code JacksonJsonRedisSerializer}, {@code GenericJacksonJsonRedisSerializer} (Jackson 3 ObjectMapper 기반)
 *  - 구버전: {@code Jackson2JsonRedisSerializer}, {@code GenericJackson2JsonRedisSerializer}는
 *    {@code @Deprecated(since = "3.0", forRemoval = true)} — 신규 코드에서 사용 금지.
 *  - 지금은 String만 다루므로 JSON 시리얼라이저는 쓰지 않지만, 추후 캐시 값 등에 객체 직렬화가
 *    필요해지면 반드시 Jackson3 기반(JacksonJsonRedisSerializer 계열)을 선택할 것.
 */
@Configuration
public class RedisConfig {

    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        StringRedisTemplate template = new StringRedisTemplate(connectionFactory);
        // StringRedisTemplate은 기본 생성자에서도 4개 시리얼라이저가 모두 StringRedisSerializer로
        // 설정되지만, 의도를 코드에 명시적으로 남기기 위해 다시 지정한다.
        StringRedisSerializer serializer = new StringRedisSerializer();
        template.setKeySerializer(serializer);
        template.setValueSerializer(serializer);
        template.setHashKeySerializer(serializer);
        template.setHashValueSerializer(serializer);
        return template;
    }

    /**
     * rate limit 카운터용 Lua 스크립트 등록 예시.
     * 실제 로직은 {@code src/main/resources/scripts/rate_limit.lua}에 있다 — INCR 후 최초
     * 호출에만 EXPIRE를 실행해 "카운트 증가 + TTL 설정"을 원자적으로 처리한다.
     *
     * Spring Data Redis는 스크립트의 SHA1을 계산해 캐시해두고 EVALSHA를 우선 시도한 뒤
     * NOSCRIPT 응답일 때만 EVAL로 폴백한다. 이 빈이 싱글턴이 아니면(예: 매 요청마다 새로
     * DefaultRedisScript를 생성) 매번 스크립트를 다시 로드/평가하게 되어 이 캐싱 이점이
     * 사라진다 — 그래서 반드시 스프링 컨테이너가 관리하는 싱글턴 빈으로 등록한다.
     *
     * 새로운 Lua 스크립트가 필요하면 이 빈을 복사하지 말고 같은 패턴
     * (scripts/ 아래 별도 .lua 파일 + ClassPathResource + 싱글턴 빈)을 그대로 따라 추가할 것.
     */
    @Bean
    public DefaultRedisScript<Long> rateLimitScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource("scripts/rate_limit.lua")));
        script.setResultType(Long.class);
        return script;
    }
}
