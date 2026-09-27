package com.safedeal.domain.chat.service;

import com.safedeal.domain.chat.dto.ChatRoomCreateRequest;
import com.safedeal.domain.chat.dto.ChatRoomCreateResponse;
import com.safedeal.domain.chat.entity.ChatRoom;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * 컨트롤러 진입점. <b>의도적으로 {@code @Transactional}을 붙이지 않는다.</b>
 *
 * <p>동시 클릭에서 진 쪽은 {@link ChatRoomCreator#open}의 INSERT가
 * {@code uk_chat_rooms_buyer_listing}에 걸려 {@link DataIntegrityViolationException}을
 * 던지고, 그 트랜잭션은 롤백된다. 트랜잭션 안에서 이 예외를 삼키면 안 된다(신뢰도 도메인의
 * 선례 — "예외를 삼키면 트랜잭션이 이미 롤백 표시된 뒤라 뒷수습이 지저분해진다"). 여기서는
 * catch 시점에 트랜잭션이 이미 정상 종료(롤백 완료)됐으므로, 재시도는 완전히 새 트랜잭션이고
 * 안전하다.
 *
 * <p>재시도는 <b>1회만</b> 한다 — 이겼던 상대의 커밋이 이미 끝났으므로 2단계(기존 방 조회)가
 * 이번엔 반드시 방을 찾는다. 두 번째도 실패하면 그건 동시성 경쟁이 아니라 버그이므로 조용히
 * 감추지 않고 그대로 올린다(루프를 돌리지 않는다).
 *
 * <p><b>{@link ChatRoom#UK_BUYER_LISTING} 위반일 때만 재시도한다.</b> 방에는
 * {@link ChatRoom#UK_PUBLIC_ID}도 있다 — 다른 제약 위반까지 재시도하면 두 번째 시도가 새
 * ULID로 우연히 성공하면서 원래 오류가 가려진다. 그 외 무결성 위반은 즉시 올린다.
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
