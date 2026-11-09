package com.harudle.campus.infrastructure;

import com.harudle.campus.application.CampusLinkClaims;
import com.harudle.campus.application.CampusLinkProperties;
import com.harudle.campus.application.CampusLinkTokenSigner;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.util.Date;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 캠퍼스 연결 토큰을 HS256 JWT로 서명한다. 하루들 Access Token과 다른 키 · 발급 대상(aud)을 쓴다.
 *
 * <p>JwtEncoder나 SecretKey 빈을 새로 만들지 않아 기존 인증 설정의 빈 주입에 영향을 주지 않는다.
 */
@Component
@ConditionalOnProperty(prefix = "harudle.campus", name = "enabled", havingValue = "true")
public class HmacCampusLinkTokenSigner implements CampusLinkTokenSigner {

    static final String ISSUER = "harudle";
    static final String AUDIENCE = "harudle-campus";

    private final MACSigner signer;

    public HmacCampusLinkTokenSigner(CampusLinkProperties properties) {
        try {
            this.signer = new MACSigner(properties.signingKey());
        } catch (JOSEException exception) {
            throw new IllegalStateException("캠퍼스 연결 서명 키를 사용할 수 없습니다.", exception);
        }
    }

    @Override
    public String sign(CampusLinkClaims claims) {
        JWTClaimsSet claimsSet = new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .audience(AUDIENCE)
                .subject(claims.userId().toString())
                .jwtID(UUID.randomUUID().toString())
                .issueTime(Date.from(claims.issuedAt()))
                .expirationTime(Date.from(claims.expiresAt()))
                .claim("nickname", claims.nickname())
                .claim("picture", pictureOf(claims))
                .claim("state", claims.state())
                .build();
        JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.HS256)
                .type(JOSEObjectType.JWT)
                .build();
        SignedJWT jwt = new SignedJWT(header, claimsSet);
        try {
            jwt.sign(signer);
        } catch (JOSEException exception) {
            throw new IllegalStateException("캠퍼스 연결 토큰에 서명할 수 없습니다.", exception);
        }
        return jwt.serialize();
    }

    private String pictureOf(CampusLinkClaims claims) {
        if (claims.profileImageUrl() == null) {
            return null;
        }
        return claims.profileImageUrl().toString();
    }
}
