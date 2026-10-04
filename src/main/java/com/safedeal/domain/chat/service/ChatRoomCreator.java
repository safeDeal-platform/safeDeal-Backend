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

import java.time.Instant;

/** 채팅방 생성의 단일 시도(트랜잭션 경계). {@link ChatRoomCommandService}가 UNIQUE 충돌 시 이 메서드를 재시도한다. */
@Service
@RequiredArgsConstructor
public class ChatRoomCreator {

    private final ChatRoomRepository chatRoomRepository;
    private final ListingRepository listingRepository;
    private final PublicIdGenerator publicIdGenerator;

    /**
     * 기존 방 조회가 매물 상태 확인보다 먼저다 — 매물이 팔렸거나 삭제·제재됐어도 기존 방은
     * 그대로 돌려준다(분쟁 증거·환불 협의를 이어갈 수 있어야 한다).
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
            if (existing.getBuyerHiddenAt() != null) {
                // 숨겨진 방일 때만 복구 UPDATE를 낸다 — 보이는 방이면 이 블록을 건너뛴다.
                chatRoomRepository.rejoinAsBuyer(existing.getId(), Instant.now());
            }
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
