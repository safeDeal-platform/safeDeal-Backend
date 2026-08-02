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
 * 감사 필드 정책상 모든 테이블은 created_at을 갖지만, updated_at은 "생성 후 실제로 UPDATE가
 * 일어나는 테이블"만 갖는다. 불변 원장(trust_score_logs)·감사 로그(admin_audit_logs)·
 * 이벤트 원본(outbox)처럼 append-only인 테이블에 updated_at을 두면, 절대 변하지 않는 값을
 * 매 행마다 저장하면서 "이 테이블은 수정될 수 있다"는 잘못된 신호까지 남긴다.
 *
 * 판정 기준은 테이블 이름이 아니라 실제 UPDATE의 존재 여부다. chat_messages도 신고 후
 * 숨김·마스킹이 생기면 변경 테이블이므로 {@link MutableEntity}를 상속해야 한다.
 *
 * {@code updatable = false}로 createdAt이 이후 UPDATE 문에 포함되지 않게 막는다.
 * {@code Instant}를 쓰는 이유는 DB·JVM 모두 UTC로 저장하고 표시 시점에만 KST로 변환하는
 * 정책 때문이다 — LocalDateTime은 시간대 정보가 없어 서버 로케일에 따라 의미가 달라진다.
 */
@Getter
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class CreatedEntity {

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private Instant createdAt;
}
