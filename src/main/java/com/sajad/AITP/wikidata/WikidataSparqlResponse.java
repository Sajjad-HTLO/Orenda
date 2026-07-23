package com.sajad.AITP.wikidata;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * DTO for Wikidata SPARQL JSON response.
 * <p>
 * Wikidata SPARQL returns:
 * <pre>
 * { "head": {"vars": [...]}, "results": {"bindings": [...]} }
 * </pre>
 * Each binding is a map of variable name → { "type": "uri"|"literal", "value": "..." }.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class WikidataSparqlResponse {

    private Head head;
    private Results results;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Head {
        private List<String> vars;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Results {
        private List<Map<String, Binding>> bindings;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Binding {
        private String type;
        private String value;

        @JsonProperty("xml:lang")
        private String xmlLang;
    }
}