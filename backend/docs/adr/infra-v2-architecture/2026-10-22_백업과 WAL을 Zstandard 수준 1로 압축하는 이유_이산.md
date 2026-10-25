# 2026-10-22 백업과 WAL을 Zstandard 수준 1로 압축하는 이유

- 작성일: 2026-10-22
- 작성자: 이산
- 상태: 채택, 서버 적용 및 부하 검증 전
- 결정 대상: Dev와 Prod의 pgBackRest 백업 및 WAL 압축 설정

## 배경

Prod는 최근 30일 복구에 필요한 물리 백업과 WAL을 S3에 보관한다.
반복 저장을 줄이기 위해 일간 백업은 증분 방식으로 정했다.
저장량을 더 줄이되, t4g.small에서 실행하는 DB 처리에 부담을 적게 주도록 압축 방식을 정한다.

## 결정 요인

- 압축에 드는 CPU 사용과 S3 저장량 사이에서 균형을 잡는다.
- gzip을 선택하려면 t4g.small의 DB 처리에 부담이 적다는 근거가 있어야 한다.
- Dev와 Prod에 같은 설정을 적용해 부하 테스트 조건을 맞춘다.
- 측정 전 압축률과 t4g.small의 CPU 사용량을 보장하지 않는다.

## 고려한 대안

### 대안 1: gzip 수준 6

장점은 pgBackRest 기본값이며 널리 사용하는 형식이라는 점이다.
단점은 아래 DB 백업 실험에서 Zstandard 수준 1보다 CPU 시간을 더 사용했다는 점이다.
t4g.small에서 DB 처리에 영향이 적다는 직접 측정 자료는 찾지 못했다.

### 대안 2: LZ4 수준 1

장점은 압축과 해제 속도를 우선할 수 있다는 점이다.
단점은 공개 비교에서 Zstandard 수준 1보다 저장 크기가 커지는 경향이 있다는 점이다.

### 대안 3: Zstandard 수준 1

장점은 낮은 압축 수준에서 속도와 저장량의 균형을 기대할 수 있다는 점이다.
단점은 실제 DB와 WAL의 압축률을 측정해야 하고, 설치한 pgBackRest가 Zstandard를 지원하는지 확인해야 한다는 점이다.

## 결정

대안 3을 채택한다. Dev와 Prod에서 물리 백업과 WAL을 모두 아래 설정으로 압축한다.

```ini
[global]
compress-type=zst
compress-level=1
```

개별 명령 설정이 전역 설정을 덮어쓰지 않도록 최종 설정을 확인한다.
PostgreSQL의 `wal_compression`과는 별개다. 이번 결정은 S3에 보관하는 파일의 압축 방식이다.
gzip은 대안으로 남기지만 자동 전환하지 않는다. 측정 결과나 설치 제약이 있으면 다시 검토한다.

### 선정 근거

[pgBackRest 공식 운영 가이드](https://pgbackrest.org/user-guide.html#quickstart/performance-tuning)는 gzip이 기본값이지만, 비슷한 압축 결과와 더 빠른 처리 속도를 이유로 Zstandard를 권장한다.
공식 권장은 압축 방식에 대한 것이며, 수준 1은 CPU 부담을 고려한 우리 선택이다.

[Percona의 PostgreSQL 백업 실험](https://www.percona.com/blog/pgbackrest-compression-how-much-cpu-is-a-smaller-backup-worth/)에서는 다음 결과를 공개했다.

| 설정 | 원본 1GB당 CPU 사용 시간 | 저장량 감소 |
| --- | --- | --- |
| Zstandard 수준 1 | 9.1 CPU초 | 59.9% |
| Zstandard 수준 3 | 13.8 CPU초 | 64.7% |
| gzip 수준 6 | 54.5 CPU초 | 61.6% |

CPU초는 사용한 CPU 시간을 모두 더한 값이며, 작업 완료까지 걸린 실제 시간과 다르다.
이 실험에서는 gzip 수준 6이 저장량을 1.7%포인트 더 줄였지만 CPU 시간은 약 6배 사용했다.
Zstandard 수준 3도 수준 1보다 용량을 더 줄였지만 CPU 시간을 더 사용했다.
우리는 압축에 쓰는 CPU를 줄이는 것을 우선해 수준 1을 선택한다.
LZ4는 CPU 부담을 더 줄여야 할 때의 재검토안으로 남긴다.
PostgreSQL 18.4와 pgBackRest 2.59.0, 약 4GiB DB를 사용한 단일 호스트의 메모리에 올라간 데이터와 작업자 8개 기준이다.
t4g.small의 실제 CPU 사용률이나 WAL 압축률로 환산하지 않는다.

t4g.small에서 gzip의 DB 영향까지 확인한 직접 실험 자료는 이번 조사에서 찾지 못했다.
[AWS Graviton 가이드](https://aws.github.io/graviton/)는 배포판의 일반 zlib에 ARM 최적화가 없을 수 있다고 설명한다.
따라서 ARM 서버라는 이유만으로 gzip 부담이 작다고 판단하지 않는다.
gzip 수준 1은 수준 6과 비용이 다를 수 있지만, t4g.small에서 부담이 작다는 직접 근거는 확인하지 못했다.

## 긍정적 결과

- 증분 백업에 압축을 함께 적용해 S3 저장량과 전송량을 줄일 수 있다.
- Dev와 Prod에서 같은 압축 작업의 성능 영향을 확인할 수 있다.

## 부정적 결과와 트레이드오프

- 압축에 CPU와 메모리를 사용하므로 DB 부하가 없어지는 것은 아니다.
- 높은 수준보다 저장량이 클 수 있다. 정확한 차이는 실제 데이터에 따라 달라진다.
- 일반 시험 데이터의 압축률을 하루들 백업이나 WAL의 예상 용량으로 확정하지 않는다.
- 공개 실험은 선택의 근거로 사용한다. 실제 t4g.small에서 영향이 없다는 검증 결과로 사용하지 않는다.

## 후속 결정과 재검토 조건

- 압축 및 전송 작업 수와 CPU 우선순위를 정한다.
- CPU 부담으로 DB 응답이 나빠지면 작업 설정과 LZ4 대안을 검토한다.
- 설치한 ARM64 이미지에서 Zstandard가 지원되지 않으면 패키지 구성 또는 압축 방식을 재검토한다.

## 구현과 검증

압축 방식만 결정했다. 서버 설정은 아직 적용하지 않았다.
Dev에서 전체 및 증분 백업, WAL 전송 각각의 압축 전후 크기와 CPU, 메모리 사용량을 기록한다.
부하 테스트 중 DB 응답 시간과 WAL 보관 지연을 함께 확인한다.
압축된 S3 백업과 WAL로 복원해 데이터와 앱 동작을 검증한다.

## 관련 문서

- [인프라 v2 설계 결정 목록](README.md)
- [일간 물리 백업 방식](<2026-10-22_Dev와 Prod의 일간 물리 백업을 증분 방식으로 만드는 이유_이산.md>)
- [WAL 파일 전환 간격](<2026-10-22_Dev와 Prod의 WAL 파일 전환 간격을 1분으로 맞추는 이유_이산.md>)
- [pgBackRest 압축 설정](https://pgbackrest.org/configuration.html#section-general/option-compress-type)
- [pgBackRest 성능 조정 안내](https://pgbackrest.org/user-guide.html#quickstart/performance-tuning)
- [Percona의 pgBackRest 압축 실험](https://www.percona.com/blog/pgbackrest-compression-how-much-cpu-is-a-smaller-backup-worth/)
- [AWS Graviton 압축 라이브러리 안내](https://aws.github.io/graviton/)
- [Zstandard 공개 실험](https://github.com/facebook/zstd#benchmarks)
