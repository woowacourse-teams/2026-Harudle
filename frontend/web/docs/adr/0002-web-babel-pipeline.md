# ADR-0002: Web 컴파일 파이프라인에 Babel 사용

- **날짜:** 2026-08-07

## 📜 맥락 (Context)

Web 애플리케이션의 TypeScript와 JSX를 브라우저에서 실행할 JavaScript로 변환할 컴파일 도구를 결정해야 했다.

프로젝트에는 React Native 기반 Mobile 애플리케이션과 Web·Mobile 공용 패키지가 추가될 예정이다. React Native의 Metro는 Babel 생태계를 기반으로 코드를 변환하므로, Web에서 SWC나 esbuild 같은 별도 컴파일러를 사용하면 공용 코드가 플랫폼마다 서로 다른 변환기와 플러그인 체계를 거치게 된다.

서로 다른 컴파일러를 사용하는 것 자체가 문제는 아니지만, 지원 문법과 플러그인 동작에 차이가 생기면 공용 패키지의 플랫폼별 빌드 결과를 각각 확인하고 두 종류의 설정을 관리해야 한다. 초기 팀 규모에서는 컴파일 속도보다 Web과 Mobile의 변환 기반을 통일하여 설정과 디버깅의 차이를 줄이는 것을 우선하기로 했다.

초기에는 Babel 8을 사용했지만, 테스트 환경을 구성하는 과정에서 Jest와 일부 전이 의존성의 Peer Dependency가 Babel 8을 지원하지 않는 호환성 문제가 확인되었다. Babel을 유지하면서 Jest 기반 테스트 환경도 함께 사용하기 위해 현재 생태계가 안정적으로 지원하는 Babel 7로 변경할 필요가 있었다.

## 🎯 결정 (Decision)

Web의 TypeScript와 JSX 컴파일러로 Babel을 사용한다.

- Webpack에서는 `babel-loader`를 통해 `.ts`, `.tsx` 파일을 Babel로 변환한다.
- `@babel/preset-env`, `@babel/preset-react`, `@babel/preset-typescript`를 사용하여 브라우저 문법, React JSX와 TypeScript 문법을 처리한다.
- TypeScript 컴파일러는 JavaScript 출력에 사용하지 않고 `tsc --noEmit`으로 타입 검사만 수행한다.
- 향후 Mobile과 공용 패키지가 추가되면 Babel 생태계를 공통 기반으로 사용하되, 플랫폼별 실행 환경 차이가 필요한 설정은 각각 유지한다.
- Webpack과 `babel-loader`는 현재 Web에서 Babel을 실행하기 위한 구현 수단이며, 이 ADR의 핵심 결정은 Web 컴파일 파이프라인에 Babel을 사용한다는 것이다.
- 구성 당시 Babel 8은 Jest와 일부 의존성의 Peer Dependency 호환 문제로 함께 사용할 수 없어 Babel 7.29.7을 사용한다. Jest 생태계의 Babel 8 지원이 안정되고 Peer Dependency 검사를 통과하면 Babel 8 업그레이드를 다시 검토한다.

## ⚖️ 결과 (Consequences)

### 긍정적 효과

- Web과 React Native가 같은 Babel 플러그인 생태계를 사용하여 공용 코드의 변환 차이를 줄일 수 있다.
- 공용 Babel 플러그인이 필요할 때 Web과 Mobile에 같은 방식으로 적용하기 쉽다.
- TypeScript의 타입 검사와 코드 변환 책임을 분리하여 Babel은 빠른 문법 변환, `tsc`는 정적 타입 검사를 담당하게 할 수 있다.
- Babel의 플러그인 생태계를 활용할 수 있다.
- Web과 Mobile의 컴파일 문제를 조사할 때 서로 다른 변환 도구를 동시에 이해해야 하는 부담을 줄일 수 있다.

### 부정적 효과 / 감수할 점

- Babel은 TypeScript 타입을 제거할 뿐 타입 오류를 검사하지 않으므로 별도의 `tsc --noEmit` 과정이 반드시 필요하다.
- SWC나 esbuild 기반 변환보다 개발 및 프로덕션 빌드 속도가 느릴 수 있다.
- Babel 설정과 프리셋, Webpack Loader의 호환성을 함께 관리해야 한다.
- 같은 Babel 생태계를 사용하더라도 Web과 Metro의 설정 및 실행 환경이 완전히 같아지는 것은 아니므로 플랫폼별 검증은 여전히 필요하다.
- Babel 플러그인에 의존하는 코드가 늘어나면 향후 다른 컴파일러로 전환하는 비용이 커질 수 있다.
- 현재는 Jest 생태계의 Peer Dependency 제약으로 Babel 8 대신 Babel 7을 유지해야 한다.

## 🔄 대안 (Alternatives Considered)

### 대안 1: SWC 사용

- **채택하지 않은 이유:** Rust 기반의 빠른 변환 성능이 장점이지만, React Native가 사용하는 Babel과 별도의 플러그인 및 설정 체계를 관리해야 한다. 초기에는 빌드 성능보다 Web·Mobile 컴파일 기반의 일관성을 우선했다.

### 대안 2: esbuild 사용

- **채택하지 않은 이유:** 설정이 단순하고 변환 속도가 빠르지만, Mobile의 Babel 파이프라인과 별도로 관리해야 하며 Babel 플러그인을 그대로 공유할 수 없다.

### 대안 3: TypeScript Compiler로 변환과 타입 검사 모두 수행

- **채택하지 않은 이유:** 별도의 Babel 없이 TypeScript를 JavaScript로 변환할 수 있지만, React Native의 Metro/Babel 환경과 변환 기반이 달라지고 Babel 플러그인 생태계를 공통으로 사용하기 어렵다.

### 대안 4: Babel 8 사용

- **채택하지 않은 이유:** 최초 개발 환경에서는 Babel 8을 사용했지만, Jest와 일부 전이 의존성의 Peer Dependency가 Babel 8을 지원하지 않아 테스트 환경과 호환되지 않았다. Babel 8 자체를 배제한 결정은 아니며, Jest 생태계의 지원이 안정되면 업그레이드를 재검토한다.

## 📎 연관 이슈 및 문서

- 연관 PR: [#2](https://github.com/woowacourse-teams/2026-Harudle/pull/2)
