package com.harudle.feed.presentation;

import static org.assertj.core.api.Assertions.assertThat;

import com.harudle.feed.service.dto.FeedPreviewMetadata;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class FeedPreviewHtmlTest {

    private static final URI DETAIL = URI.create("https://harudle.example/feeds/1");
    private static final URI IMAGE = URI.create("https://images.example/api/v1/public/feeds/1/image");

    @Test
    void escapesMetadataAndKeepsTheRedirectScriptIndependentOfUserText() throws Exception {
        String html = FeedPreviewHtml.render(new FeedPreviewMetadata("<script>alert('nickname')</script>",
                "\" onload=\"alert('category') & 설명"), DETAIL, IMAGE);
        assertThat(html).contains("&lt;script&gt;", "&quot; onload=&quot;", "&amp; 설명")
                .doesNotContain("<script>alert", "content=\"\" onload=");
        var scripts = Pattern.compile("<script>(.*?)</script>", Pattern.DOTALL).matcher(html);
        assertThat(scripts.find()).isTrue();
        String script = scripts.group(1);
        assertThat(script).contains("window.location.replace", "window.location.search", "window.location.hash")
                .doesNotContain("nickname", "category");
        assertThat(scripts.find()).isFalse();
        String hash = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256")
                .digest(script.getBytes(StandardCharsets.UTF_8)));
        assertThat(FeedPreviewHtml.contentSecurityPolicy()).contains("'sha256-" + hash + "'")
                .contains("default-src 'none'", "base-uri 'none'", "frame-ancestors 'none'")
                .doesNotContain("unsafe-inline");
    }

    @Test
    void initialHtmlHasFeedMetadataAndAStableImageForBotsWithoutRunningJavascript() {
        String html = FeedPreviewHtml.render(new FeedPreviewMetadata("네오님의 네컷만화 | 하루들", "일상의 네컷만화"),
                DETAIL, IMAGE);
        assertThat(html).contains("<meta property=\"og:title\" content=\"네오님의 네컷만화 | 하루들\">")
                .contains("<meta property=\"og:url\" content=\"" + DETAIL + "\">")
                .contains("<link rel=\"canonical\" href=\"" + DETAIL + "\">")
                .contains("<meta property=\"og:image\" content=\"" + IMAGE + "\">")
                .contains("<meta name=\"twitter:image\" content=\"" + IMAGE + "\">")
                .contains("content=\"image/jpeg\"", "content=\"1200\"", "content=\"630\"")
                .doesNotContain("X-Amz-", "http-equiv=\"refresh\"");
    }
}
