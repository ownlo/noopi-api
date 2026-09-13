package com.noopi.api;

import com.noopi.metrics.MetricsQuery;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/metrics")
@Tag(name = "운영 통계", description = "한국 시간 기준 일별 집계. 기본 10초 주기로 DB 반영")
public class MetricsController {
    private final MetricsQuery query;
    private final String apiKey;
    public MetricsController(MetricsQuery query, @Value("${noopi.metrics.api-key:}") String apiKey) {
        this.query = query; this.apiKey = apiKey;
    }
    @GetMapping("/daily")
    @Operation(summary = "일별 방·게임 통계 조회", description = "양 끝 날짜 포함 최대 366일. 방 지속시간은 종료일에 반영하며 분 단위입니다. 플레이 횟수는 started입니다.")
    public MetricsQuery.Daily daily(
        @Parameter(description = "METRICS_API_KEY에 설정한 운영 키", required = true)
        @RequestHeader(value = "X-Metrics-Key", required = false) String key,
        @Parameter(description = "조회 시작일", example = "2026-09-01")
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @Parameter(description = "조회 종료일", example = "2026-09-13")
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        ErrorCode.METRICS_ACCESS_DENIED.require(!apiKey.isBlank() && key != null
            && MessageDigest.isEqual(apiKey.getBytes(StandardCharsets.UTF_8), key.getBytes(StandardCharsets.UTF_8)));
        long days = ChronoUnit.DAYS.between(from, to);
        ErrorCode.BAD_REQUEST.require(days >= 0 && days < 366);
        return query.daily(from, to);
    }
}
