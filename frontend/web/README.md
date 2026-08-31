# 하루들 Web

하루의 기록을 네 컷 이미지로 만들어 주는 하루들 서비스의 웹 애플리케이션입니다.

## 빠르게 시작하기

### 요구 환경

- Node.js 24
- pnpm 11.20.0

### 실행

```bash
cd frontend
corepack enable
pnpm install
cd web
pnpm dev
```

개발 서버는 `http://localhost:5173`에서 실행됩니다. 개발 환경에서는 MSW가 자동으로 활성화되며, Mock이 없는 요청은 그대로 서버로 전달됩니다.

## 기술 스택

| 구분      | 기술                             |
| --------- | -------------------------------- |
| UI        | React, TypeScript, React Router  |
| 스타일링  | Emotion                          |
| 빌드      | Webpack, Babel                   |
| 테스트    | Jest, React Testing Library, MSW |
| 코드 품질 | ESLint, Prettier                 |
| 모노레포  | pnpm Workspace                   |

## 코드 위치 정하기

```text
src/
├── pages/       # 특정 페이지에서만 사용하는 UI, Hook, 상태, API
├── shared/      # 여러 페이지에서 사용하는 UI와 범용 코드
├── styles/      # 전역 스타일과 디자인 토큰
├── mocks/       # MSW Handler
├── assets/      # 이미지와 아이콘
└── test/        # 공통 테스트 설정
```

- 페이지 전용 코드는 `pages/<페이지>/`에 함께 두고, 페이지 내부 폴더는 최대 1 depth까지만 사용합니다.
- 특정 페이지에 종속되지 않는 도메인 타입·API·비즈니스 로직이 생기면 `domain/<도메인>/`을 만듭니다.
- `shared/`로 옮기기 전, 실제로 여러 페이지에서 같은 책임으로 사용되는지 확인합니다.

## 개발 규칙

### 컴포넌트

- 화면이 나뉘어 보인다는 이유만으로 분리하지 않습니다. 부모가 몰라도 되는 상태, 이벤트, Loading/Error UX 등의 독립적인 책임이 있을 때 분리합니다.
- 공통화는 다른 페이지에서 2회 또는 같은 페이지에서 3회 반복될 때 검토합니다. 횟수보다 **같은 이유로 변경되는지**를 우선합니다.

### 스타일

- Emotion의 `css` prop을 사용하고 컴포넌트와 스타일을 같은 `.tsx` 파일에 둡니다.
- 렌더링 흐름을 먼저 읽을 수 있도록 스타일 선언은 컴포넌트 아래에 둡니다.
- 여러 화면에서 공유하는 디자인 값은 `styles/theme.ts`의 토큰을 사용합니다.

### API

- `response.json()` 결과는 `unknown`으로 받고 타입 가드로 검증한 뒤 사용합니다.
- Problem Details 오류는 공통 `isProblemDetails`와 `RequestError`를 사용합니다.
- 인증이 필요한 요청은 `shared/auth.ts`의 `authFetch`를 사용합니다.

### 테스트

- 테스트 파일은 대상 코드 옆에 `*.test.ts` 또는 `*.test.tsx`로 작성합니다.
- API 동작이 필요하면 실제 서버 대신 `mocks/`의 MSW Handler를 사용합니다.

## 브랜치와 배포 흐름

```text
작업 브랜치(예시: feat/home-page, fix/oauth-error)
        ↓
develop-frontend
        ↓
dev ── CI/CD → dev.harudle.com
        ↓
main ── CI/CD → harudle.com
```

1. `develop-frontend`에서 `feat/<작업명>` 또는 `fix/<작업명>` 형식의 작업 브랜치를 생성합니다.
2. 작업이 끝나면 `develop-frontend`를 대상으로 PR을 올립니다.
3. QA가 필요한 변경을 `dev`에 병합하면 `dev.harudle.com`으로 자동 배포됩니다.
4. 개발 환경에서 QA를 마친 뒤 `main`에 병합하면 프로덕션 환경인 `harudle.com`으로 자동 배포됩니다.

## 커밋 메시지

[Angular 컨벤션 기반의 Conventional Commits](https://www.conventionalcommits.org/en/v1.0.0-beta.4/)를 따릅니다.

```text
<type>(<scope>): <한글 요약>

<변경 이유와 추가 설명>
```

- 제목과 본문은 한글로 작성합니다.
- Scope는 `FE` 를 사용합니다.
- 가능하면 빈 줄 다음에 코드만으로 드러나지 않는 변경 이유와 주요 내용을 작성합니다.

주요 Type은 다음과 같습니다.

| Type       | 용도                          |
| ---------- | ----------------------------- |
| `feat`     | 기능 추가                     |
| `fix`      | 버그 수정                     |
| `refactor` | 동작 변경 없는 코드 구조 개선 |
| `test`     | 테스트 추가 또는 수정         |
| `docs`     | 문서 변경                     |
| `chore`    | 그 외 설정 및 유지보수        |

```text
feat(FE): 로그인 화면 구현

OAuth 로그인 진입점과 실패 시 오류 메시지를 추가한다.
```

## 명령어

| 명령어                 | 설명                                     |
| ---------------------- | ---------------------------------------- |
| `pnpm dev`             | 개발 서버 실행                           |
| `pnpm test`            | 테스트 실행                              |
| `pnpm test:watch`      | 테스트 Watch 모드 실행                   |
| `pnpm test:e2e`        | e2e 테스트 실행                          |
| `pnpm test:e2e:ui`     | e2e 테스트를 브라우저에서 실행           |
| `pnpm test:e2e:report` | e2e 테스트 보고서 열기                   |
| `pnpm build`           | 프로덕션 빌드                            |
| `pnpm check`           | 타입, 린트, 포맷, 테스트, 빌드 전체 검사 |

PR을 올리기 전 `pnpm check`를 실행합니다.

결정의 배경과 세부 기준은 [ADR](./docs/adr/README.md)에서 확인할 수 있습니다.
