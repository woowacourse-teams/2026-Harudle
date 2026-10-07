# Frontend agent instructions

## Project context

이 파일은 `frontend/` 전체에 적용한다. 현재 구현된 애플리케이션은
`frontend/web`이다.

프론트엔드의 브랜치, 커밋, 실행 규칙과 프로젝트 설명은
[`web/README.md`](web/README.md)를 따른다.

아키텍처와 설계 결정의 배경은 [`web/docs/adr/README.md`](web/docs/adr/README.md)와
관련 ADR을 따른다.

## Decision boundaries

다음 변경은 사용자 요청이나 관련 ADR·문서에 명시적인 근거가 없는 한, 기존
코드만 보고 새로운 방식을 임의로 도입하지 않는다.

- 새로운 외부 라이브러리 또는 workspace 의존성 추가
- 기존 아키텍처의 의존 방향 변경
- 인증 또는 보안 흐름 변경
- `shared/`에 새로운 전역 추상화 또는 공용 패턴 도입
- 기존 ADR의 결정과 다른 구현 방식 도입

근거를 찾을 수 없다면 구현을 진행하기 전에 사용자 또는 프론트엔드 담당자에게
확인한다.

## Tooling and validation

- Node.js 24와 pnpm 11.20.0을 사용한다. 의존성은 `frontend/`에서
  `pnpm install`로 설치하고 npm 또는 yarn lockfile을 만들지 않는다.
- Web 전용 의존성은 `web/package.json`에 선언한다. 향후 workspace 내부
  패키지를 참조할 때는 `workspace:` 프로토콜을 사용한다.
- Web 변경을 완료하기 전에 `frontend/web`에서 `pnpm check`를 실행한다. 이
  스크립트는 typecheck, lint, format check, test, production build를 모두
  실행한다. `frontend/package.json`에는 통합 검사 스크립트가 없으므로 루트에서
  검사 명령을 추측하지 않는다.

## Code placement and components

- 특정 페이지에서만 쓰는 UI, Hook, 상태, API 코드는
  `web/src/pages/<page>/`에 함께 둔다. 페이지 폴더 안의 하위 디렉터리는 최대
  1 depth까지만 사용한다.
- 특정 페이지에 결합되지 않은 도메인 타입, API, 비즈니스 로직은
  `web/src/domain/<domain>/`에 둔다.
- UI나 유틸을 `web/src/shared/`로 옮기기 전에 여러 페이지에서 같은 책임으로
  사용되는지 확인한다.
- 화면이 시각적으로 나뉜다는 이유만으로 컴포넌트를 분리하거나 공통화하지
  않는다. 부모가 몰라도 되는 상태, 이벤트, Loading/Error UX 등의 독립적인
  책임이 있을 때 분리한다. 공통화는 다른 페이지에서 2회 또는 같은 페이지에서
  3회 반복될 때 검토하되, 같은 이유로 변경되는지를 우선한다.

## Styling

- 컴포넌트 스타일에는 Emotion의 `css` prop을 사용한다. 컴포넌트와 해당
  스타일을 같은 `.tsx` 파일에 두고, 스타일 선언은 컴포넌트 아래에 둔다.
- 여러 화면에서 공유하는 디자인 값은 `web/src/styles/theme.ts`의 토큰을
  사용한다. 단순한 스타일 적용만을 위해 Emotion의 `styled`로 별도 컴포넌트를
  만들지 않는다.

## TypeScript

- 선언과 검증 범위는 [ADR-0008](web/docs/adr/0008-typescript-conventions.md)을 따른다.
- 객체 타입과 Props는 기본적으로 `interface`를 사용한다. `type` 키워드는
  유니온·리터럴 타입에만 사용하고, 객체 확장은 `interface extends`를 우선한다.
- 상태마다 필요한 데이터가 다르면 판별 유니온으로 표현하고, 상태에 종속된
  필드를 하나의 객체에 선택적 속성으로 나열하지 않는다.
- 허용된 값이 정해진 역할·상태·월 등은 `string`·`number` 대신 리터럴
  유니온으로 제한한다.
- 같은 의미의 타입과 라이브러리 제공 타입은 재사용한다. 일부 속성이나 함수
  시그니처는 `Pick`, `Parameters`, `typeof` 등으로 추출한다.
- 한 매개변수가 다른 매개변수의 타입이나 전달 필요 여부를 결정하면 제네릭,
  매핑 타입 또는 튜플로 관계를 표현해 잘못된 인자 조합을 막는다.
- 함수 선언·화살표 함수·콜백의 반환 타입은 반드시 실제 반환값에 맞게
  명시한다. 반환값이 없으면 `void`, 비동기는 `Promise<T>` 또는 `Promise<void>`,
  타입 가드는 `value is T`를 사용하고 가능한 `null`·`undefined`도 표현한다.
- JSX 인라인 이벤트의 매개변수는 추론을 사용하고, 분리한 DOM 핸들러에는 대상 요소가
  포함된 React 이벤트 타입을 지정한다. DOM 이벤트가 필요 없는 콜백에는
  `() => void`, `(value: T) => void`처럼 필요한 값만 전달한다.
- 타입을 보장할 수 없는 외부 값과 에러는 `unknown`으로 받고, 타입 가드나
  `instanceof`로 좁힌 뒤 사용한다. 타입 단언으로 검증을 대체하지 않는다.
- `any`가 불가피하면 필요한 이유와 다른 타입으로 표현하기 어려운 이유를
  바로 위 주석에 적는다. ESLint 예외는 이유를 적어 해당 줄에만 적용한다.
- 읽기 전용 속성은 `readonly`, 상수 객체·배열은 `as const`, 읽기 전용 배열
  인자는 `readonly T[]` 또는 `ReadonlyArray<T>`로 표현한다.
- 타입 가드가 필드·중첩 구조·리터럴을 실제로 확인하는지 검토하고 잘못된
  입력을 거부하는지 테스트한다.
- 타입 가드 통과 후 내부 전달에서는 동일한 구조 검증을 반복하지 않는다.
  구조 검사와 날짜·개수·길이 등 도메인 검증의 차이를 구분한다.
- LLM 지침과 리뷰는 보조 수단이다. 병합 전 검사는 CI의 typecheck·lint·test로
  확인하며, `pnpm build`만으로 타입 검사가 실행된다고 간주하지 않는다.

## API and authentication boundaries

- `response.json()`과 브라우저 저장소 등 외부 경계의 값은 `unknown`으로 받고,
  직접 작성한 TypeScript 타입 가드로 필수 필드, 중첩 구조와 허용 리터럴을
  검증한 뒤 사용한다. 타입 단언으로 검증을 대체하지 않는다.
- 실패 응답이 Problem Details인지 공통 `web/src/shared/api.ts`의
  `isProblemDetails`로 검증하고, 확인된 값은 `RequestError`로 변환한다.
- 인증이 필요한 요청은 `web/src/shared/auth.ts`의 `authFetch`를 사용한다.
  Access Token은 메모리에만 두고 `localStorage`, `sessionStorage` 또는 쿠키에
  영속화하지 않는다. Refresh Token을 JavaScript에서 읽거나 저장하지 않는다.
- 인증 흐름을 수정할 때 기존 Single-Flight 갱신, 원 요청 최대 1회 재시도,
  중복 세션 만료 처리 방지를 보존한다. Refresh/logout처럼 쿠키를 사용하는 상태
  변경 요청에는 발급받은 CSRF Token을 `X-XSRF-TOKEN` 헤더로 보낸다.

## Tests

- 테스트는 대상 코드 옆에 `*.test.ts` 또는 `*.test.tsx`로 둔다.
- API 동작이 필요한 테스트에는 실제 서버 대신 `web/src/mocks/`의 MSW
  Handler를 사용한다.
