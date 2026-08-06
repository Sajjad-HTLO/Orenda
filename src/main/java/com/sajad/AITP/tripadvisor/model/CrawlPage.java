package com.sajad.AITP.tripadvisor.model;

import lombok.Builder;

@Builder
public record CrawlPage(
        int offset,
        String url
) {
}
