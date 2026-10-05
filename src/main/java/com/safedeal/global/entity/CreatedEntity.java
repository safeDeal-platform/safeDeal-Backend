package com.safedeal.global.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

/**
 * 생성 시각만 갖는 엔티티의 상위 클래스.
 *
 * updated_at은 실제로 UPDATE가 일어나는 테이블에만 둔다 — 불변 원장·감사 로그처럼 절대 안
 * 바뀌는 append-only 테이블에 두면 "이 테이블은 수정될 수 있다"는 잘못된 신호를 준다.
 * {@code Instant}를 쓰는 이유는 DB·JVM 모두 UTC로 저장하고 표시 시점에만 KST로 바꾸는
 * 정책 때문이다(LocalDateTime은 시간대가 없어 서버 로케일에 따라 값의 의미가 달라진다).
 */
@Getter
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class CreatedEntity {

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private Instant createdAt;
}
