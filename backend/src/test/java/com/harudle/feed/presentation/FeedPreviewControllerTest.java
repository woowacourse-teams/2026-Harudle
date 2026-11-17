package com.harudle.feed.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.harudle.auth.infrastructure.UserRepository;
import com.harudle.common.config.TimeConfiguration;
import com.harudle.common.error.ApiExceptionLoggerTestConfiguration;
import com.harudle.common.error.ProblemDetailFactory;
import com.harudle.common.error.TraceIdConfiguration;
import com.harudle.common.security.CsrfConfiguration;
import com.harudle.common.security.SecurityConfig;
import com.harudle.feed.configuration.FeedConfiguration;
import com.harudle.feed.service.FeedPreviewService;
import com.harudle.feed.service.dto.FeedPreviewMetadata;
import com.harudle.feed.service.exception.FeedNotFoundException;
import com.harudle.generation.diary.service.port.ImageStorageException;
import com.harudle.generation.diary.service.port.dto.ReferenceImage;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(FeedPreviewController.class)
@Import({FeedConfiguration.class, FeedPreviewResponseHeadersFilter.class, SecurityConfig.class,
        CsrfConfiguration.class, ApiExceptionLoggerTestConfiguration.class, ProblemDetailFactory.class,
        TraceIdConfiguration.class, TimeConfiguration.class})
@TestPropertySource(properties = "harudle.feed.public-base-url=https://harudle.example/feeds")
class FeedPreviewControllerTest {

    private static final UUID FEED = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
    private static final String PAGE = "/api/v1/public/feeds/" + FEED;
    private static final byte[] JPEG = {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xd9};

    @Autowired private MockMvc mvc;
    @MockitoBean private FeedPreviewService previews;
    @MockitoBean private JwtDecoder jwtDecoder;
    @MockitoBean private UserRepository users;

    @Test
    void anonymousCrawlerGetsInitialHtmlWithStableUrlsAndNoLoginOrSignedUrl() throws Exception {
        when(previews.metadata(FEED)).thenReturn(new FeedPreviewMetadata("네오님의 네컷만화 | 하루들", "일상의 네컷만화"));
        var response = mvc.perform(get(PAGE).accept(MediaType.TEXT_HTML)
                        .header("Host", "attacker.example").header("X-Forwarded-Host", "attacker.example"))
                .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-Robots-Tag", "noindex"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Security-Policy", FeedPreviewHtml.contentSecurityPolicy()))
                .andReturn().getResponse();
        assertThat(response.getContentAsString(StandardCharsets.UTF_8))
                .contains("네오님의 네컷만화", "https://harudle.example" + PAGE + "/image")
                .contains("https://harudle.example/feeds/" + FEED)
                .doesNotContain("attacker.example", "X-Amz-", "sourceText");
        verify(previews).metadata(FEED);
    }

    @Test
    void imageIsServedAsJpegBytesWithoutRedirectOrExpirationQuery() throws Exception {
        when(previews.image(FEED)).thenReturn(new ReferenceImage(new ByteArrayResource(JPEG), MediaType.IMAGE_JPEG));
        mvc.perform(get(PAGE + "/image").accept(MediaType.IMAGE_JPEG))
                .andExpect(status().isOk()).andExpect(content().contentType(MediaType.IMAGE_JPEG))
                .andExpect(content().bytes(JPEG)).andExpect(header().doesNotExist("Location"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
        verify(previews).image(FEED);
    }

    @Test
    void supportsHeadForCrawlersWithoutReturningImageBody() throws Exception {
        when(previews.image(FEED)).thenReturn(new ReferenceImage(new ByteArrayResource(JPEG), MediaType.IMAGE_JPEG));
        mvc.perform(head(PAGE + "/image"))
                .andExpect(status().isOk()).andExpect(content().contentType(MediaType.IMAGE_JPEG))
                .andExpect(content().bytes(new byte[0])).andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    void hiddenOrDeletedPageAndImageReturn404ThatCannotBeCached() throws Exception {
        when(previews.metadata(FEED)).thenThrow(new FeedNotFoundException());
        when(previews.image(FEED)).thenThrow(new FeedNotFoundException());
        for (String path : new String[]{PAGE, PAGE + "/image"}) {
            mvc.perform(get(path)).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("FEED_NOT_FOUND"))
                    .andExpect(header().string("Cache-Control", "no-store"));
        }
    }

    @Test
    void imageStorageFailureIsRetryableAndNotCached() throws Exception {
        when(previews.image(FEED)).thenThrow(new ImageStorageException("공유 미리보기 이미지를 읽을 수 없습니다."));
        mvc.perform(get(PAGE + "/image")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("IMAGE_STORAGE_ERROR"))
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    void invalidFeedIdIsRejectedAndCannotBeNegativelyCached() throws Exception {
        mvc.perform(get("/api/v1/public/feeds/not-a-uuid/image")).andExpect(status().isBadRequest())
                .andExpect(header().string("Cache-Control", "no-store"));
    }
}
