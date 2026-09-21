package com.safedeal.domain.chat.dto;

import com.safedeal.domain.chat.entity.ChatRoom;
import com.safedeal.domain.listing.entity.Listing;
import com.safedeal.domain.listing.entity.ListingStatus;

/**
 * 채팅방 생성/재사용 결과(API 명세서 CHT-1). {@code roomId}는 내부 id가 아니라 방의
 * public_id다. {@code created}가 새로 만들었는지 기존 방을 돌려줬는지 계약에 이미 담고
 * 있으므로, HTTP 상태코드(200/201)로 같은 정보를 또 표현하지 않는다 — 항상 200이다.
 *
 * @param status 매물 상태. 삭제된 매물은 {@link ListingStatus#DELETED}로 합쳐 내보낸다 —
 *               {@code Listing}에서 삭제는 status와 별개 축이라, 그대로 내보내면 삭제된
 *               매물의 방에 "판매중" 같은 잘못된 배너가 뜬다.
 */
public record ChatRoomCreateResponse(String roomId, boolean created, ListingStatus status) {

    public static ChatRoomCreateResponse of(ChatRoom room, Listing listing, boolean created) {
        ListingStatus status = listing.isDeleted() ? ListingStatus.DELETED : listing.getStatus();
        return new ChatRoomCreateResponse(room.getPublicId(), created, status);
    }
}
