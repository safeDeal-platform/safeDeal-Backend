package com.safedeal.domain.chat.service;

import com.safedeal.domain.chat.dto.ChatMessageAppendCommand;
import com.safedeal.domain.chat.dto.ChatMessageAppendResult;
import com.safedeal.domain.chat.entity.ChatMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * 다음 슬라이스(STOMP)의 진입점이 될 서비스. <b>의도적으로 {@code @Transactional}을 붙이지
 * 않는다</b> — {@link ChatRoomCommandService}와 같은 이유(트랜잭션 밖에서만 예외를 안전하게
 * 삼킬 수 있다).
 *
 * <p><b>어제(채팅방 생성)와 경쟁의 성격이 다르다.</b> 어제는 서로 다른 두 요청이 같은
 * (buyer_id, listing_id)를 놓고 경쟁했다. 오늘은 <b>같은 요청의 네트워크 재전송</b>이
 * 같은 (room_id, client_message_id)로 겹치는 상황이다 — 클라이언트가 응답을 못 받고
 * 다시 보낸 경우다. 그래도 처리 방식은 같다: 재시도 시점의 선조회가 먼저 커밋된(자기 자신의
 * 이전 시도이거나 상대의) 행을 찾아 같은 결과를 돌려준다.
 *
 * <p>재시도는 1회만 한다 — 두 번째도 실패하면 경쟁이 아니라 버그이므로 그대로 올린다.
 * {@link ChatMessage#UK_ROOM_CLIENT_MSG} 위반일 때만 재시도한다({@link ChatRoomCommandService}와
 * 같은 이유 — 원인을 모르는 무결성 위반을 다시 시도해 원래 오류를 가리지 않는다).
 */
@Service
@RequiredArgsConstructor
public class ChatMessageCommandService {

    private final ChatMessageAppender chatMessageAppender;

    public ChatMessageAppendResult append(Long senderId, ChatMessageAppendCommand command) {
        try {
            return chatMessageAppender.append(senderId, command);
        } catch (DataIntegrityViolationException e) {
            if (!UniqueViolations.causedBy(e, ChatMessage.UK_ROOM_CLIENT_MSG)) {
                throw e;
            }
            return chatMessageAppender.append(senderId, command);
        }
    }
}
