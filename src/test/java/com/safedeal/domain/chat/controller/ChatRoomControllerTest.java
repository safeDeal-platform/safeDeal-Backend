package com.safedeal.domain.chat.controller;

import com.safedeal.domain.chat.dto.ChatRoomCreateRequest;
import com.safedeal.domain.chat.dto.ChatRoomCreateResponse;
import com.safedeal.domain.chat.service.ChatRoomCommandService;
import com.safedeal.domain.listing.entity.ListingStatus;
import com.safedeal.global.exception.BusinessException;
import com.safedeal.global.exception.CommonErrorCode;
import com.safedeal.global.exception.GlobalExceptionHandler;
import com.safedeal.global.security.AuthenticatedUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 채팅방 생성 컨트롤러 테스트 — 서비스는 목, principal은 SecurityContext로 직접 주입한다
 * (standalone). {@code NotificationControllerTest}와 같은 구성이다.
 */
class ChatRoomControllerTest {

    private final ChatRoomCommandService chatRoomCommandService = mock(ChatRoomCommandService.class);

    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new ChatRoomController(chatRoomCommandService))
            .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @BeforeEach
    void authenticate() {
        Authentication auth = new UsernamePasswordAuthenticationToken(
                new AuthenticatedUser(42L, "USER"), null,
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("principal의 userId를 구매자로 넘기고, roomId/created/status를 그대로 응답한다")
    void open_delegatesWithPrincipalUserId() throws Exception {
        when(chatRoomCommandService.open(eq(42L), eq(new ChatRoomCreateRequest("01J3ARSNIPLISTING000000000"))))
                .thenReturn(new ChatRoomCreateResponse("01J3ARSNIPROOM000000000000", true, ListingStatus.ACTIVE));

        mockMvc.perform(post("/api/chat/rooms")
                        .contentType("application/json")
                        .content("{\"listingId\":\"01J3ARSNIPLISTING000000000\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.roomId").value("01J3ARSNIPROOM000000000000"))
                .andExpect(jsonPath("$.data.created").value(true))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));

        verify(chatRoomCommandService).open(42L, new ChatRoomCreateRequest("01J3ARSNIPLISTING000000000"));
    }

    @Test
    @DisplayName("기존 방을 반환할 때도 created=false 필드가 생략되지 않는다")
    void open_reuse_doesNotOmitCreatedField() throws Exception {
        when(chatRoomCommandService.open(eq(42L), eq(new ChatRoomCreateRequest("01J3ARSNIPLISTING000000000"))))
                .thenReturn(new ChatRoomCreateResponse("01J3ARSNIPROOM000000000000", false, ListingStatus.SOLD));

        mockMvc.perform(post("/api/chat/rooms")
                        .contentType("application/json")
                        .content("{\"listingId\":\"01J3ARSNIPLISTING000000000\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.created").value(false))
                .andExpect(jsonPath("$.data.status").value("SOLD"));
    }

    @Test
    @DisplayName("본인 매물이면 400 C001")
    void open_ownListing_isBadRequest() throws Exception {
        doThrow(new BusinessException(CommonErrorCode.INVALID_INPUT, "본인 매물에는 채팅방을 만들 수 없습니다."))
                .when(chatRoomCommandService).open(eq(42L), eq(new ChatRoomCreateRequest("01J3ARSNIPLISTING000000000")));

        mockMvc.perform(post("/api/chat/rooms")
                        .contentType("application/json")
                        .content("{\"listingId\":\"01J3ARSNIPLISTING000000000\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("C001"));
    }

    @Test
    @DisplayName("없거나 새 방을 만들 수 없는 매물이면 404 C002")
    void open_missingOrBlockedListing_isNotFound() throws Exception {
        doThrow(new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND, "매물을 찾을 수 없습니다."))
                .when(chatRoomCommandService).open(eq(42L), eq(new ChatRoomCreateRequest("01J3ARSNIPLISTING000000000")));

        mockMvc.perform(post("/api/chat/rooms")
                        .contentType("application/json")
                        .content("{\"listingId\":\"01J3ARSNIPLISTING000000000\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("C002"));
    }

    @Test
    @DisplayName("listingId가 빈 문자열이면 400 C001 (검증 실패)")
    void open_blankListingId_isBadRequest() throws Exception {
        mockMvc.perform(post("/api/chat/rooms")
                        .contentType("application/json")
                        .content("{\"listingId\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("C001"));
    }
}
