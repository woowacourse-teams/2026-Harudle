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

## 2026-11-04 dev 키 준비와 백업 검증 기록

이 기록은 해당 시점의 실행 결과다. 이후 환경의 현재 상태나 팀 공동 복구 완료를 뜻하지 않는다.
최신 인프라 문서는 2026-11-05에 앱과 DB 인스턴스를 다시 교체했다고 기록한다. 아래 개인 백업
검증만으로 새 인스턴스의 키와 canary 반영을 확인한 것으로 간주하지 않으며, 배포 직전에 재검증한다.

| 항목 | 확인한 결과 |
| --- | --- |
| 환경과 검토 코드 | dev, `0079e4a225c4d55fef8ff2bcefa2c18e54b24e98` |
| 백업 세대와 키 ID | `dev/20261104T110032Z`, `primary` |
| 보관소 성격 | 서버 밖 개인 장비의 암호화 백업. 팀 공동 보관소 식별자와 갱신 담당자는 지정 필요 |
| 백업 파일 | `key-material.rsa-oaep`, 같은 세대의 `diary-key-checks.json`, 비밀값 없는 `metadata.json` |
| 보호 방법 | 키 자료는 RSA-OAEP SHA-256으로 암호화. 디렉터리 `700`, 파일 `600`. 검증 파일 자체의 별도 파일 암호화는 아님 |
| 키 검증 | 후보 코드로 초기 검증 파일 생성 후 실제 컨테이너 UID `10001`의 `verify` 통과 |
| 서버 밖 복원 확인 | 암호화 백업의 키를 복원해 같은 세대 검증 파일로 `verify` 성공 |
| 작업 후 서비스 | 기존 백엔드 healthy, 배포 에이전트 정상, dev health와 공개 피드 HTTP 200 |
| 당시 DB | V1부터 V43까지 성공. `source_text_version` 열 없음. V44와 기존 데이터 전환은 실행하지 않음 |
| 실제 v1 데이터의 `verify-data`와 앱 조회 | 미수행. 기존 서비스의 health/공개 피드 성공은 이 검증을 대신하지 않음 |
| 팀원 두 명의 독립 접근과 복원 | 미확인. 개인 백업 복원 결과를 팀 공동 접근 완료로 기록하지 않음 |
| prod 백업과 복원 | 이번 dev 준비 작업에 포함되지 않음 |

원문 키, 개인 복구 키, 암호문과 실제 일기 원문은 기록하지 않는다. 개인 장비 백업을 유일한
운영 복구 보관소로 확정하지 않는다. 팀 보관소와 권한이 확정되면 보관소의 비밀값 없는 식별자,
갱신 담당자와 대체 담당자, 각자의 접근 및 복원 확인 결과를 팀 Notion에 기록한다.

팀 기록에는 백업 세대, 검증한 코드, `verify` 결과, 실제 v1 행의 `verify-data` checked/failed,
본인 상세와 성공한 동일 요청 재조회 결과를 각각 분리해 남긴다. v1 행이 없어 checked가 0인 검사는
실제 v1 데이터 복원 성공으로 표시하지 않는다. 이 조건을 충족하기 전에는 prod writer의 실제
복원 승인 완료로 간주하지 않는다.

## 번호와 적용 순서

| 순서 | PR | 마이그레이션 |
| --- | --- | --- |
| 1 | [사용자 차단 #462](https://github.com/woowacourse-teams/2026-Harudle/pull/462) | `V36__create_user_blocks.sql` |
| 2 | [약관 동의 #476](https://github.com/woowacourse-teams/2026-Harudle/pull/476) | `V37__create_user_terms_acceptances.sql` |
| 3 | 회원탈퇴와 Apple 로그인 등 최신 백엔드 | `V38`부터 `V43`까지 |
| 4 | [원문 암호화 #442](https://github.com/woowacourse-teams/2026-Harudle/pull/442) | `V44__encrypt_diary_source_text.sql` |
| 5 | [기존 원문 전환 #450](https://github.com/woowacourse-teams/2026-Harudle/pull/450) | 별도 수동 전환·검증·최종화 |

기존 V1~43과 SQL 본문은 바꾸지 않고 암호화 파일의 번호만 V44로 재배정했다. 최신 브랜치의 V38은 회원탈퇴가 사용한다. 저장 형식의 v0/v1은 그대로다.
실제 배포본에 위 SQL을 함께 포함해 순서대로 적용한다. V37을 먼저 단독 배포한 뒤 나중에 V36을
추가하는 식으로 순서를 뒤집지 않는다. 이미 적용된 번호·체크섬을 수정하거나 `repair`로 우회하지 않는다.

초기화는 배포본의 실제 SQL 버전 집합과 DB 이력을 대조한다. 예를 들어 차단·약관 이력이 있는
환경에는 그 SQL도 포함한 배포본을 사용한다. 후속 번호를 암호화 적용으로 오인하지 않으며,
암호화 V44 이력과 원문 열·저장 버전이 맞지 않으면 초기화를 거절한다.

## 함께 병합할 설정

- `application.yml`: 하나의 `harudle` 아래에 암호화 `diary`, 차단의 `database.interaction-lock-timeout`,
  약관의 `legal.require-terms-acceptance` 설정을 함께 보존한다.
- 소셜 로그인 #466과 `deploy/compose.prod.yaml`을 합칠 때는 암호화 검증 자료,
  `/etc/harudle/secrets/apple`의 Apple 키 디렉터리, 기존 GCP WIF 파일의 읽기 전용 마운트를 함께 유지한다.
- #466의 기존 `User.java` 충돌은 해당 PR의 최신 기준 브랜치 반영 과정에서 별도로 해결한다.

## 운영 배포 전 남은 확인

운영 키·검증 자료 백업의 실제 격리 복원 연습과 운영 규모의 V44 잠금 시간 측정은 이번 코드
검증으로 완료한 것이 아니다. [암호화 운영 안내](diary-source-text-encryption.md)의 reader 보존,
writer 승인과 복구 조건을 충족한 뒤 배포한다. 실제 서비스에서 전환·최종화 명령을 자동 실행하지 않는다.
