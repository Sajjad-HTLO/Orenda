package com.aitp.orenda.tripadvisor.model;

import java.util.List;

public record ListingParseResult(
        List<HotelListing> hotels
) {

    public int hotelCount() {
        return hotels.size();
    }
}
