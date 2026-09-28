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

/** 채팅 API. 인증되지 않은 요청은 401(C005)로 막는다 — 화이트리스트에 넣지 않는다. */
@RestController
@RequestMapping("/api/chat/rooms")
@RequiredArgsConstructor
public class ChatRoomController {

    private final ChatRoomCommandService chatRoomCommandService;

    /** 채팅방 생성 또는 기존 방 반환. 구매자는 토큰에서 꺼낸다(요청 본문 아님) — 본인 확인 위조 방지. */
    @PostMapping
    public ApiResponse<ChatRoomCreateResponse> open(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody ChatRoomCreateRequest request) {
        return ApiResponse.success(chatRoomCommandService.open(user.userId(), request));
    }
}
