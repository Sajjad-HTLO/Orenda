package com.aitp.orenda.tripadvisor.model;

import lombok.Builder;

@Builder
public record CrawlPage(
        int offset,
        String url
) {
}
