package com.travelpay.common.response;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PageResponseTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void mapsApplicationResultsToApiItemsWhilePreservingPagination() {
        var page = new PageImpl<>(List.of(
                new ItemResult("fifth", "internal-5"),
                new ItemResult("sixth", "internal-6")
        ), PageRequest.of(2, 2), 7);

        var response = PageResponse.from(page, result -> new ItemResponse(result.name()));

        assertEquals(mapper.readTree("""
                {
                  "items": [{"name": "fifth"}, {"name": "sixth"}],
                  "page": 2,
                  "size": 2,
                  "totalElements": 7,
                  "totalPages": 4
                }
                """), mapper.readTree(mapper.writeValueAsString(response)));
    }

    @Test
    void emptyPageReturnsAnEmptyArrayAndKeepsTheRequestedPageSize() {
        var page = new PageImpl<String>(List.of(), PageRequest.of(0, 20), 0);

        var response = PageResponse.from(page);

        assertEquals(mapper.readTree("""
                {
                  "items": [],
                  "page": 0,
                  "size": 20,
                  "totalElements": 0,
                  "totalPages": 0
                }
                """), mapper.readTree(mapper.writeValueAsString(response)));
    }

    @Test
    void responseItemsRemainStableWhenTheCallerChangesTheOriginalList() {
        var original = new ArrayList<>(List.of("first"));
        var response = new PageResponse<>(original, 0, 20, 1, 1);

        original.clear();

        assertEquals(List.of("first"), response.items());
        assertThrows(UnsupportedOperationException.class, () -> response.items().clear());
    }

    private record ItemResult(String name, String internalValue) {
    }

    private record ItemResponse(String name) {
    }
}
