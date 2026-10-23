package com.harudle.profile.service;

import com.harudle.profile.query.PublicProfileSnapshot;
import com.harudle.profile.repository.PublicProfileQueryRepository;
import com.harudle.profile.service.port.PublicProfileReader;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class DefaultPublicProfileReader implements PublicProfileReader {

    private final PublicProfileQueryRepository repository;
    private final ProfileImageUrlResolver imageUrlResolver;

    public DefaultPublicProfileReader(
            PublicProfileQueryRepository repository,
            ProfileImageUrlResolver imageUrlResolver
    ) {
        this.repository = repository;
        this.imageUrlResolver = imageUrlResolver;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, Profile> readAll(Set<UUID> userIds) {
        Objects.requireNonNull(userIds, "userIds는 필수입니다.");
        if (userIds.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("회원 ID는 null일 수 없습니다.");
        }
        if (userIds.isEmpty()) {
            return Map.of();
        }

        return repository.findAllByUserIds(userIds).stream()
                .collect(Collectors.toUnmodifiableMap(PublicProfileSnapshot::id, this::toProfile));
    }

    private Profile toProfile(PublicProfileSnapshot snapshot) {
        return new Profile(
                snapshot.id(),
                snapshot.nickname(),
                imageUrlResolver.resolve(snapshot.profileImageCode())
        );
    }
}
