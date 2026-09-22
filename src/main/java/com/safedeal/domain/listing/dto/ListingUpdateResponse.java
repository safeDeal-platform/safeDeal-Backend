package com.safedeal.domain.listing.dto;

import com.safedeal.domain.listing.entity.Listing;
import com.safedeal.domain.listing.entity.ListingStatus;

/** 수정 결과. 다음 수정에 쓸 version을 함께 내려준다. */
public record ListingUpdateResponse(String publicId, ListingStatus status, Long version) {

    public static ListingUpdateResponse from(Listing listing) {
        return new ListingUpdateResponse(
                listing.getPublicId(), listing.getStatus(), listing.getVersion());
    }
}
