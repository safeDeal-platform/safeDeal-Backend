package com.safedeal.global.entity;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import org.springframework.data.annotation.LastModifiedDate;

import java.time.Instant;

/**
 * 생성 후 상태나 데이터가 바뀌는 엔티티의 상위 클래스. {@link CreatedEntity}에 updated_at을
 * 더한다. {@code @EntityListeners}를 다시 선언하지 않는 이유: 매핑된 상위 클래스의 리스너는
 * 상속한 엔티티에 그대로 적용되므로, 여기서 또 선언하면 리스너가 두 번 등록된 것처럼 보인다.
 */
@Getter
@MappedSuperclass
public abstract class MutableEntity extends CreatedEntity {

    @LastModifiedDate
    @Column(nullable = false)
    private Instant updatedAt;
}
