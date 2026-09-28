package com.safedeal.domain.chat.service;

import com.safedeal.domain.chat.dto.ChatRoomCreateRequest;
import com.safedeal.domain.chat.dto.ChatRoomCreateResponse;
import com.safedeal.domain.chat.entity.ChatRoom;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * 컨트롤러 진입점. 일부러 {@code @Transactional}을 안 붙인다 — 재시도가 완전히 새
 * 트랜잭션으로 깨끗하게 시작해야 하기 때문이다.
 *
 * <p>동시 클릭에서 진 쪽은 {@code uk_chat_rooms_buyer_listing} 위반으로 실패하는데, 그때만
 * 한 번 재시도한다(이긴 쪽이 이미 커밋했으니 이번엔 방을 찾는다). 그 외 제약 위반은 원인이
 * 다르므로 재시도하지 않고 그대로 올린다 — 다 재시도하면 두 번째 시도가 우연히 성공해
 * 원래 오류가 숨어버린다.
 */
@Service
@RequiredArgsConstructor
public class ChatRoomCommandService {

    private final ChatRoomCreator chatRoomCreator;

    public ChatRoomCreateResponse open(Long buyerId, ChatRoomCreateRequest request) {
        try {
            return chatRoomCreator.open(buyerId, request);
        } catch (DataIntegrityViolationException e) {
            if (!UniqueViolations.causedBy(e, ChatRoom.UK_BUYER_LISTING)) {
                throw e;
            }
            return chatRoomCreator.open(buyerId, request);
        }
    }
}
