# 브랜치 운영 규칙

## 브랜치 역할

| 브랜치 | 역할 |
|---|---|
| main | 운영에 반영할 검증된 코드 |
| dev | 개발 변경을 통합하고 함께 검증하는 코드 |
| 작업 브랜치 | 하나의 이슈 또는 함께 검증할 변경 단위 |

일반 작업 브랜치는 최신 `dev`에서 생성하고 PR 대상도 `dev`로 지정합니다. `main`과 `dev`에는 직접 커밋하거나 강제 push하지 않습니다.

## 작업 브랜치 이름

```text
type/이슈번호-영역-짧은-설명
```

- type은 커밋 규칙의 `feat`, `fix`, `refactor`, `docs`, `test`, `build`, `ci`, `chore`를 사용합니다.
- 이름은 영문 소문자·숫자·하이픈으로 작성합니다.
- 업무 기능과 버그 수정은 관련 이슈 번호를 포함합니다.
- 초기 설정이나 작은 문서 작업처럼 별도 이슈가 없으면 번호를 생략할 수 있습니다.

```text
feat/12-user-service-signup
fix/27-wallet-service-funding-status
refactor/35-ledger-service-validation
chore/initial-setup
docs/local-development-guide
```

## 개발 흐름

1. `dev`를 최신 상태로 갱신합니다.
2. `dev`에서 작업 브랜치를 생성합니다.
3. 작업·관련 검증을 마친 뒤 `dev`를 대상으로 PR을 만듭니다.
4. 관련 서비스 담당자에게 리뷰를 요청합니다. 공통 계약·DB 권한·환경 변수를 바꾸면 영향받는 담당자를 포함합니다.
5. 적어도 한 명의 승인과 관련 검증 결과를 확인한 뒤 작업 PR을 Squash merge합니다.
6. 반영된 작업 브랜치는 삭제하고 다음 작업은 다시 최신 `dev`에서 시작합니다.

CI 도입 전에는 작성자가 실행한 빌드·테스트 명령과 결과를 PR에 기록합니다. CI 도입 후에는 자동 검사 결과도 확인합니다.

## 운영 반영

- 통합 검증한 `dev`를 `main`으로 반영하는 PR을 별도로 만듭니다.
- 장기 브랜치인 `dev`와 `main` 사이의 PR은 merge commit으로 반영해 공통 이력을 유지합니다.
- 운영 긴급 수정만 예외적으로 `main`에서 `hotfix/이슈번호-영역-설명`을 생성할 수 있습니다. 반영 후 같은 수정이 `dev`에도 포함되도록 merge PR을 만듭니다.
- 공유 브랜치의 이력 재작성이나 강제 push를 일반 작업 절차로 사용하지 않습니다.

## GitHub 기본 브랜치와 이슈 종료

개발 PR의 기본 대상을 맞추기 위해 GitHub 저장소의 기본 브랜치는 `dev`를 권장합니다. 운영 브랜치가 `main`인 것과 GitHub의 기본 브랜치가 무엇인지는 별도 설정입니다.

`Closes #12` 자동 종료는 PR이 GitHub의 기본 브랜치로 머지될 때만 동작합니다. 기본 브랜치를 `main`으로 유지한다면 `dev` 대상 PR은 자동 종료되지 않으므로 `Refs #12`로 연결하고 완료 시 수동 종료하는 등 팀의 종료 절차를 따릅니다.

이 문서는 운영 규칙이며 실제 저장소 생성·기본 브랜치 변경·보호 규칙 설정을 수행하지 않습니다.

근거: [GitHub의 브랜치 기반 협업 흐름](https://docs.github.com/en/get-started/using-github/github-flow), [이슈 자동 종료 조건](https://docs.github.com/en/issues/tracking-your-work-with-issues/using-issues/linking-a-pull-request-to-an-issue)
