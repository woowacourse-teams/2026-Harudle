package com.harudle.feed.presentation;

import com.harudle.feed.service.dto.FeedPreviewMetadata;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import org.springframework.web.util.HtmlUtils;

/** HTML에는 공개 프로필과 분류만 포함하고 원문과 저장소 주소는 포함하지 않는다. */
final class FeedPreviewHtml {

    private static final String REDIRECT_SCRIPT = "const target = new URL(document.querySelector('link[rel=canonical]').href);"
            + "if (target.origin !== window.location.origin || target.pathname !== window.location.pathname) {"
            + "target.search = window.location.search; target.hash = window.location.hash;"
            + "window.location.replace(target.href);}";

    private FeedPreviewHtml() {
    }

    static String contentSecurityPolicy() {
        try {
            String hash = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256")
                    .digest(REDIRECT_SCRIPT.getBytes(StandardCharsets.UTF_8)));
            return "default-src 'none'; script-src 'sha256-" + hash
                    + "'; base-uri 'none'; frame-ancestors 'none'; form-action 'none'";
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("공유 페이지 보안 정책을 만들 수 없습니다.", exception);
        }
    }

    static String render(FeedPreviewMetadata metadata, URI feedUrl, URI imageUrl) {
        String title = escape(metadata.title());
        String description = escape(metadata.description());
        String detail = escape(feedUrl.toASCIIString());
        String image = escape(imageUrl.toASCIIString());
        return """
                <!doctype html>
                <html lang="ko">
                <head>
                  <meta charset="UTF-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1.0">
                  <title>%s</title>
                  <link rel="canonical" href="%s">
                  <meta name="description" content="%s">
                  <meta property="og:type" content="article">
                  <meta property="og:site_name" content="하루들">
                  <meta property="og:title" content="%s">
                  <meta property="og:description" content="%s">
                  <meta property="og:url" content="%s">
                  <meta property="og:image" content="%s">
                  <meta property="og:image:type" content="image/jpeg">
                  <meta property="og:image:width" content="1200">
                  <meta property="og:image:height" content="630">
                  <meta property="og:image:alt" content="%s">
                  <meta name="twitter:card" content="summary_large_image">
                  <meta name="twitter:title" content="%s">
                  <meta name="twitter:description" content="%s">
                  <meta name="twitter:image" content="%s">
                </head>
                <body>
                  <p><a href="%s">하루들에서 네컷만화 보기</a></p>
                  <script>%s</script>
                </body>
                </html>
                """.formatted(title, detail, description, title, description, detail, image, title,
                title, description, image, detail, REDIRECT_SCRIPT);
    }

    private static String escape(String value) {
        return HtmlUtils.htmlEscape(value, StandardCharsets.UTF_8.name());
    }
}
