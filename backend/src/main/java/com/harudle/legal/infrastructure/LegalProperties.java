package com.harudle.legal.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("harudle.legal")
public record LegalProperties(boolean requireTermsAcceptance) {
}
