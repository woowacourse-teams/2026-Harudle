# 암호화 통합·배포 확인

## 확인한 운영 이력

2026-10-26 KST에 기존 Session Manager 접속으로 Flyway 메타데이터만 조회했다.
조회 세션은 읽기 전용, SQL 실행 제한 5초, 잠금 대기 제한 1초를 사용했다.

| 환경 | 이력 수·마지막 버전 | 마지막 SQL | 체크섬 | 성공 여부 |
| --- | --- | --- | --- | --- |
| dev | 34개 · V34 | `V34__scope_profile_image_cleanup_to_user.sql` | `905814578` | 전체 성공 |
| prod | 23개 · V23 | `V23__add_storyboard_provider_info.sql` | `-435042481` | 전체 성공 |

두 환경 모두 V36 이상 이력이 없었다. 이 조회에서 DB 데이터·스키마·Flyway 이력을 변경하지 않았다.
이 기록은 위 시점의 상태이며, 실제 배포 직전에 다시 확인한다.

## 번호와 적용 순서

| 순서 | PR | 마이그레이션 |
| --- | --- | --- |
| 1 | [사용자 차단 #462](https://github.com/woowacourse-teams/2026-Harudle/pull/462) | `V36__create_user_blocks.sql` |
| 2 | [약관 동의 #476](https://github.com/woowacourse-teams/2026-Harudle/pull/476) | `V37__create_user_terms_acceptances.sql` |
| 3 | [원문 암호화 #442](https://github.com/woowacourse-teams/2026-Harudle/pull/442) | `V38__encrypt_diary_source_text.sql` |
| 4 | [기존 원문 전환 #450](https://github.com/woowacourse-teams/2026-Harudle/pull/450) | 별도 수동 전환·검증·최종화 |

기존 V1~35와 SQL 본문은 바꾸지 않고 미적용 파일의 번호만 재배정했다. 저장 형식의 v0/v1은 그대로다.
실제 배포본에 위 SQL을 함께 포함해 순서대로 적용한다. V37을 먼저 단독 배포한 뒤 나중에 V36을
추가하는 식으로 순서를 뒤집지 않는다. 이미 적용된 번호·체크섬을 수정하거나 `repair`로 우회하지 않는다.

초기화는 배포본의 실제 SQL 버전 집합과 DB 이력을 대조한다. 예를 들어 차단·약관 이력이 있는
환경에는 그 SQL도 포함한 배포본을 사용한다. 후속 번호를 암호화 적용으로 오인하지 않으며,
암호화 V38 이력과 원문 열·저장 버전이 맞지 않으면 초기화를 거절한다.

## 함께 병합할 설정

- `application.yml`: 하나의 `harudle` 아래에 암호화 `diary`, 차단의 `database.interaction-lock-timeout`,
  약관의 `legal.require-terms-acceptance` 설정을 함께 보존한다.
- 소셜 로그인 #466과 `deploy/compose.prod.yaml`을 합칠 때는 암호화 검증 자료,
  `/etc/harudle/secrets/apple`의 Apple 키 디렉터리, 기존 GCP WIF 파일의 읽기 전용 마운트를 함께 유지한다.
- #466의 기존 `User.java` 충돌은 해당 PR의 최신 기준 브랜치 반영 과정에서 별도로 해결한다.

## 운영 배포 전 남은 확인

운영 키·검증 자료 백업의 실제 격리 복원 연습과 운영 규모의 V38 잠금 시간 측정은 이번 코드
검증으로 완료한 것이 아니다. [암호화 운영 안내](diary-source-text-encryption.md)의 reader 보존,
writer 승인과 복구 조건을 충족한 뒤 배포한다. 실제 서비스에서 전환·최종화 명령을 자동 실행하지 않는다.
