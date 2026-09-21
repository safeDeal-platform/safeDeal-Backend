package com.safedeal.domain.chat.service;

import com.safedeal.domain.chat.dto.ChatRoomCreateRequest;
import com.safedeal.domain.chat.dto.ChatRoomCreateResponse;
import com.safedeal.domain.chat.entity.ChatRoom;
import com.safedeal.domain.chat.repository.ChatRoomRepository;
import com.safedeal.domain.listing.entity.Listing;
import com.safedeal.domain.listing.repository.ListingRepository;
import com.safedeal.global.exception.BusinessException;
import com.safedeal.global.exception.CommonErrorCode;
import com.safedeal.global.util.PublicIdGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 채팅방 생성의 단일 시도(트랜잭션 경계). {@link ChatRoomCommandService}가 이 메서드를
 * 감싸 UNIQUE 충돌 시 재시도한다 — 재시도 catch가 이 트랜잭션 "밖"에 있어야 하므로 빈을
 * 분리했다(자기 호출은 프록시를 안 타 {@code @Transactional}이 무시된다).
 */
@Service
@RequiredArgsConstructor
public class ChatRoomCreator {

    private final ChatRoomRepository chatRoomRepository;
    private final ListingRepository listingRepository;
    private final PublicIdGenerator publicIdGenerator;

    /**
     * API 명세서 CHT-1 처리 순서. <b>기존 방 조회(2단계)가 매물 상태 검증(3·4단계)보다
     * 먼저다</b> — 매물이 SOLD든 삭제됐든 BLOCKED든, 기존 방이 있으면 상태를 묻지 않고
     * 돌려준다("SOLD·삭제 매물은 기존 방만 반환"). 제재(BLOCKED)된 판매자와의 기존
     * 대화창도 같은 이유로 열어둔다 — 분쟁 증거·환불 협의를 이어갈 수 있어야 한다.
     */
    @Transactional
    public ChatRoomCreateResponse open(Long buyerId, ChatRoomCreateRequest request) {
        Listing listing = listingRepository.findByPublicIdIncludingDeleted(request.listingId())
                .orElseThrow(() -> new BusinessException(
                        CommonErrorCode.RESOURCE_NOT_FOUND, "매물을 찾을 수 없습니다."));

        ChatRoom existing = chatRoomRepository
                .findByBuyerIdAndListingId(buyerId, listing.getId())
                .orElse(null);
        if (existing != null) {
            existing.rejoinAsBuyer();
            return ChatRoomCreateResponse.of(existing, listing, false);
        }

        if (listing.isOwnedBy(buyerId)) {
            throw new BusinessException(
                    CommonErrorCode.INVALID_INPUT, "본인 매물에는 채팅방을 만들 수 없습니다.");
        }
        if (!listing.isListable()) {
            throw new BusinessException(
                    CommonErrorCode.RESOURCE_NOT_FOUND, "채팅방을 만들 수 없는 매물입니다.");
        }

        ChatRoom room = chatRoomRepository.save(ChatRoom.open(
                publicIdGenerator.generate(), listing.getId(), buyerId, listing.getSellerId()));
        return ChatRoomCreateResponse.of(room, listing, true);
    }
}
