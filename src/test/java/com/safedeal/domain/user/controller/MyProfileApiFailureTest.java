package com.safedeal.domain.user.controller;

import com.safedeal.domain.user.entity.User;
import com.safedeal.domain.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /api/users/me 의 장애·조회 방식 케이스. 저장소·Redis를 spy로 바꿔야 해서 {@link MyProfileApiTest}와
 * 컨텍스트를 나눈다(spy가 다른 테스트에 새지 않도록).
 */
class MyProfileApiFailureTest extends MyProfileApiSupport {

    @MockitoSpyBean UserRepository userRepository;
    @MockitoSpyBean StringRedisTemplate redisTemplate;

    @Test
    @DisplayName("ME-22 DB 장애면 500 C007이고 본문에 SQL·예외 메시지가 없다")
    void databaseFailureIsInternalError() throws Exception {
        User me = user();
        doThrow(new DataAccessResourceFailureException("Communications link failure: select * from users"))
                .when(userRepository).findByIdAndDeletedAtIsNull(anyLong());

        String body = mockMvc.perform(meAs(me))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("C007"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContainIgnoringCase("select").doesNotContain("Communications link failure");
    }

    @Test
    @DisplayName("ME-23 Redis 장애 중에도 유효한 토큰이면 200 (블랙리스트 fail-open)")
    void redisFailureStillServesValidToken() throws Exception {
        User me = user();
        doThrow(new RedisConnectionFailureException("redis down")).when(redisTemplate).hasKey(anyString());

        mockMvc.perform(meAs(me))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.publicId").value(me.getPublicId()));
    }

    @Test
    @DisplayName("ME-26 조회는 탈퇴 제외 단건 조회(findByIdAndDeletedAtIsNull)를 쓰고 잠금 쿼리는 쓰지 않는다")
    void usesNonLockingLookup() throws Exception {
        User me = user();

        mockMvc.perform(meAs(me)).andExpect(status().isOk());

        verify(userRepository).findByIdAndDeletedAtIsNull(me.getId());
        verify(userRepository, never()).findForTrustScoreUpdate(anyLong());
        verify(userRepository, never()).findForPasswordResetUpdate(anyLong());
    }
}
