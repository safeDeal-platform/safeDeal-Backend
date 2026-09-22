package com.safedeal.domain.listing.dto;

import com.safedeal.domain.listing.entity.Listing;
import com.safedeal.domain.listing.entity.ListingStatus;
import com.safedeal.domain.listing.entity.SoldSource;

public record ListingStatusChangeResponse(ListingStatus status, SoldSource soldSource) {

    public static ListingStatusChangeResponse from(Listing listing) {
        return new ListingStatusChangeResponse(listing.getStatus(), listing.getSoldSource());
    }
}
