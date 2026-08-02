package com.safedeal.global.entity;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import org.springframework.data.annotation.LastModifiedDate;

import java.time.Instant;

/**
 * 생성 후 상태나 업무 데이터가 변경되는 엔티티의 상위 클래스.
 *
 * {@link CreatedEntity}에 updated_at을 더한다. 매물의 상태 전이, 채팅방의 커서 갱신처럼
 * 실제로 UPDATE가 일어나는 테이블이 여기에 해당한다.
 *
 * {@code @EntityListeners}를 다시 선언하지 않는 이유: 매핑된 상위 클래스에 붙인 엔티티
 * 리스너는 그것을 상속한 모든 엔티티에 적용된다. 여기서 중복 선언하면 리스너가 두 번
 * 등록된 것처럼 보여 읽는 사람을 헷갈리게 만든다.
 */
@Getter
@MappedSuperclass
public abstract class MutableEntity extends CreatedEntity {

    @LastModifiedDate
    @Column(nullable = false)
    private Instant updatedAt;
}
