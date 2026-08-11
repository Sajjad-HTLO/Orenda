package com.aitp.orenda.tripadvisor.parser;

import com.aitp.orenda.tripadvisor.model.ListingParseResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ListingParserTest {

    private final ListingParser parser = new ListingParser();

    @Test
    void parseExtractsUniqueHotelReviewLinks() {
        String html = """
                <html><body>
                  <a href="/Hotel_Review-g293974-d294607-Reviews-Test_Hotel-Istanbul.html?m=1" aria-label="Test Hotel">ignored</a>
                  <a href="/Hotel_Review-g293974-d294607-Reviews-Test_Hotel-Istanbul.html#photos">duplicate</a>
                  <a href="/Restaurants-g293974-Istanbul.html">Restaurant</a>
                  <a href="https://www.tripadvisor.com/Hotel_Review-g293974-d1-Reviews-Second_Hotel-Istanbul.html">Second Hotel</a>
                </body></html>
                """;

        ListingParseResult result = parser.parse(html, "https://www.tripadvisor.com/Hotels-g293974-Istanbul-Hotels.html");

        assertThat(result.hotelCount()).isEqualTo(2);
        assertThat(result.hotels())
                .extracting("url")
                .containsExactly(
                        "https://www.tripadvisor.com/Hotel_Review-g293974-d294607-Reviews-Test_Hotel-Istanbul.html",
                        "https://www.tripadvisor.com/Hotel_Review-g293974-d1-Reviews-Second_Hotel-Istanbul.html"
                );
        assertThat(result.hotels().getFirst().tripadvisorId()).isEqualTo(294607L);
        assertThat(result.hotels().getFirst().name()).isEqualTo("Test Hotel");
    }
}
