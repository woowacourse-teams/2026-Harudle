# 피드 사용자 프로필 스키마 적용과 이미지 백필

## 적용 전 확인

V14는 현재 PR에서 새로 추가한 마이그레이션이다. 배포 전 각 공유 DB의
`flyway_schema_history`에서 V14~V18 적용 여부를 확인한다. 이미 기존 V14가 적용된
DB에서는 수정한 파일로 재배포하면 체크섬 검증이 실패한다. 그 경우 임의로 repair하거나
이미 적용된 기록을 지우지 않고 해당 환경의 변경 이력을 먼저 확인한다.

```sql
SELECT version, description, success
FROM flyway_schema_history
WHERE version IN ('14', '15', '16', '17', '18')
ORDER BY installed_rank;
```

## 실행 단계

- V14: nullable 컬럼 추가와 CHECK `NOT VALID`만 실행하고 커밋한다.
  `lock_timeout = '2s'`로 DDL 잠금 획득을 오래 기다리지 않는다. 잠금 획득 실패 시
  기존 장기 트랜잭션을 확인하고 다시 배포한다. 이 설정은 실행 시간의 상한이 아니다.
- V15·V16: 새 피드·알림 테이블을 만든다.
- V17: 닉네임 부분 UNIQUE 인덱스를 `CREATE UNIQUE INDEX CONCURRENTLY`로 만든다.
  같은 이름의 `.sql.conf`에서 `executeInTransaction=false`를 지정한다.
- V18: V14의 CHECK를 검증한다. 테이블은 검사하지만 일반 조회·쓰기를 막는
  `ACCESS EXCLUSIVE` 잠금을 계속 보유하지 않는다.

`application.yml`은 `spring.flyway.group=false`와
`spring.flyway.postgresql.transactional-lock=false`를 사용한다. 별도 Flyway CLI/API로
실행하는 경우에도 그룹 실행을 끄고 `flyway.postgresql.transactional.lock=false`를 적용한다.
CLI/API에서는 Spring 설정을 자동으로 읽는다고 가정하지 않는다.

닉네임 쓰기 기능은 V17의 유효한 인덱스가 생성된 후 활성화한다. 프로필 컬럼은 기존
애플리케이션과의 공존을 위해 nullable로 유지한다. 프로필 기능 구현 시 신규 회원의
이미지를 배정하고, 백필 완료 전 기존 회원의 NULL 이미지는
`1 + UUID 마지막 바이트 % 5`로 계산한 기본 이미지를 표시한다. 체험 사용자에는 적용하지 않는다.
현재 PR은 프로필 API와 화면을 구현하지 않는다.

## 기존 회원 이미지 백필

백필 SQL은 `db/maintenance`에 있어 애플리케이션 시작 시 Flyway가 실행하지 않는다.
PostgreSQL 접속 환경(`PGHOST`, `PGPORT`, `PGDATABASE`, `PGUSER`, `PGPASSFILE` 등)을 설정하고,
저장소 루트에서 아래 명령을 실행한다. 비밀번호를 명령 인자에 직접 넣지 않는다.

```sh
PGOPTIONS='-c lock_timeout=2s' psql -X -v ON_ERROR_STOP=1 \
  -f backend/src/main/resources/db/maintenance/backfill_user_profile_images.sql \
  -c 'CALL pg_temp.backfill_user_profile_images(1000);'
```

- `psql -f`와 `-c`는 같은 연결에서 실행한다. 프로시저는 `pg_temp`에 정의되므로
  연결 종료 시 사라지고 영구 스키마 객체를 추가하지 않는다.
- `CALL`은 autocommit 상태의 최상위 명령으로 실행한다. `BEGIN`, `--single-transaction`,
  Flyway 또는 애플리케이션의 `@Transactional`로 감싸지 않는다.
- 배치 크기는 1~10,000 범위이며 기본값은 1,000이다. 먼저 작은 크기로 시간과 부하를
  확인한 뒤 조절한다. `statement_timeout`을 지정하면 배치 하나가 아니라 CALL 전체에 적용된다.
- UUID 순서로 대상 회원을 찾고 배치마다 UPDATE·COMMIT한다. 갱신 시에도
  이미지 NULL, 미탈퇴, 체험 사용자 제외 조건을 다시 확인한다.
- 이미 설정한 이미지는 보존한다. 중간 실패 시 앞서 커밋한 배치는 남아 있고,
  같은 명령을 다시 실행하면 아직 이미지가 NULL인 회원만 처리한다.
- 실행 중 새로 생긴 대상이나 UUID 커서 이전의 대상을 놓치지 않도록 완료 후 아래
  잔여 수를 확인하고 필요하면 재실행한다. 신규 회원 이미지는 프로필 서비스에서 배정한다.

```sql
SELECT count(*) AS remaining_members
FROM users AS member
WHERE member.profile_image_code IS NULL
  AND member.deleted_at IS NULL
  AND NOT EXISTS (
      SELECT 1 FROM guest_sessions AS guest WHERE guest.guest_user_id = member.id
  );
```

## 인덱스 생성 실패 복구

Concurrent 인덱스 생성 실패 시 INVALID 인덱스가 남을 수 있다. 이름만 보고
`IF NOT EXISTS`로 넘어가지 않고 상태와 실패 원인을 확인한다.

```sql
SELECT indexrelid::regclass AS index_name, indisvalid, indisready
FROM pg_index
WHERE indexrelid = to_regclass('public.uq_users_active_nickname');
```

INVALID 상태라면 실패 원인을 해소한 뒤 해당 인덱스를 트랜잭션 밖에서
`DROP INDEX CONCURRENTLY public.uq_users_active_nickname`로 제거하고 V17을 재시도한다.
Flyway에 실패 기록이 남았다면 동일한 migration 위치와 설정으로 repair가 필요한지도 확인한다.
유효한 인덱스가 남아 있는 경우는 실행 이력을 먼저 대조한다. 적용 완료한 V14의 체크섬을
맞추기 위한 repair와 인덱스 생성 실패 복구를 혼동하지 않는다.

## 검증

마이그레이션 테스트는 V14에서 CHECK 검증을 미루고 신규 쓰기에는 제약을 적용하는지,
V18에서 검증을 완료하는지 확인한다. V17이 기존 쓰기 트랜잭션을 기다리는 동안 별도 연결의
사용자 조회·쓰기가 가능한지도 실제 PostgreSQL에서 확인한다.
백필 테스트는 회원 배정, 기존 이미지 보존, 탈퇴·체험 사용자 제외, 잘못된 배치 크기,
중간 실패 전 배치 커밋 보존과 재실행을 검증한다.
