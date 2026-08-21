package com.aitp.orenda.tripadvisor.restaurants;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RestaurantDetailParserTest {

    private final RestaurantDetailParser parser = new RestaurantDetailParser();

    @Test
    void parseExtractsRestaurantJsonLdFields() {
        String html = """
                <html><body>
                  <script type="application/ld+json">
                  {
                    "@type": "Restaurant",
                    "name": "360 Panorama Rooftop Restaurant",
                    "address": {
                      "@type": "PostalAddress",
                      "streetAddress": "Kuloğlu Mah. Turnacıbaşı Sk. No:25",
                      "addressLocality": "Beyoğlu",
                      "postalCode": "34433",
                      "addressCountry": { "@type": "Country", "name": "TR" }
                    },
                    "geo": { "@type": "GeoCoordinates", "latitude": "41.031", "longitude": "28.976" },
                    "aggregateRating": { "@type": "AggregateRating", "ratingValue": "4.5", "reviewCount": "2767" },
                    "priceRange": "$$$",
                    "servesCuisine": ["Turkish", "Seafood"],
                    "telephone": "+902123330000",
                    "image": "https://dynamic-media-cdn.tripadvisor.com/media/photo-o/1.jpg"
                  }
                  </script>
                  <h1>360 Panorama Rooftop Restaurant</h1>
                </body></html>
                """;

        RestaurantDetail detail = parser.parse(html,
                "https://www.tripadvisor.com/Restaurant_Review-g293974-d26224028-Reviews-360_Panorama_Rooftop_Restaurant-Istanbul.html",
                "https://www.tripadvisor.com/Restaurants-g293974-Istanbul.html");

        assertThat(detail.tripadvisorId()).isEqualTo(26224028L);
        assertThat(detail.name()).isEqualTo("360 Panorama Rooftop Restaurant");
        assertThat(detail.address()).contains("Kuloğlu Mah");
        assertThat(detail.locality()).isEqualTo("Beyoğlu");
        assertThat(detail.country()).isEqualTo("TR");
        assertThat(detail.postalCode()).isEqualTo("34433");
        assertThat(detail.latitude()).isEqualTo(41.031);
        assertThat(detail.longitude()).isEqualTo(28.976);
        assertThat(detail.rating()).isEqualTo(4.5);
        assertThat(detail.reviewCount()).isEqualTo(2767);
        assertThat(detail.priceRange()).isEqualTo("$$$");
        assertThat(detail.cuisine()).contains("Turkish");
        assertThat(detail.phone()).isEqualTo("+902123330000");
        assertThat(detail.imageUrls()).contains(
                "https://dynamic-media-cdn.tripadvisor.com/media/photo-o/1.jpg");
    }
}