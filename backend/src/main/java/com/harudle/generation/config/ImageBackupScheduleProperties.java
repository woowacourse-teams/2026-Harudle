package com.harudle.generation.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.time.DateTimeException;
import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("harudle.generation.storage.r2.backup-scheduler")
public record ImageBackupScheduleProperties(
        boolean enabled,
        @DefaultValue("0 0 0 * * *") @NotBlank String cron,
        @DefaultValue("Asia/Seoul") @NotBlank String zone,
        @DefaultValue("100") @Min(1) @Max(1000) int batchSize
) {
    @AssertTrue(message = "R2 백업 cron 표현식이 유효해야 합니다.")
    public boolean isCronValid() {
        return cron != null && CronExpression.isValidExpression(cron);
    }

    @AssertTrue(message = "R2 백업 시간대가 유효해야 합니다.")
    public boolean isZoneValid() {
        try {
            ZoneId.of(zone == null ? "" : zone);
            return true;
        } catch (DateTimeException exception) {
            return false;
        }
    }
}
