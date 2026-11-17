package com.safedeal.domain.user.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ME-19 응답은 엔티티를 직접 직렬화하지 않고 전용 DTO record로 나간다.
 * 클래스가 아직 없을 때도 컴파일이 깨지지 않도록 이름으로 찾는다(없으면 단언 실패로 Red).
 */
class MyProfileResponseContractTest {

    private static final String TYPE = "com.safedeal.domain.user.dto.MyProfileResponse";

    @Test
    @DisplayName("ME-19 MyProfileResponse는 합의한 6개 필드만 가진 record다")
    void isRecordWithAgreedComponents() {
        Class<?> type = load();

        assertThat(type).as(TYPE + " 가 있어야 한다").isNotNull();
        assertThat(type.isRecord()).isTrue();
        assertThat(Arrays.stream(type.getRecordComponents()).map(RecordComponent::getName))
                .containsExactlyInAnyOrder("publicId", "email", "nickname", "emailVerified", "trustScore", "createdAt");
    }

    private static Class<?> load() {
        try {
            return Class.forName(TYPE);
        } catch (ClassNotFoundException e) {
            return null;
        }
    }
}
