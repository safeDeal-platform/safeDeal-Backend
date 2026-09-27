package com.safedeal.domain.chat.controller;

import com.safedeal.domain.chat.dto.ChatRoomCreateRequest;
import com.safedeal.domain.chat.dto.ChatRoomCreateResponse;
import com.safedeal.domain.chat.service.ChatRoomCommandService;
import com.safedeal.global.response.ApiResponse;
import com.safedeal.global.security.AuthenticatedUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 채팅 API. 경로는 API 명세서(CHT-1)의 설계를 그대로 쓴다.
 *
 * 현재 사용자는 {@code @AuthenticationPrincipal AuthenticatedUser}로만 꺼낸다(공통 규칙).
 * 인증되지 않은 요청은 시큐리티 계층에서 401(C005)로 걸러진다 — 채팅은 화이트리스트에
 * 넣지 않는다.
 */
@RestController
@RequestMapping("/api/chat/rooms")
@RequiredArgsConstructor
public class ChatRoomController {

    private final ChatRoomCommandService chatRoomCommandService;

    /**
     * 채팅방 생성 또는 기존 방 반환(API 명세서 CHT-1). 구매자는 토큰에서 꺼내고 요청
     * 본문으로 받지 않는다(위조 방지) — 매물 도메인의 판매자 취급과 같은 원칙이다.
     */
    @PostMapping
    public ApiResponse<ChatRoomCreateResponse> open(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody ChatRoomCreateRequest request) {
        return ApiResponse.success(chatRoomCommandService.open(user.userId(), request));
    }
}
