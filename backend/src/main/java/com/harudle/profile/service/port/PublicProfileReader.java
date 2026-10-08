package com.harudle.profile.service.port;

import java.net.URI;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 피드, 댓글, 알림에 표시할 작성자 정보를 조회한다. */
public interface PublicProfileReader {

    /**
     * 여러 회원의 프로필을 한 번에 조회하고, 회원 ID를 키로 반환한다.
     * 닉네임이 없으면 기존 이름을 표시하고, 프로필 이미지는 배정된 이미지를 사용한다.
     * 조회할 수 없는 회원은 결과에서 뺀다. 빈 Set을 받거나 결과가 없으면 빈 Map을 반환한다.
     * 입력과 반환 Map은 null을 허용하지 않는다.
     */
    Map<UUID, Profile> readAll(Set<UUID> userIds);

    record Profile(UUID id, String nickname, URI profileImageUrl) {}
}
