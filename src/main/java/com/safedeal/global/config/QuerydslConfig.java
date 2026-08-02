package com.safedeal.global.config;

// OpenFeign 포크(io.github.openfeign.querydsl) 7.0을 사용하지만 클래스 패키지는
// 기존 com.querydsl 그대로 유지된다(좌표만 포크, 패키지는 미변경).
import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class QuerydslConfig {

    @PersistenceContext
    private EntityManager entityManager;

    @Bean
    public JPAQueryFactory jpaQueryFactory() {
        return new JPAQueryFactory(entityManager);
    }
}
