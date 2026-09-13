package com.safedeal.domain.listing.dto;

import com.safedeal.domain.listing.entity.Listing;
import com.safedeal.domain.listing.entity.ListingStatus;

/** 등록 결과. 내부 id는 내보내지 않는다 — 외부 식별자는 언제나 public_id다. */
public record ListingCreateResponse(String publicId, ListingStatus status) {

    public static ListingCreateResponse from(Listing listing) {
        return new ListingCreateResponse(listing.getPublicId(), listing.getStatus());
    }
}
