# 2026-08-11 Generation ID 기반 이미지 Object Key 사용

- 작성일: 2026-08-11
- 작성자: 캐모
- 결정 대상: Generation 이미지 저장 포트와 S3 Object Key 정책

## 배경

Generation 서비스는 AI가 생성한 이미지를 `ImageStorage` 포트를 통해 S3에 저장한다.
현재 저장 계약은 다음과 같다.

```java
String store(GeneratedImage generatedImage);
```

이 계약에서는 저장소 구현체가 이미지와 연결된 생성 작업을 알 수 없다. 따라서 S3 구현체는 랜덤 UUID나
이미지 내용의 해시처럼 생성 작업과 직접 연결되지 않은 값을 사용해 Object Key를 만들어야 한다.

```text
generated/comics/{randomUuid}.png
```

이 방식은 저장소가 독립적으로 Key를 생성할 수 있지만 다음 문제가 있다.

- DB의 `ComicGeneration`과 S3 객체의 관계를 Object Key만 보고 파악하기 어렵다.
- 동일한 생성 작업을 재실행할 때마다 서로 다른 객체가 만들어질 수 있다.
- 장애 조사 시 어떤 생성 작업이 만든 객체인지 추가 조회가 필요하다.
- S3 업로드 후 DB 저장에 실패한 고아 객체를 식별하고 정리하기 어렵다.

현재 생성 흐름에서는 S3 저장 전에 `ComicGeneration`이 `PROCESSING` 상태로 저장되며 UUID 형식의
`generationId`가 이미 존재한다. 따라서 이 식별자를 이미지 저장 컨텍스트로 사용할 수 있겠다고 생각했다.

## 결정 요인

- DB 생성 작업과 S3 객체를 쉽게 연결할 수 있어야 한다.
- 동일한 생성 작업에 대해 안정적인 Object Key를 만들 수 있어야 한다.
- 장애 조사와 고아 객체 정리가 쉬워야 한다.
- 서비스 계층에 bucket 같은 S3 세부 정책이 노출되지 않아야 한다.
- 실제 S3 어댑터 구현 전에 작은 비용으로 계약을 변경할 수 있어야 한다.

## 고려한 대안

### 대안 1: 저장소가 랜덤 UUID로 Object Key 생성

```java
String store(GeneratedImage generatedImage);
```

```text
generated/comics/{randomUuid}.png
```

장점은 다음과 같다.

- 저장소 구현체가 Object Key 생성 책임을 완전히 가진다.
- 인터페이스가 특정 비즈니스 도메인에 종속되지 않는다.
- 매번 새로운 Key를 사용하므로 의도하지 않은 덮어쓰기 가능성이 작다.

단점은 다음과 같다.

- 생성 작업과 S3 객체의 연관 관계가 Object Key에 드러나지 않는다.
- 재실행할 때마다 새로운 객체가 생길 수 있다.
- 고아 객체와 중복 객체를 추적하고 정리하기 어렵다.

### 대안 2: 서비스가 전체 Object Key를 생성해 저장소에 전달

```java
String store(String imageObjectKey, GeneratedImage generatedImage);
```

장점은 다음과 같다.

- 서비스가 생성 작업에 맞는 결정적인 Key를 직접 지정할 수 있다.
- 저장 결과의 위치를 호출 전에 알 수 있다.

단점은 다음과 같다.

- 서비스가 `generated/comics`와 같은 S3 prefix를 알게 된다.
- 파일명과 MIME type별 확장자 정책이 서비스 계층으로 유출된다.
- 저장소 구조가 변경되면 서비스 코드도 함께 변경될 수 있다.

### 대안 3: 서비스는 Generation ID만 전달하고 저장소가 Object Key 생성

```java
String store(UUID generationId, GeneratedImage generatedImage);
```

장점은 다음과 같다.

- DB 생성 작업과 S3 객체를 직접 연결할 수 있다.
- 같은 생성 작업은 같은 Object Key를 사용할 수 있다.
- bucket, prefix, 파일명과 확장자 정책은 저장소 구현체에 유지된다.
- 장애 조사와 고아 객체 정리에 필요한 식별 정보를 확보할 수 있다.

단점은 다음과 같다.

- `ImageStorage`가 범용 파일 저장소가 아닌 Generation 전용 포트가 된다.
- 동일 Key를 다시 저장하면 기존 이미지가 덮어써질 수 있다.
- 동일 URL을 캐시하는 환경에서는 캐시 무효화 정책이 필요할 수 있다.

## 결정

대안 3을 선택한다.

`ImageStorage`의 저장 계약에 `generationId`를 추가한다.   

이유는 다음과 같다.
- `ImageStorage`를 사용하는 곳은 도메인 상 Generation밖에 없을 것이다.
- 동일 Key를 다시 저장할 일은 거의 없을 것이다.
- 동일 URL을 캐시하는 환경도 거의 없을 것이다.   
- 단점들이 현재 상황에서는 거의 발생하지 않을 것이라 대안 3을 선택하게 되었다.

```java
public interface ImageStorage {

    ReferenceImage load(String imageObjectKey);

    String store(UUID generationId, GeneratedImage generatedImage);
}
```

`GenerateComicService`는 이미 생성된 작업 ID를 이미지와 함께 전달한다.

```java
String imageObjectKey = imageStorage.store(
        generation.getId(),
        generatedImage
);
```

S3 구현체가 최종 Object Key를 조립한다. 기본 형식은 다음과 같다.

```text
generated/comics/{generationId}/comic.{extension}
```

확장자는 `GeneratedImage`의 `MediaType`으로 결정한다. 이미지 생성 계약이 PNG로 고정되면 다음 경로를 사용한다.

```text
generated/comics/{generationId}/comic.png
```

서비스는 `generationId`만 제공하며 bucket, prefix, 파일명, 확장자와 최종 Object Key 정책을 알지 않는다.

## 긍정적 결과

- `ComicGeneration.id`만으로 관련 S3 객체 경로를 추론할 수 있다.
- 로그와 장애 조사에서 DB 작업과 S3 객체를 쉽게 연결할 수 있다.
- 동일한 Generation ID를 재사용하는 재실행 정책이 추가되면 같은 경로를 재사용할 수 있다.
- 랜덤 Key를 반복 생성해 논리적으로 같은 이미지가 여러 개 남는 문제를 줄일 수 있다.
- 고아 객체 탐색과 정리 작업에서 Object Key의 Generation ID를 활용할 수 있다.
- S3 경로 정책은 계속 S3 어댑터가 소유한다.

## 부정적 결과와 트레이드오프

- `ImageStorage`는 Generation 컨텍스트에 종속되므로 다른 도메인의 범용 저장 포트로 재사용하기 어렵다.
- 재실행 시 같은 Key에 업로드하면 기존 객체가 덮어써진다.
- S3 Versioning이 활성화된 경우 같은 Key를 사용해도 이전 버전의 저장 비용이 남을 수 있다.
- CDN이나 브라우저가 Object URL을 캐시한다면 동일 Key 덮어쓰기에 대한 캐시 무효화가 필요하다.
- 고정된 `.png` 파일명을 사용하려면 이미지 생성 결과가 PNG라는 계약이 필요하다.
- 현재 서비스는 실패한 동일 작업을 자동으로 재시도하지 않으므로 경로 재사용의 일부 이점은 향후 재시도 정책에 해당한다.

이 포트는 Generation 패키지 내부에서만 사용하는 전용 출력 포트다. 따라서 범용성 감소보다 추적성, 장애 대응과
경로 안정성이 더 중요하다고 판단한다.

