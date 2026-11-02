package com.harudle.auth.infrastructure.oauth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.Assert;

@ConfigurationProperties("app.auth.apple")
public record AppleClientSecretProperties(
        String clientId,
        String teamId,
        String keyId,
        String privateKeyPath
) {

    public AppleClientSecretProperties {
        Assert.hasText(clientId, "Apple clientId는 필수입니다.");
        Assert.hasText(teamId, "Apple teamId는 필수입니다.");
        Assert.hasText(keyId, "Apple keyId는 필수입니다.");
        Assert.hasText(privateKeyPath, "Apple privateKeyPath는 필수입니다.");
    }

}
