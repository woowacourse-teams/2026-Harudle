package com.harudle.category.service.port;

/** 카테고리 정보를 조회하고, 새 피드를 게시할 수 있는지 확인한다. */
public interface CategoryReader {

    /** 비활성 카테고리도 조회한다. 해당 ID의 카테고리가 없으면 예외를 던진다. */
    Category get(long categoryId);

    /**
     * 게시할 활성 카테고리를 반환한다. 없거나 비활성이면 예외를 던진다.
     * 게시와 같은 트랜잭션에서 호출하고, 끝날 때까지 잠가 관리자의 변경과 겹치지 않게 한다.
     */
    Category lockActiveForPublication(long categoryId);

    record Category(long id, String name, int sortOrder, boolean active) {}
}
