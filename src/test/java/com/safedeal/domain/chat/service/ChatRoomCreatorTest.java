package com.safedeal.domain.chat.service;

import com.safedeal.domain.chat.dto.ChatRoomCreateRequest;
import com.safedeal.domain.chat.dto.ChatRoomCreateResponse;
import com.safedeal.domain.chat.entity.ChatRoom;
import com.safedeal.domain.chat.repository.ChatRoomRepository;
import com.safedeal.domain.listing.entity.Category;
import com.safedeal.domain.listing.entity.ItemCondition;
import com.safedeal.domain.listing.entity.Listing;
import com.safedeal.domain.listing.entity.ListingStatus;
import com.safedeal.domain.listing.repository.ListingRepository;
import com.safedeal.global.exception.BusinessException;
import com.safedeal.global.exception.CommonErrorCode;
import com.safedeal.global.util.PublicIdGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 채팅방 생성/재사용의 처리 순서(API 명세서 CHT-1)를 리포지토리를 목으로 대체해 검증한다.
 * 특히 "기존 방 조회가 매물 상태 검증보다 먼저"라는 규칙 — SOLD·삭제·BLOCKED 매물이어도
 * 기존 방은 반환돼야 한다.
 */
@ExtendWith(MockitoExtension.class)
class ChatRoomCreatorTest {

    @Mock
    ChatRoomRepository chatRoomRepository;

    @Mock
    ListingRepository listingRepository;

    @Mock
    PublicIdGenerator publicIdGenerator;

    @InjectMocks
    ChatRoomCreator chatRoomCreator;

    private static final Long BUYER_ID = 20L;
    private static final Long SELLER_ID = 30L;
    private static final Long LISTING_ID = 10L;
    private static final String LISTING_PUBLIC_ID = "01J3ARSNIPLISTING000001";

    private static Category leafCategory() {
        Category root = Category.root("DIGITAL", "디지털", 1);
        return Category.child("DIGITAL_PHONE", "휴대폰", root, 1);
    }

    private static Listing listing(ListingStatus status) {
        Listing listing = Listing.register(
                LISTING_PUBLIC_ID, SELLER_ID, "아이폰", "설명", 500_000,
                leafCategory(), ItemCondition.USED, "서울특별시", "강남구", false);
        ReflectionTestUtils.setField(listing, "id", LISTING_ID);
        ReflectionTestUtils.setField(listing, "status", status);
        return listing;
    }

    private static Listing deletedListing() {
        Listing listing = listing(ListingStatus.ACTIVE);
        ReflectionTestUtils.setField(listing, "deletedAt", Instant.parse("2026-09-01T00:00:00Z"));
        return listing;
    }

    private static ChatRoomCreateRequest request() {
        return new ChatRoomCreateRequest(LISTING_PUBLIC_ID);
    }

    @Test
    @DisplayName("없는 매물이면 404 C002")
    void open_missingListing_throwsNotFound() {
        when(listingRepository.findByPublicIdIncludingDeleted(LISTING_PUBLIC_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> chatRoomCreator.open(BUYER_ID, request()))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(CommonErrorCode.RESOURCE_NOT_FOUND));
    }

    @Test
    @DisplayName("기존 방이 있으면 ACTIVE 매물이라도 그 방을 반환하고, 저장은 호출하지 않는다")
    void open_existingRoom_reusesRegardlessOfCreate() {
        Listing listing = listing(ListingStatus.ACTIVE);
        ChatRoom existing = ChatRoom.open("01J3ARSNIPROOM0000000001", LISTING_ID, BUYER_ID, SELLER_ID);
        when(listingRepository.findByPublicIdIncludingDeleted(LISTING_PUBLIC_ID))
                .thenReturn(Optional.of(listing));
        when(chatRoomRepository.findByBuyerIdAndListingId(BUYER_ID, LISTING_ID))
                .thenReturn(Optional.of(existing));

        ChatRoomCreateResponse response = chatRoomCreator.open(BUYER_ID, request());

        assertThat(response.created()).isFalse();
        assertThat(response.roomId()).isEqualTo("01J3ARSNIPROOM0000000001");
        assertThat(response.status()).isEqualTo(ListingStatus.ACTIVE);
        verify(chatRoomRepository, never()).save(any());
    }

    @Test
    @DisplayName("SOLD 매물이어도 기존 방이 있으면 반환한다 (새 방만 금지)")
    void open_existingRoom_soldListing_stillReturnsRoom() {
        Listing listing = listing(ListingStatus.SOLD);
        ChatRoom existing = ChatRoom.open("01J3ARSNIPROOM0000000001", LISTING_ID, BUYER_ID, SELLER_ID);
        when(listingRepository.findByPublicIdIncludingDeleted(LISTING_PUBLIC_ID))
                .thenReturn(Optional.of(listing));
        when(chatRoomRepository.findByBuyerIdAndListingId(BUYER_ID, LISTING_ID))
                .thenReturn(Optional.of(existing));

        ChatRoomCreateResponse response = chatRoomCreator.open(BUYER_ID, request());

        assertThat(response.created()).isFalse();
        assertThat(response.status()).isEqualTo(ListingStatus.SOLD);
    }

    @Test
    @DisplayName("삭제된 매물이어도 기존 방이 있으면 반환하고, status는 DELETED로 합쳐 나간다")
    void open_existingRoom_deletedListing_stillReturnsRoomAsDeleted() {
        Listing listing = deletedListing();
        ChatRoom existing = ChatRoom.open("01J3ARSNIPROOM0000000001", LISTING_ID, BUYER_ID, SELLER_ID);
        when(listingRepository.findByPublicIdIncludingDeleted(LISTING_PUBLIC_ID))
                .thenReturn(Optional.of(listing));
        when(chatRoomRepository.findByBuyerIdAndListingId(BUYER_ID, LISTING_ID))
                .thenReturn(Optional.of(existing));

        ChatRoomCreateResponse response = chatRoomCreator.open(BUYER_ID, request());

        assertThat(response.created()).isFalse();
        assertThat(response.status()).isEqualTo(ListingStatus.DELETED);
    }

    @Test
    @DisplayName("판매자가 제재(BLOCKED)돼도 기존 방이 있으면 반환한다 — 분쟁 증거·환불 협의를 이어갈 수 있어야 한다")
    void open_existingRoom_blockedListing_stillReturnsRoom() {
        Listing listing = listing(ListingStatus.BLOCKED);
        ChatRoom existing = ChatRoom.open("01J3ARSNIPROOM0000000001", LISTING_ID, BUYER_ID, SELLER_ID);
        when(listingRepository.findByPublicIdIncludingDeleted(LISTING_PUBLIC_ID))
                .thenReturn(Optional.of(listing));
        when(chatRoomRepository.findByBuyerIdAndListingId(BUYER_ID, LISTING_ID))
                .thenReturn(Optional.of(existing));

        ChatRoomCreateResponse response = chatRoomCreator.open(BUYER_ID, request());

        assertThat(response.created()).isFalse();
        assertThat(response.status()).isEqualTo(ListingStatus.BLOCKED);
    }

    @Test
    @DisplayName("숨겨져 있던 기존 방에 재진입하면 buyerHiddenAt이 복구된다")
    void open_existingRoom_rejoinsAsBuyer() {
        Listing listing = listing(ListingStatus.ACTIVE);
        ChatRoom existing = ChatRoom.open("01J3ARSNIPROOM0000000001", LISTING_ID, BUYER_ID, SELLER_ID);
        ReflectionTestUtils.setField(existing, "buyerHiddenAt", Instant.parse("2026-09-01T00:00:00Z"));
        when(listingRepository.findByPublicIdIncludingDeleted(LISTING_PUBLIC_ID))
                .thenReturn(Optional.of(listing));
        when(chatRoomRepository.findByBuyerIdAndListingId(BUYER_ID, LISTING_ID))
                .thenReturn(Optional.of(existing));

        chatRoomCreator.open(BUYER_ID, request());

        assertThat(existing.getBuyerHiddenAt()).isNull();
    }

    @Test
    @DisplayName("본인 매물이면 기존 방이 없을 때 400 C001")
    void open_ownListing_noExistingRoom_throwsInvalidInput() {
        Listing listing = listing(ListingStatus.ACTIVE);
        when(listingRepository.findByPublicIdIncludingDeleted(LISTING_PUBLIC_ID))
                .thenReturn(Optional.of(listing));
        when(chatRoomRepository.findByBuyerIdAndListingId(SELLER_ID, LISTING_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> chatRoomCreator.open(SELLER_ID, request()))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(CommonErrorCode.INVALID_INPUT));
        verify(chatRoomRepository, never()).save(any());
    }

    @Test
    @DisplayName("SOLD 매물에 기존 방이 없으면 새 방을 못 만들어 404 C002")
    void open_soldListing_noExistingRoom_throwsNotFound() {
        Listing listing = listing(ListingStatus.SOLD);
        when(listingRepository.findByPublicIdIncludingDeleted(LISTING_PUBLIC_ID))
                .thenReturn(Optional.of(listing));
        when(chatRoomRepository.findByBuyerIdAndListingId(BUYER_ID, LISTING_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> chatRoomCreator.open(BUYER_ID, request()))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(CommonErrorCode.RESOURCE_NOT_FOUND));
        verify(chatRoomRepository, never()).save(any());
    }

    @Test
    @DisplayName("삭제된 매물에 기존 방이 없으면 404 C002")
    void open_deletedListing_noExistingRoom_throwsNotFound() {
        Listing listing = deletedListing();
        when(listingRepository.findByPublicIdIncludingDeleted(LISTING_PUBLIC_ID))
                .thenReturn(Optional.of(listing));
        when(chatRoomRepository.findByBuyerIdAndListingId(BUYER_ID, LISTING_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> chatRoomCreator.open(BUYER_ID, request()))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(CommonErrorCode.RESOURCE_NOT_FOUND));
    }

    @Test
    @DisplayName("ACTIVE 매물이고 기존 방이 없으면 새로 만든다")
    void open_activeListing_noExistingRoom_createsNewRoom() {
        Listing listing = listing(ListingStatus.ACTIVE);
        when(listingRepository.findByPublicIdIncludingDeleted(LISTING_PUBLIC_ID))
                .thenReturn(Optional.of(listing));
        when(chatRoomRepository.findByBuyerIdAndListingId(BUYER_ID, LISTING_ID))
                .thenReturn(Optional.empty());
        when(publicIdGenerator.generate()).thenReturn("01J3ARSNIPROOM0000000002");
        when(chatRoomRepository.save(any(ChatRoom.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ChatRoomCreateResponse response = chatRoomCreator.open(BUYER_ID, request());

        assertThat(response.created()).isTrue();
        assertThat(response.roomId()).isEqualTo("01J3ARSNIPROOM0000000002");
        assertThat(response.status()).isEqualTo(ListingStatus.ACTIVE);
    }
}
