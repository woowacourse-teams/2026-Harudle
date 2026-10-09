package com.harudle.diary.service.port;

import java.util.UUID;

/** 일기를 피드에 게시할 수 있는지 확인한다. 입력값은 null을 허용하지 않는다. */
public interface DiaryPublicationReader {

    /**
     * 본인의 삭제되지 않은 일기인지, 만화 생성이 성공했는지 확인한다. 조건에 맞지 않으면 예외를 던진다.
     * 일기 ID, 작성자 ID, 이미지 키만 반환하며 비공개 일기 내용은 포함하지 않는다.
     * 게시와 같은 트랜잭션에서 일기를 잠그고, 트랜잭션이 끝날 때 잠금을 해제한다.
     * generation은 잠그지 않고 조회만 한다. 기존 생성 처리와 잠금 순서가 꼬이는 것을 막기 위해서다.
     */
    PublishableDiary lockAndRead(UUID actorId, UUID diaryId);

    record PublishableDiary(UUID diaryId, UUID authorId, String imageObjectKey) {}
}
