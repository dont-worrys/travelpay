package com.travelpay.user.presentation.dto;

import java.time.Instant;
import java.util.List;

import com.travelpay.common.response.ApiResponse;
import com.travelpay.user.application.dto.TermsResult;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TermsResponseTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class));

    @Test
    void convertsApplicationResultsToTheApiFieldsWithUtcTimes() {
        var results = List.of(
                new TermsResult("SERVICE_TERMS", "2026-10-01", "서비스 이용약관", "서비스 약관 본문", true,
                        Instant.parse("2026-10-01T00:00:00Z")),
                new TermsResult("MARKETING_TERMS", "2026-10-02", "마케팅 안내", "선택 약관 본문", false,
                        Instant.parse("2026-10-02T03:00:00Z")));

        contextRunner.run(context -> {
            var mapper = context.getBean(JsonMapper.class);
            var response = ApiResponse.success(TermsResponse.from(results));

            assertEquals(mapper.readTree("""
                    {
                      "success": true,
                      "data": {
                        "terms": [
                          {
                            "termsCode": "SERVICE_TERMS",
                            "termsVersion": "2026-10-01",
                            "title": "서비스 이용약관",
                            "content": "서비스 약관 본문",
                            "isRequired": true,
                            "effectiveAt": "2026-10-01T00:00:00Z"
                          },
                          {
                            "termsCode": "MARKETING_TERMS",
                            "termsVersion": "2026-10-02",
                            "title": "마케팅 안내",
                            "content": "선택 약관 본문",
                            "isRequired": false,
                            "effectiveAt": "2026-10-02T03:00:00Z"
                          }
                        ]
                      },
                      "error": null
                    }
                    """), mapper.readTree(mapper.writeValueAsString(response)));
        });
    }

    @Test
    void representsNoCurrentTermsAsAnEmptyArray() {
        contextRunner.run(context -> {
            var mapper = context.getBean(JsonMapper.class);
            var response = ApiResponse.success(TermsResponse.from(List.of()));

            assertEquals(mapper.readTree("""
                    {
                      "success": true,
                      "data": {"terms": []},
                      "error": null
                    }
                    """), mapper.readTree(mapper.writeValueAsString(response)));
        });
    }
}
