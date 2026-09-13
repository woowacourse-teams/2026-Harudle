# 색상 토큰 사용 기준

`theme.colors`는 색상이 화면에서 맡는 역할을 기준으로 선택합니다.

| 분류         | 용도                        | 예시                                          |
| ------------ | --------------------------- | --------------------------------------------- |
| `foreground` | 글자와 아이콘               | `neutral`, `neutralMuted`, `brand`, `onBrand` |
| `background` | 화면과 요소의 배경          | `surface`, `brandSolid`, `brandWeak`          |
| `stroke`     | 외곽선, 구분선, 포커스 표시 | `outline`, `divider`, `critical`, `focusRing` |

- 마스크 아이콘은 CSS의 `background-color`로 그리더라도 `foreground`를 사용합니다.
- `outline`은 카드·입력창 등 요소의 외곽선, `divider`는 항목, 영역 사이 구분선에 사용합니다.
- 같은 색상값이어도 역할이 다르면 토큰을 분리합니다. 내부 팔레트는 값만 공유하며 컴포넌트에서 직접 참조하지 않습니다.
- `background.brandWeak`는 넓은 브랜드 배경과 연한 브랜드 강조 영역에 공통으로 사용합니다.
- `stroke.focusRing`은 포커스 표시의 공통 색상이며 불투명도는 35%입니다.
- `onBrand`는 브랜드 배경 위에 올라가는 글자 또는 아이콘에 사용합니다.
- 실제 사용처가 있고 같은 목적으로 함께 변경할 값만 공통 토큰으로 추가합니다. 관리자 화면의 별도 색조, 장식, 그라데이션·그림자는 해당 컴포넌트 가까이에 유지합니다.
- 특정 기능 안에서만 공유되는 색은 해당 페이지 폴더에 둡니다. 예: `pages/home/diaryTimelineColors.ts`.
