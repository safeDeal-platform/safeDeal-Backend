package com.safedeal.domain.chat.controller;

import com.safedeal.domain.chat.dto.ChatReadResponse;
import com.safedeal.domain.chat.dto.ChatRoomCreateRequest;
import com.safedeal.domain.chat.dto.ChatRoomCreateResponse;
import com.safedeal.domain.chat.service.ChatReadCommandService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 채팅방 컨트롤러 테스트 — 서비스는 목, principal은 SecurityContext로 직접 주입한다
 * (standalone). {@code NotificationControllerTest}와 같은 구성이다.
 */
class ChatRoomControllerTest {

    private final ChatRoomCommandService chatRoomCommandService = mock(ChatRoomCommandService.class);
    private final ChatReadCommandService chatReadCommandService = mock(ChatReadCommandService.class);

    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new ChatRoomController(chatRoomCommandService, chatReadCommandService))
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

    @Test
    @DisplayName("읽음 처리 — principal의 userId·경로 roomId·본문 lastReadMessageId를 그대로 서비스에 넘기고 응답을 그대로 돌려준다")
    void markAsRead_delegatesWithPrincipalUserIdAndPathRoomId() throws Exception {
        when(chatReadCommandService.markAsRead(42L, "01J3ARSNIPROOM000000000000", 1024L))
                .thenReturn(new ChatReadResponse(1024L));

        mockMvc.perform(patch("/api/chat/rooms/01J3ARSNIPROOM000000000000/read")
                        .contentType("application/json")
                        .content("{\"lastReadMessageId\":1024}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.lastReadMessageId").value(1024));

        verify(chatReadCommandService).markAsRead(42L, "01J3ARSNIPROOM000000000000", 1024L);
    }

    @Test
    @DisplayName("읽음 처리 — 서비스가 404 C002를 던지면 그대로 404 C002")
    void markAsRead_serviceThrowsNotFound_isNotFound() throws Exception {
        doThrow(new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND, "채팅방을 찾을 수 없습니다."))
                .when(chatReadCommandService).markAsRead(42L, "01J3ARSNIPROOM000000000000", 1024L);

        mockMvc.perform(patch("/api/chat/rooms/01J3ARSNIPROOM000000000000/read")
                        .contentType("application/json")
                        .content("{\"lastReadMessageId\":1024}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("C002"));
    }

    @Test
    @DisplayName("읽음 처리 — 서비스가 400 C001을 던지면(다른 방 메시지 등) 그대로 400 C001")
    void markAsRead_serviceThrowsInvalidInput_isBadRequest() throws Exception {
        doThrow(new BusinessException(CommonErrorCode.INVALID_INPUT, "존재하지 않는 메시지입니다."))
                .when(chatReadCommandService).markAsRead(42L, "01J3ARSNIPROOM000000000000", 9999L);

        mockMvc.perform(patch("/api/chat/rooms/01J3ARSNIPROOM000000000000/read")
                        .contentType("application/json")
                        .content("{\"lastReadMessageId\":9999}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("C001"));
    }

    @Test
    @DisplayName("읽음 처리 — 본문에 lastReadMessageId가 없으면 400 C001 (검증 실패)")
    void markAsRead_missingField_isBadRequest() throws Exception {
        mockMvc.perform(patch("/api/chat/rooms/01J3ARSNIPROOM000000000000/read")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("C001"));
    }

    @Test
    @DisplayName("읽음 처리 — lastReadMessageId가 숫자가 아니면 400 C001")
    void markAsRead_nonNumericField_isBadRequest() throws Exception {
        mockMvc.perform(patch("/api/chat/rooms/01J3ARSNIPROOM000000000000/read")
                        .contentType("application/json")
                        .content("{\"lastReadMessageId\":\"abc\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("C001"));
    }
}
