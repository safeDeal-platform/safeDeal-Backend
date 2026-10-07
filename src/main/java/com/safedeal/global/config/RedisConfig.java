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
 * 값 타입을 Object로 열어두는 범용 {@code RedisTemplate<String, Object>} 대신
 * {@link StringRedisTemplate} 하나만 둔다 — 지금 쓰는 용도(챌린지 코드 TTL, 카운터, 분산 락,
 * rate limit Lua)가 전부 문자열이라 직렬화 방식이 뒤섞일 이유가 없다.
 *
 * Redis 키 네이밍(새 키도 이 규칙을 따를 것): 인증 challenge
 * {@code auth:challenge:v1:{purpose}:{targetHash}}, rate limit
 * {@code rate:v1:{actorId}:{route}:{window}}. 캐시(RedisCacheManager)는 아직 없다 —
 * 도입 시 캐시별 TTL 필수·null 캐싱 금지·JPA 엔티티 직접 캐싱 금지 규칙을 지킬 것.
 *
 * 객체 직렬화가 필요해지면(Spring Boot 4는 Jackson 3 기반) {@code Jackson2JsonRedisSerializer}가
 * 아니라 {@code JacksonJsonRedisSerializer} 계열을 쓴다 — 구버전은 deprecated다.
 */
@Configuration
public class RedisConfig {

    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        StringRedisTemplate template = new StringRedisTemplate(connectionFactory);
        // 기본 생성자로도 4개 시리얼라이저가 전부 StringRedisSerializer가 되지만, 의도를
        // 코드에 명시적으로 남기기 위해 다시 지정한다.
        StringRedisSerializer serializer = new StringRedisSerializer();
        template.setKeySerializer(serializer);
        template.setValueSerializer(serializer);
        template.setHashKeySerializer(serializer);
        template.setHashValueSerializer(serializer);
        return template;
    }

    /**
     * rate limit 카운터용 Lua 스크립트 등록. 실제 로직은
     * {@code src/main/resources/scripts/rate_limit.lua}에 있다 — INCR 후 첫 요청(카운트 1)이거나
     * TTL이 없을 때 EXPIRE를 실행한다. Lua는 앞 명령을 롤백하지 않으므로, EXPIRE가 실패하지 않도록 TTL 상한을 사전 검증한다.
     *
     * 이 빈은 반드시 싱글턴으로 등록한다 — 매번 새로 만들면 Spring Data Redis가 스크립트를
     * 캐시해두고 재사용하는 이점(EVALSHA)이 사라진다. 새 Lua 스크립트가 필요하면 이 빈을
     * 복사하지 말고 같은 패턴(scripts/ 아래 .lua 파일 + ClassPathResource + 싱글턴 빈)을 따를 것.
     */
    @Bean
    public DefaultRedisScript<Long> rateLimitScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource("scripts/rate_limit.lua")));
        script.setResultType(Long.class);
        return script;
    }
}
