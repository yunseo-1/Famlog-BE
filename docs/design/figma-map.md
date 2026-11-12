# FamLog Figma 화면 지도

> Claude Code가 Figma를 매번 호출하지 않고도 화면 구조를 알 수 있게 만든 로컬 지도다.
> 기준 시점: 2026-11-12. Figma가 바뀌면 이 문서도 같이 고친다.

## 0. 파일 정보

| 항목 | 값 |
|---|---|
| 파일 | FamLog - Wireframe & Design Patterns |
| URL | https://www.figma.com/design/1nECE5iK8TBDRSydCasO1j |
| fileKey | `1nECE5iK8TBDRSydCasO1j` |
| 페이지 | `🎨 Design Patterns` (`0:1`), `📱 Wireframes` (`2:2`) |
| 기준 기기 | Android 360×800 dp, 좌우 여백 16, 8pt 그리드 |
| 폰트 | Noto Sans KR (Regular / Medium / Bold) |
| 노드 링크 | `https://www.figma.com/design/1nECE5iK8TBDRSydCasO1j?node-id=7-156` (노드 `7:156` → `7-156`) |

**Figma MCP 사용 규칙 (Starter 요금제 호출 한도):** 이 문서와 `screenshots/`로 먼저 구현한다.
부족할 때만 노드 하나에 `get_design_context` 또는 `get_screenshot`을 호출한다.

---

## 1. 화면 ↔ 사양서 화면 ID

| Figma 화면 | 노드 ID | 사양서 | 캡처 | 상태 |
|---|---|---|---|---|
| 01 가족 그룹 목록 (채팅 탭) | `6:46` | SCR-10 | `01_group_list.png` | 최신 |
| 02 채팅방 | `6:114` | SCR-11 | (없음) | ⚠️ 구버전. 5장 "목표 구조"로 구현 |
| 03 캘린더 (현재 그룹: 우리 가족) | `7:156` | SCR-20 | `03_calendar.png` | 최신 |
| 04 장소 · 지도/지역별 | `7:366` | SCR-30/31 | `04_places.png` | 앱 바는 실제로 "우리 가족 ▾" |
| 05 앨범 · AI 여행 앨범 | `8:256` | SCR-40 (v1.1) | `05_album.png` | 앱 바는 실제로 "우리 가족 ▾" |
| 06 주간 이야기 | `8:365` | SCR-50 (v1.1) | `06_story.png` | 사양 변경: **월간** 우선 |
| 07 가족 그룹 전환 (바텀시트) | `16:396` | SCR-70 | `07_group_switch.png` | 캡처 속 선택일은 구버전(22일) |
| 디자인 가이드 | `5:45` | – | `design_guide.png` | 패턴 A 문구는 Figma가 최신 |

아직 Figma에 없는 MVP 화면: SCR-01 로그인, SCR-02 그룹 시작, SCR-03 가족 역할 설정, SCR-12 채팅방 서랍,
SCR-21 일정 등록, SCR-32 장소 등록, SCR-80 설정. → 아래 컴포넌트와 패턴을 조합해 구현한다.

---

## 2. 공통 골격 (패턴 F)

```
Screen 360×800 (Auto Layout VERTICAL, bg = color/bg/default)
├─ App Bar (instance, 56)
├─ Content (FILL, padding L/R 16 · top 8 · bottom 16, gap 12, clip)
└─ Bottom Nav (instance, 64)   ← 탭 화면만. 상세 화면은 숨기고 App Bar의 Back을 켠다
FAB 56×56 (absolute, 오른쪽 16 / Bottom Nav 위 16, bg brand/primary, plus 아이콘 on-brand, 그림자 y4 blur12 18%)
```

- 탭 화면 앱 바: Title = 현재 그룹 이름 + `Show Group Switcher = true` (그룹이 1개면 false)
- 채팅 탭(01)만 Title "FamLog", 그룹 전환 없음

---

## 3. 화면별 구조

### 01 가족 그룹 목록 `6:46`

```
App Bar  Title="FamLog", Back=off, Action=on(search), GroupSwitcher=off
Content
├─ Story Banner (HORIZONTAL, bg brand/subtle, radius 16, padding 16, gap 12)
│   ├─ icon/story (brand/primary)
│   └─ "새 이야기가 도착했어요" (Body/Medium Strong) / "11월 2주차 · 할머니 생신 준비로 바빴던 한 주" (Caption, text/secondary)
├─ "내 가족 그룹" (Caption, text/tertiary)
├─ List Item × N   Title="우리 가족 · 4명", Subtitle="동생: 누가 휴지 좀 사와 ㅠㅠ", Meta="오후 3:12"
│                  (안 읽은 수 배지는 아직 미디자인 → brand/primary 원형 배지, Caption on-brand 로 구현)
└─ New Group Row: 44 원(surface/muted)+plus(brand) / "새 가족 그룹 만들기"(brand, Body/Medium Strong) / "초대 코드를 받았다면 여기서 참여해요"(Caption tertiary)
Bottom Nav  active=채팅
```

### 03 캘린더 `7:156`

```
App Bar  Title="우리 가족", GroupSwitcher=on, Action=on(search)
Content
├─ Segmented [일정 | 할 일]  (bg surface/muted, radius full, padding 4; 선택 칸 bg/default + Body/Medium Strong, 비선택 text/tertiary)
├─ Member Chip 행 (가로 스크롤): 전체(Selected, 점 없음) · 아빠 #14A3B8 · 엄마 #E5568D · 윤서 #E8A33C · 동생 #7BB33A
│   (점 색 = group_members.calendar_color)
├─ "2026년 11월" (Title/Medium)
├─ Month Grid: 요일 행(일~토, Caption tertiary) + 주 5행 × 7칸 (칸 높이 44)
│   └─ 칸: 날짜 원 26 (선택일: bg brand/primary + Label on-brand, 일요일 숫자 text/tertiary) + 구성원 색 점 5px 가로 나열
├─ Divider 1px border/default
├─ "11월 21일 (토)" (Body/Medium Strong)
└─ Event Row (HORIZONTAL gap 12): 색 막대 4×40(일정 주인 색) / 제목(Body/Medium Strong)+메타(Caption secondary) /
    태그 "채팅에서 등록" (bg category/schedule-bg, text category/schedule, Caption, radius full) ← 후보 승인으로 생긴 일정만
FAB (+)
Bottom Nav  active=캘린더
```

### 04 장소 `7:366`

```
App Bar  Title="우리 가족", GroupSwitcher=on
Content
├─ Segmented [지도 | 리스트]
├─ Chip 행: 전체 · 식당 · 카페 · 여행지 (Member Chip 재사용, 점 숨김)
├─ Map 328×260 radius 12 (Kakao Map SDK로 대체)
│   └─ Pin: pill(bg category/place, radius full, padding 4/10/4/6) = place 아이콘 16(on-brand) + 장소명(Caption on-brand); 내 위치 = 파란 점 14 + 흰 테두리 3
├─ "경기 수원시 영통구 · 3곳" (Body/Medium Strong)   ← 지역별 묶음 헤더
└─ List Item × N  Title=장소명, Subtitle="식당 · 아빠가 공유 · 11/12", Meta="1.2km"
```

사양서 PLC-02/03 반영 필요(아직 Figma 없음): [리스트] 탭에서 **지역별(시·도 → 시·군·구 접기)** 과
**구성원별("공유한 사람" Member Chip 필터)** 전환을 만든다. 칩 행을 `지역 | 구성원` 세그먼트 아래에 둔다.

### 05 앨범 `8:256` (v1.1)

```
Content
├─ AI Trip Album Card (border/default 1px, radius 16, padding 16, gap 10)
│   ├─ sparkle 16 + "AI가 여행을 찾아 앨범을 만들었어요" (Label, brand)
│   ├─ Cover: Image 192×148 + 세로 2장
│   ├─ "11/14~11/16 강릉 가족여행" / "사진 128장 · 4명이 올림 · 비슷한 사진 23장 정리됨"
│   └─ Button Small Primary "영상 만들기" · Secondary "앨범 보기"
├─ "모든 앨범" + 오른쪽 "수동 앨범 만들기"(Label brand)
└─ Album Grid 2열 (156×120 이미지 + 제목 + 메타), 간격 16
```

v1.1 사양 반영: 상단에 **월별 타임라인**(2026년 11월 ▸ 사진 n장 · 동영상 n개)과 [이번 달 영상 만들기] 추가.

### 06 이야기 `8:365` (v1.1)

```
Content
├─ Segmented [주간 | 월간]  ← 사양 변경: 월간만 (세그먼트 제거하고 월 목록)
├─ Story Card (border, radius 16, clip): Cover 150 / 라벨(Caption brand) / 제목(Title/Medium) / 본문(Body/Medium secondary) /
│   Highlights 3칸(category 색 bg + 숫자 Title/Medium + Caption) / Button Small: "영상으로 만들기", "공유 링크", Ghost "원문 보기"
└─ "지난 이야기" + List Item
```

v1.1 사양 반영: 하이라이트에 기념일 추가 가능, **구성원별 한 줄** 섹션, 초안(DRAFT) 배지와 [수정] 버튼.

### 07 가족 그룹 전환 `16:396`

```
(03 화면 위) Dim: 검정 40%
Bottom Sheet (radius 상단 20, padding 16/12/24, gap 4)
├─ Handle 36×4 (surface/placeholder)
├─ "가족 그룹 선택" (Title/Medium)
├─ Group Row × N: 아바타 40 / 이름(Body/Medium Strong) + 메타(Caption tertiary) / 현재 그룹: bg brand/subtle + 이름 brand + check(brand)
├─ Divider
└─ + "새 가족 그룹 만들기" / + "초대 코드로 참여하기" (Body/Medium)
```

---

## 4. 컴포넌트 (페이지 `0:1`)

| 컴포넌트 | 노드 | 변형 (Variant) | 속성 (Property) | 구현 Composable |
|---|---|---|---|---|
| Icons | `3:5` chat · `3:9` calendar · `3:13` place · `3:18` album · `3:22` story · `3:25` back · `3:29` send · `3:32` plus · `3:36` search · `3:39` sparkle · `3:44` more · `3:47` check · `16:47` chevron-down | – | 24px, stroke 2 (Lucide 계열) | `FamIcons.*` (lucide 또는 Material Symbols Rounded로 대체 가능) |
| Button | `3:60` | Style=Primary/Secondary/Ghost × Size=Medium/Small | Label | `FamButton(style, size)` |
| Member Chip | `3:67` | Selected=true/false | Name, (자식) Member Color 점 | `MemberChip` |
| Nav Tab | `3:76` | State=Active/Inactive | Label | `BottomNav` 내부 |
| Bottom Nav | `3:77` | – | 탭 5개 고정 | `FamBottomNav` |
| App Bar | `3:107` | – | Title, Show Back, Show Action, Show Group Switcher | `FamAppBar` |
| Chat Bubble | `4:37` | Sender=Other/Mine | Message, (자식) Sender 이름 | `ChatBubble` |
| ⭐ AI Suggestion Card | `4:80` (Anniversary `26:96`) | Type=Schedule/Anniversary/Todo/Place | Title, Meta, Inferred, Show Inferred, Evidence, Evidence Meta | `CandidateCard` |
| ⭐ Resolved Chip | `26:134` | Type=Schedule/Anniversary/Todo/Place | Label, Show Undo | `ResolvedChip` |
| List Item | `4:81` | – | Title, Subtitle, Meta | `FamListItem` |
| Image Placeholder | `4:87` | – | – | 로딩/빈 이미지 |
| Chat Input | `4:92` | – | – | `ChatInputBar` |

### 세부 규격

- **Button**: Medium padding 12/20, Small padding 8/12, radius md(12). 글꼴 Medium=Body/Medium Strong, Small=Label.
  Primary = bg brand/primary + text on-brand · Secondary = bg/default + 1px border/strong + text/primary · Ghost = 배경 없음 + text brand/primary.
- **Member Chip**: radius full, padding 4/12, gap 4, 점 8px. 선택 = bg brand/subtle + 1px brand + Label brand. 비선택 = bg/default + 1px border/default + text/secondary.
- **Nav Tab**: 72×56, 아이콘 24 + Caption, gap 4. Active = brand/primary, Inactive = text/tertiary.
- **App Bar**: 360×56, padding 16, gap 8. `[Back] [Title Group = Title(Title/Medium) + chevron-down 20] [Spacer FILL] [Action]`.
- **Chat Bubble**: 말풍선 padding 8/12, radius 16, 텍스트 최대 너비 200. Other = 아바타 32 + 이름(Caption secondary) + bubble/other, 왼쪽 정렬. Mine = bubble/mine, 오른쪽 정렬.
- **AI Suggestion Card** (사양서 7.4 ③④): 너비 280, padding 16, gap 8, radius 16, bg category-bg, 1px stroke category.
  ```
  Header: sparkle 16 + "일정을/기념일을/할 일을/장소를 찾았어요" (Label, category 색)
  Title (Body/Medium Strong)
  Meta (Body/Medium, text/secondary, 여러 줄)
  Inferred (Show Inferred): Badge "추정"(Caption tertiary, bg/default, 1px border, radius full) + 텍스트(Caption secondary)
  Evidence: bg/default, radius 8, 왼쪽 3px category 테두리, padding 6/8/6/10
     ├─ 인용 “…” (Body/Medium) ← 탭하면 원문으로 스크롤 + 추출 구간 형광 강조
     └─ "엄마 · 오후 2:10 · 원문 보기" (Caption tertiary)
  Actions: Button Small Primary "등록" · Secondary "수정" · Ghost "무시"
  ```
  채팅방에서는 근거 메시지 아래, 왼쪽 들여쓰기 40(아바타 폭)으로 붙인다.
- **Resolved Chip**: radius full, padding 6/12, bg category-bg, check 16 + Label(category 색) "캘린더에 등록됨 · 엄마 · 보기", 승인 후 5분간 "되돌리기"(Label tertiary, 밑줄).
- **List Item**: 너비 FILL, padding 12/0, gap 12, 썸네일 44 원, Title(Body/Medium Strong) / Subtitle(Body/Medium tertiary, 1줄 말줄임) / Meta(Caption tertiary).
- **Chat Input**: 상단 1px border, padding 8, gap 8. plus(text/secondary) · 입력창(surface/muted, radius full, padding 8/16, "메시지 입력" tertiary) · send(brand).

---

## 5. 02 채팅방 계열 목표 구조 (Figma 미반영, 이대로 구현)

공통: App Bar Title="우리 가족 (4)", Back=on, Action=on, GroupSwitcher=off. Content bg = color/bg/subtle, gap 10. 하단 Chat Input. Bottom Nav 없음.

### 02 등록 후보 카드

```
"11월 12일 목요일" (Caption tertiary, 가운데)
Bubble Other(엄마): "이번 토요일 11시에 할머니 댁 가자"
  └ Indent 40 → CandidateCard Schedule
       Title "할머니 댁 방문" / Meta "11/14(토) 오전 11:00 · 할머니 댁"
       Inferred "참석자: 가족 전체" / Evidence “이번 토요일 11시에 할머니 댁 가자” · "엄마 · 오후 2:10 · 원문 보기"
Bubble Mine: "좋아요! 가는 길에 과일 사 갈게요"
Bubble Other(엄마): "아빠가 이번 주에 자동차 점검 맡겨야 해"
  └ CandidateCard Todo
       Title "자동차 점검 맡기기" / Meta "담당: 아빠 · 기한 11/15(일)까지"
       Inferred "기한: \"이번 주\" → 11/15(일)" / Evidence … · "엄마 · 오후 2:12 · 원문 보기"
```

### 02-2 승인 후 + 장소·기념일 후보

```
Bubble Other(엄마): "이번 토요일 11시에 할머니 댁 가자"
  └ ResolvedChip Schedule "캘린더에 등록됨 · 엄마 · 보기" + 되돌리기
System Message (가운데 pill, bg surface/muted, Caption secondary): "엄마가 ‘할머니 댁 방문’을 캘린더에 등록했어요"
Bubble Other(동생): "잠실에 있는 OO칼국수 정말 맛있더라"
  └ CandidateCard Place
       Title "OO칼국수" / Meta "맛집 · 서울 송파구 잠실동\n추천: 칼국수가 정말 맛있음 — 동생"
       Inferred "지역·카테고리: 장소 검색 결과" / Evidence … · "동생 · 오후 3:05 · 원문 보기"
       (카카오 검색 결과가 2개 이상이면 카드 위에 "어느 곳인가요?" 후보 목록을 먼저 보여준다)
Bubble Other(언니): "다음 달 15일이 할머니 생신이야"
  └ CandidateCard Anniversary
       Title "할머니 생신" / Meta "매년 12월 15일 · 1주 전 알림" / Inferred "양력으로 추정 (음력이면 수정)"
```

### 02-3 등록 후보 수정 바텀시트

```
(02 화면 위) Dim 40%
Edit Sheet (radius 상단 20, padding 16/12/20, gap 12)
├─ Handle
├─ "일정 등록" (Title/Medium) + "AI가 채운 내용을 확인하고 고쳐 주세요. 등록 전에는 캘린더에 저장되지 않아요." (Caption tertiary)
├─ 필드: 라벨(Caption secondary) + 입력 박스(radius 12, padding 10/12, 1px border/default)
│   제목 "할머니 댁 방문"
│   [날짜 "2026년 11월 14일 (토)"] [시간 "오전 11:00"  ← AI 추정: 라벨 옆 배지 "AI 추정 · 확인 필요"(bg brand/subtle, Caption brand), 박스 테두리 brand]
│   장소 "할머니 댁"
│   참석자: Member Chip(선택) 아빠·엄마·언니·동생  ← AI 추정 배지
├─ 근거 블록 (bg/subtle, 왼쪽 3px category 테두리): "근거: “이번 토요일 11시에 할머니 댁 가자”" / "엄마 · 11/12(목) 오후 2:10 · 원문 보기"
└─ Button Medium: Secondary "취소" | Primary "등록"  (각 FILL)
필수 항목(missingFields)이 비어 있으면 "등록" 비활성 + 해당 필드에 "입력해 주세요" (status/danger)
```

---

## 6. 디자인 토큰

정확한 값은 `design-tokens.json`, Compose 초안은 `compose-theme/`.

| 의미 | 토큰 | 값 |
|---|---|---|
| 주요 행동(버튼·활성 탭) | color/brand/primary | #C94A26 |
| 브랜드 강조 | color/brand/accent | #E8643C |
| 선택 칩 배경 | color/brand/subtle | #FFF1EB |
| 내 말풍선 | color/bubble/mine | #FFDCCB |
| 상대 말풍선 | color/bubble/other | #EFEFF1 |
| 일정 | color/category/schedule (+bg) | #2F6FED / #EAF1FF |
| 기념일 | color/category/anniversary (+bg) | #C2255C / #FCEBF3 |
| 장소 | color/category/place (+bg) | #1F9D6B / #E6F6EF |
| 할 일 | color/category/todo (+bg) | #7A4FE0 / #F1ECFF |
| 본문/보조/약한 텍스트 | color/text/primary·secondary·tertiary | #1C1C21 / #4A4A52 / #8A8A93 |
| 배경 | color/bg/default·subtle | #FFFFFF / #F7F7F8 |
| 면·자리 | color/surface/muted·placeholder | #EFEFF1 / #E1E1E5 |
| 테두리 | color/border/default·strong | #E1E1E5 / #C8C8CE |
| 위험 | color/status/danger | #D93A3A |

간격 xs 4 · sm 8 · md 12 · lg 16 · xl 20 · 2xl 24 · 3xl 32 / 모서리 sm 8 · md 12 · lg 16 · full

| 텍스트 스타일 | 크기/굵기/행간 | 용도 |
|---|---|---|
| Title/Large | 22 Bold / 30 | 화면 큰 제목, 이야기 제목 |
| Title/Medium | 18 Bold / 26 | 앱 바, 섹션 제목 |
| Body/Large | 16 Regular / 24 | 이야기 본문 |
| Body/Medium | 14 Regular / 20 | 채팅, 리스트 설명 |
| Body/Medium Strong | 14 Medium / 20 | 리스트 제목, 버튼 |
| Label | 13 Medium / 18 | 칩, 작은 버튼, 카드 헤더 |
| Caption | 12 Regular / 16 | 시간, 탭 이름, 보조 정보 |

**색 사용 규칙 (패턴 B·C):** 코랄(brand)은 "행동"에만. 일정=파랑, 기념일=자주, 장소=초록, 할 일=보라를 카드·캘린더·지도 핀·하이라이트에 똑같이 쓴다.
구성원 색은 캘린더 점·칩 점·"올린 사람" 표시에만 쓴다.
