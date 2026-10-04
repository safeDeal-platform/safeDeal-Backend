package com.safedeal.domain.chat.dto;

import com.safedeal.domain.chat.entity.ChatRoom;
import com.safedeal.domain.listing.entity.Listing;
import com.safedeal.domain.listing.entity.ListingStatus;

/**
 * 채팅방 생성/재사용 결과. {@code created}로 신규 여부를 이미 알려주므로 HTTP 상태코드는
 * 항상 200이다.
 *
 * @param status 삭제된 매물은 {@link ListingStatus#DELETED}로 합쳐 내보낸다 — 그대로
 *               내보내면 삭제된 매물의 방에 "판매중" 같은 잘못된 배너가 뜬다.
 */
public record ChatRoomCreateResponse(String roomId, boolean created, ListingStatus status) {

    public static ChatRoomCreateResponse of(ChatRoom room, Listing listing, boolean created) {
        ListingStatus status = listing.isDeleted() ? ListingStatus.DELETED : listing.getStatus();
        return new ChatRoomCreateResponse(room.getPublicId(), created, status);
    }
}
