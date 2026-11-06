package com.harudle.legal.infrastructure;

import com.harudle.common.security.AuthTimeConfiguration;
import com.harudle.legal.application.LegalDocumentCatalog;
import com.harudle.legal.application.TermsAcceptanceService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;

/** Read-only terms checks for maintenance authorization; acceptance endpoints are not registered. */
@EnableConfigurationProperties(LegalProperties.class)
@Import({TermsAcceptanceService.class, LegalDocumentCatalog.class, JdbcTermsAcceptanceRepository.class,
        AuthTimeConfiguration.class})
public class ImageRecoveryTermsConfiguration {
}
