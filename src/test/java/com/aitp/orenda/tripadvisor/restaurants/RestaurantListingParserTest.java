package com.aitp.orenda.tripadvisor.restaurants;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RestaurantListingParserTest {

    private final RestaurantListingParser parser = new RestaurantListingParser();

    @Test
    void parseExtractsUniqueRestaurantReviewLinks() {
        String html = """
                <html><body>
                  <a href="/Restaurant_Review-g293974-d26224028-Reviews-360_Panorama_Rooftop_Restaurant-Istanbul.html?m=1" class="photo"></a>
                  <a href="/Restaurant_Review-g293974-d26224028-Reviews-360_Panorama_Rooftop_Restaurant-Istanbul.html">360 Panorama Rooftop Restaurant</a>
                  <a href="/Hotel_Review-g293974-d294607-Reviews-Test_Hotel-Istanbul.html">Test Hotel</a>
                  <a href="https://www.tripadvisor.com/Restaurant_Review-g293974-d1771102-Reviews-Last_Ottoman_Cafe_Restaurant-Istanbul.html">Last Ottoman Cafe &amp; Restaurant</a>
                </body></html>
                """;

        RestaurantListingParseResult result = parser.parse(
                html, "https://www.tripadvisor.com/Restaurants-g293974-Istanbul.html");

        assertThat(result.restaurantCount()).isEqualTo(2);
        assertThat(result.restaurants())
                .extracting("url")
                .containsExactly(
                        "https://www.tripadvisor.com/Restaurant_Review-g293974-d26224028-Reviews-360_Panorama_Rooftop_Restaurant-Istanbul.html",
                        "https://www.tripadvisor.com/Restaurant_Review-g293974-d1771102-Reviews-Last_Ottoman_Cafe_Restaurant-Istanbul.html"
                );
        assertThat(result.restaurants().getFirst().tripadvisorId()).isEqualTo(26224028L);
        assertThat(result.restaurants().getFirst().name()).isEqualTo("360 Panorama Rooftop Restaurant");
        assertThat(result.restaurants().get(1).name()).isEqualTo("Last Ottoman Cafe & Restaurant");
    }

    @Test
    void parsePrefersNamedAnchorOverPhotoCarouselAnchor() {
        String html = """
                <html><body>
                  <a href="/Restaurant_Review-g293974-d1644684-Reviews-Buhara_Kebab_house_Restaurant-Istanbul.html" class="photo" aria-label="Previous Photo"></a>
                  <a href="/Restaurant_Review-g293974-d1644684-Reviews-Buhara_Kebab_house_Restaurant-Istanbul.html">Buhara Kebab house Restaurant</a>
                </body></html>
                """;

        RestaurantListingParseResult result = parser.parse(
                html, "https://www.tripadvisor.com/Restaurants-g293974-Istanbul.html");

        assertThat(result.restaurantCount()).isEqualTo(1);
        assertThat(result.restaurants().getFirst().name()).isEqualTo("Buhara Kebab house Restaurant");
    }

    @Test
    void parseStripsRankPrefixFromName() {
        String html = """
                <html><body>
                  <a href="/Restaurant_Review-g293974-d13976900-Reviews-Hanzade_Bosphorus_Restaurant-Istanbul.html">3. Hanzade Bosphorus Restaurant</a>
                </body></html>
                """;

        RestaurantListingParseResult result = parser.parse(
                html, "https://www.tripadvisor.com/Restaurants-g293974-Istanbul.html");

        assertThat(result.restaurantCount()).isEqualTo(1);
        assertThat(result.restaurants().getFirst().name()).isEqualTo("Hanzade Bosphorus Restaurant");
    }
}