package com.harudle.feed.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.harudle.category.service.port.CategoryReader;
import com.harudle.feed.service.exception.FeedIntegrationUnavailableException;
import com.harudle.feed.service.port.FeedLikeReader;
import com.harudle.profile.service.port.PublicProfileReader;
import com.harudle.push.service.port.FeedPushOutbox;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

class FeedCollaboratorsTest {

    @Test
    void allowsConstructionBeforeOtherDomainsAreIntegratedButRejectsTheirUse() {
        var beans = new DefaultListableBeanFactory();
        FeedCollaborators collaborators = collaborators(beans);
        assertThatThrownBy(collaborators::categories).isInstanceOf(FeedIntegrationUnavailableException.class);
        assertThatThrownBy(collaborators::profiles).isInstanceOf(FeedIntegrationUnavailableException.class);
        assertThatThrownBy(collaborators::likes).isInstanceOf(FeedIntegrationUnavailableException.class);
        assertThatThrownBy(collaborators::pushOutbox).isInstanceOf(FeedIntegrationUnavailableException.class);
    }

    @Test
    void connectsRegisteredImplementationUsingExistingPort() {
        var beans = new DefaultListableBeanFactory();
        CategoryReader reader = mock(CategoryReader.class);
        beans.registerSingleton("categories", reader);
        assertThat(collaborators(beans).categories()).isSameAs(reader);
    }

    private FeedCollaborators collaborators(DefaultListableBeanFactory beans) {
        return new FeedCollaborators(beans.getBeanProvider(CategoryReader.class),
                beans.getBeanProvider(PublicProfileReader.class), beans.getBeanProvider(FeedLikeReader.class),
                beans.getBeanProvider(FeedPushOutbox.class));
    }
}
