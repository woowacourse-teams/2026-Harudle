# 하루들 Mobile

배포된 하루들 웹을 WebView로 여는 Expo 앱입니다.

## 실행

`frontend/`에서 `pnpm install`을 실행한 뒤, `frontend/mobile/`에서 실행합니다.

```bash
pnpm start
```

화면은 `App.tsx`, 앱 진입점은 `index.ts`에 있습니다.

## 검사

```bash
pnpm typecheck
pnpm lint
```

## 릴리스 빌드

앱 버전은 `app.json`에서만 바꿉니다. `android/`와 `ios/`는 `expo prebuild`가 만드는
폴더이고 git에 올라가지 않으므로, 이 폴더의 버전 값을 직접 고치지 않습니다.

### 1. 버전 올리기

`app.json`의 세 값을 바꿉니다.

| 값 | 뜻 | 올리는 때 |
| --- | --- | --- |
| `expo.version` | 스토어에 보이는 버전. 두 플랫폼이 같이 씁니다. | 새 버전을 낼 때 |
| `expo.android.versionCode` | Play Console 빌드 번호. 정수입니다. | Android에 제출할 때마다 |
| `expo.ios.buildNumber` | App Store Connect 빌드 번호. 문자열입니다. | iOS에 제출할 때마다 |

빌드 번호는 스토어에 이미 올린 값보다 커야 합니다. 반려 후 코드를 고쳐 다시 제출할 때는
`version`은 그대로 두고 해당 플랫폼의 빌드 번호만 올립니다.

### 2. 네이티브 폴더에 반영하기

```bash
npx expo prebuild
```

`pnpm android`와 `pnpm ios`는 네이티브 폴더가 이미 있으면 다시 만들지 않습니다.
이 단계를 건너뛰면 예전 버전 번호로 빌드됩니다. 릴리스 빌드 전에는 항상 실행합니다.

실행 후 값이 반영됐는지 확인합니다.

```bash
grep -n "versionCode\|versionName" android/app/build.gradle
```

```bash
grep -A1 "CFBundleShortVersionString\|CFBundleVersion" ios/app/Info.plist
```

### 3. Android 빌드

Android Studio에서 `android/`를 열고 **Build > Generate Signed App Bundle / APK**로
AAB를 만듭니다. 릴리스 키스토어로 서명합니다. 결과물은 `android/app/release/app-release.aab`에
생깁니다. 이 파일을 Play Console에 올립니다.

`build.gradle`의 release 빌드는 기본값으로 디버그 키스토어를 쓰므로,
`./gradlew bundleRelease`로 만든 AAB는 스토어에 올릴 수 없습니다.
키스토어와 비밀번호는 저장소에 넣지 않습니다.

### 4. iOS 빌드

Xcode에서 `ios/app.xcworkspace`를 엽니다. 실행 대상을 **Any iOS Device**로 두고
**Product > Archive**를 실행한 뒤, Organizer에서 App Store Connect로 올립니다.

### 5. 태그 찍기

스토어 승인 후 사용자에게 출시되면, 제출한 빌드의 커밋에 태그를 찍습니다.

```bash
git tag -a mobile-v1.2.0 <제출한 커밋 SHA> -m "mobile-v1.2.0"
```

```bash
git push origin refs/tags/mobile-v1.2.0
```

두 플랫폼의 출시일이 다르면 태그는 하나만 찍고 출시일을 릴리스 노트에 적습니다.
한 플랫폼만 낸 버전도 같은 형식으로 찍고, 어느 플랫폼인지 노트에 남깁니다.

### 주의

- 네이티브 폴더를 직접 고친 내용은 `npx expo prebuild --clean`을 실행하면 사라집니다.
  유지해야 하는 네이티브 설정은 `app.json`의 플러그인 설정으로 옮깁니다.
- 앱은 `WebviewScreen.tsx`의 `WEB_URL`로 운영 웹을 엽니다. 웹에서 새 네이티브 기능을
  쓰는 변경은 해당 앱 버전이 출시된 뒤에 배포합니다.
