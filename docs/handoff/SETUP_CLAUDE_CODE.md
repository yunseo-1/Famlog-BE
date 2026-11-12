# Claude Code 시작 가이드

## 1. 이 패키지를 저장소에 넣기

```bash
mkdir famlog && cd famlog && git init
# 압축을 푼 famlog-handoff/ 의 내용(CLAUDE.md, .mcp.json, docs/)을 저장소 루트에 복사
git add . && git commit -m "docs: 인수인계 패키지 추가"
```

## 2. MCP 연결 (Figma, Notion)

저장소 루트의 `.mcp.json`에 두 서버가 들어 있다. Claude Code를 저장소에서 처음 실행하면 프로젝트 MCP 서버 사용을 승인하라고 묻는다.
직접 추가하려면:

```bash
claude mcp add --transport http figma https://mcp.figma.com/mcp
claude mcp add --transport http notion https://mcp.notion.com/mcp
```

그다음 Claude Code 안에서 `/mcp` → 서버 선택 → **Authenticate**로 각각 로그인한다. 다시 `/mcp`를 실행해 둘 다 연결됐는지 확인한다.

> Figma는 Starter 요금제라 **MCP 호출 한도가 작다.** 화면 구조는 `docs/design/figma-map.md`와 캡처를 먼저 보고,
> 꼭 필요할 때만 노드 하나를 지정해 호출하게 한다.

## 3. 첫 작업 (Claude Code에 그대로 붙여 넣기)

### 3-1. 사양서 로컬 사본 만들기

```
Notion MCP로 페이지 416fc8bd-3e5e-8128-aab8-dc6d43bbd7f2(통합 사양서 v1.0)를 읽어서
docs/spec/integrated-spec.md 로 저장해줘. Notion 전용 태그(<callout>, <mention> 등)는 일반 마크다운으로 바꾸고,
내용은 줄이거나 바꾸지 마. 같은 방식으로 40dfc8bd-3e5e-81f6-b5e9-e8e2a5e9c7dc(ERD 초안)를 docs/spec/erd.md 로 저장해줘.
저장 후 docs/handoff/HANDOFF.md 4장의 "알려진 불일치"를 erd.md 맨 위에 경고로 붙여줘.
```

### 3-2. W1 개발 환경

```
CLAUDE.md와 docs/handoff/HANDOFF.md 6장(W1 체크리스트)을 읽고, 체크리스트를 순서대로 진행할 계획을 먼저 보여줘.
내가 승인하면 infra → backend → android → worker → CI 순서로 하나씩 만들고, 단계마다 빌드/테스트를 돌려 확인해줘.
```

### 3-3. 화면 구현할 때 (예: 캘린더 탭)

```
[기능] SCH-02 전체·구성원별 일정 조회 — 앱 화면 SCR-20
[참고] docs/spec/integrated-spec.md 4.3, 14.1(SCH-02) / docs/design/figma-map.md 의 "03 캘린더" / screenshots/03_calendar.png
[규칙] 색·간격·글꼴은 core/designsystem 의 FamColors/FamType/FamDimens 만 사용.
       Figma 컴포넌트 이름(Member Chip, App Bar, Bottom Nav …)과 같은 이름의 Composable 로 만든다.
[Figma] 로컬 문서로 부족한 부분만 노드 7:156 에 get_design_context 한 번 호출 가능.
[완료 조건] Compose Preview 로 화면 확인, 인수 기준 SCH-02 를 만족.
```

### 3-4. ⭐ 채팅 기반 자동 등록 (W8~9)

```
[기능] ARC-01~07 채팅 기반 자동 등록 — 사양서 7.4 전체
[순서] ① registration_candidates / candidate_evidence 마이그레이션
       ② 날짜·기간·오전오후 정규화 유틸 + 단위 테스트 30개(AR-9) 먼저
       ③ 규칙 필터 + 워커 LLM 추출(프롬프트는 worker/app/prompts/extract_v1.md)
       ④ 후보 API(approve/dismiss/undo, 낙관적 잠금) + WebSocket 이벤트
       ⑤ 앱: AI Suggestion Card / Resolved Chip / 수정 바텀시트 (figma-map.md 5장)
[완료 조건] 7.4.7 AR-1~10 전부
```

## 4. Figma 화면을 Claude Code가 볼 수 있게 하는 방법

1. **로컬 우선**: `docs/design/figma-map.md`(노드 ID·계층·속성) + `docs/design/screenshots/*.png`
2. **필요할 때만 Figma MCP**: 노드 URL 형식은
   `https://www.figma.com/design/1nECE5iK8TBDRSydCasO1j?node-id=7-156` (노드 `7:156` → URL에서는 `7-156`)
   이 URL을 주면 Claude Code가 해당 노드만 읽는다.
3. Figma 데스크톱 앱에서 프레임을 선택하고 "Copy link to selection"으로 링크를 복사해 줘도 된다.
