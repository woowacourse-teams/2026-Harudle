# 하루들 팀 컨벤션

이 문서는 하루들 팀의 브랜치, 커밋, 코드 스타일 및 테스트 작성 규칙을 정의한다.

## 브랜치 전략

### 브랜치 구조

```text
main
└── develop
    ├── develop-backend
    │   ├── feature/*
    │   └── fix/*
    └── develop-frontend
        ├── feature/*
        └── fix/*
```

- `main`: 배포 가능한 안정 버전을 관리한다.
- `develop`: 프론트엔드와 백엔드의 개발 결과를 통합한다.
- `develop-backend`: 백엔드 개발의 기준 브랜치이다.
- `develop-frontend`: 프론트엔드 개발의 기준 브랜치이다.
- `feature/*`: 새로운 기능을 개발한다.
- `fix/*`: 버그를 수정한다.

### 작업 흐름

1. 백엔드 작업 브랜치는 `develop-backend`에서 분기한다.
2. 프론트엔드 작업 브랜치는 `develop-frontend`에서 분기한다.
3. 작업을 마친 `feature/*`, `fix/*` 브랜치는 해당 영역의 개발 브랜치로 PR을 보낸다.
4. `develop-backend`, `develop-frontend`의 변경 사항을 `develop`에 통합한다.
5. 배포 가능한 변경 사항을 `develop`에서 `main`으로 통합한다.

### 브랜치 이름

브랜치 이름은 `<type>/<작업-이름>` 형식을 사용한다.

```text
feature/room-reservation
fix/reservation-time-validation
```

## 커밋 컨벤션

[Conventional Commits 1.0.0-beta.4](https://www.conventionalcommits.org/en/v1.0.0-beta.4/)와 Angular 컨벤션을 따른다.

### 커밋 메시지 형식

```text
<type>(<scope>): <한글 설명>

[선택 사항: 변경 이유와 상세 내용]

[선택 사항: 이슈 또는 호환성 관련 정보]
```

- 커밋 메시지의 설명은 한글로 작성한다.
- `scope`는 `BE`, `FE`, `AI` 중 하나를 사용한다.
- 제목만으로 충분히 설명되지 않는다면 본문에 변경 이유와 상세 내용을 작성한다.
- 본문은 제목에서 한 줄을 비우고 작성한다.

### 커밋 타입

| 타입 | 설명 |
| --- | --- |
| `feat` | 새로운 기능 추가 |
| `fix` | 버그 수정 |
| `docs` | 문서 변경 |
| `style` | 코드 의미에 영향을 주지 않는 형식 변경 |
| `refactor` | 기능 변경 없는 코드 구조 개선 |
| `perf` | 성능 개선 |
| `test` | 테스트 추가 또는 수정 |
| `build` | 빌드 시스템 또는 외부 의존성 변경 |
| `ci` | CI 설정 및 스크립트 변경 |
| `chore` | 그 밖의 유지보수 작업 |
| `revert` | 이전 변경 사항 되돌리기 |

### 예시

```text
feat(FE): 예약 현황 컴포넌트 구현
feat(BE): 예약 생성 API 구현
refactor(BE): 예약 검증 책임을 도메인으로 이동
test(BE): 예약 중복 검증 테스트 추가
chore(AI): CodeRabbit 리뷰 설정 추가
```

상세 설명이 필요한 경우 다음과 같이 작성한다.

```text
fix(BE): 중복 예약이 생성되는 문제 수정

동일한 시간대의 예약 여부를 저장 전에 확인하도록 변경한다.
동시 요청에서도 중복 예약이 생성되지 않도록 제약 조건을 추가한다.
```

## 코드 스타일

### 공통

- 한 메서드의 들여쓰기 깊이는 최대 2단계로 제한한다.
- 반복 처리에서는 명령형 `for`문보다 Stream이 더 읽기 쉬운 경우 Stream 사용을 우선한다.
- 테스트 함수 이름은 영어로 작성한다.
- 테스트의 `@DisplayName`은 한글로 작성한다.

### 백엔드

Java 코드는 [우아한테크코스 IntelliJ Java 코드 스타일](https://github.com/woowacourse/woowacourse-docs/blob/main/styleguide/java/intellij-java-wooteco-style.xml)을 따른다.

#### Java 포매팅 규칙

- 탭 대신 공백을 사용한다.
- 기본 들여쓰기는 공백 4칸, 연속 들여쓰기는 공백 8칸으로 한다.
- 탭 크기는 공백 4칸으로 설정한다.
- 한 줄은 최대 120자를 기준으로 작성한다.
- 와일드카드 import를 사용하지 않고 필요한 타입을 직접 import한다.
- static import를 일반 import보다 먼저 배치하고 두 그룹 사이에 빈 줄을 둔다.
- `if`, `for`, `while`, `do-while`문은 본문이 한 줄이어도 항상 중괄호를 사용한다.
- 제어문과 본문을 한 줄에 함께 작성하지 않는다.
- 닫는 중괄호 바로 앞에 불필요한 빈 줄을 두지 않는다.
- 코드 내부에서 연속된 빈 줄은 최대 한 줄만 유지한다.
- 다음 표현이 한 줄의 최대 길이를 넘으면 적절히 줄바꿈한다.
  - 메서드 선언의 파라미터
  - 메서드 호출의 인자
  - `extends` 목록과 `throws` 목록
  - 메서드 체이닝
  - 이항 연산식과 삼항 연산식
  - `for`문과 배열 초기화식
- 이항 및 삼항 연산식을 줄바꿈할 때 연산자는 다음 줄에 배치한다.
- 긴 주석은 한 줄의 최대 길이에 맞게 줄바꿈한다.
- 배열 초기화 중괄호 앞에는 공백을 둔다.
- 내용이 없는 Javadoc의 `@param`, `@return`, `@throws` 태그는 유지하지 않는다.

패키지는 도메인을 기준으로 나누고, 각 도메인 아래에 계층을 배치한다.

```text
domain
├── reservation
│   ├── controller
│   ├── service
│   └── repository
└── waiting
    ├── controller
    ├── service
    └── repository
```

### 프론트엔드

포매터는 다음 설정을 사용한다.

```json
{
  "arrowParens": "always",
  "bracketSpacing": true,
  "endOfLine": "lf",
  "htmlWhitespaceSensitivity": "css",
  "insertPragma": false,
  "singleAttributePerLine": false,
  "bracketSameLine": false,
  "jsxSingleQuote": false,
  "printWidth": 80,
  "proseWrap": "preserve",
  "quoteProps": "as-needed",
  "requirePragma": false,
  "semi": true,
  "singleQuote": false,
  "tabWidth": 2,
  "trailingComma": "all",
  "useTabs": false
}
```

개발 도구가 필요한 경우 [Zed Education](https://zed.dev/education)의 학생 혜택을 참고한다.

## 테스트 기준

### 테스트 범위

- 도메인의 핵심 규칙은 단위 테스트로 검증한다.
- Service 계층은 슬라이스 테스트를 작성한다.
- 인수 테스트(E2E)는 Controller 계층을 진입점으로 사용자 시나리오를 검증한다.
- Spring Validation과 도메인 검증은 각각 테스트한다.
- Repository 테스트는 다음 상황에 작성한다.
  - Repository에 비즈니스 로직이 포함된 경우
  - 회귀를 방지하기 위해 반드시 보호해야 하는 동작이 있는 경우
- Repository 테스트를 추가한 PR에는 테스트가 필요한 근거를 설명한다.

### 검증문

검증문은 AssertJ를 사용해 실제값과 기대값이 명확하게 드러나도록 작성한다.

```java
assertThat(orderNumber).isEqualTo(3);
assertThat(isAvailable).isTrue();
```

다음과 같이 의도가 충분히 드러나지 않는 검증문은 사용하지 않는다.

```java
assertTrue(isAvailable);
```
