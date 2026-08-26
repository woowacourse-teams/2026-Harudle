# Querydsl 대신 EntityManager를 사용하는 이유

- 상태: 채택
- 작성자: 이산

## 결정

- 관리자 조회는 `EntityManager`를 통해 JPQL을 실행한다.
- 생성 사용량의 증가·복구·초기화는 `EntityManager`를 통해 네이티브 SQL을 실행한다.
- `EntityManager`는 `infrastructure` 패키지 안에서만 사용하고, 서비스와 Repository 인터페이스에는 노출하지 않는다.
- 현재는 Querydsl을 도입하지 않고, 조회 조건이 더 복잡해질 때 도입을 재검토한다.

## 이유

관리자 조회는 동적 조건, 조인, DTO 프로젝션, 페이징과 카운트 쿼리가 필요하다. 현재 범위에서는 Querydsl 설정과 생성 코드 관리 비용보다 `EntityManager`로 JPQL을 명시적으로 작성하는 편이 단순하다.

생성 사용량 변경은 `ON CONFLICT`, 조건부 `UPDATE`, `RETURNING` 같은 PostgreSQL 원자 연산을 사용하므로 JPQL만으로 표현하기 어렵다. Querydsl JPA를 도입하더라도 이 네이티브 SQL 문제는 해결되지 않는다.

## 결과

`EntityManager` 사용은 인프라 구현체로 제한하고, 현재 필요한 쿼리를 한 가지 JPA 접근 방식으로 관리한다. 관리자 조회 조건이 늘어나거나 동적 쿼리의 가독성이 떨어지면 Querydsl 도입을 검토한다.
