# CI·CD 가이드

GitHub Actions로 PR을 검사하고(CI), main에 반영된 커밋을 운영에 배포합니다(CD). 이 문서는 CI를 먼저 다루며 CD는 PR4의 4-4에서 추가합니다.

## CI(PR 검사)

PR이 `dev`·`main`을 대상으로 열리거나 갱신될 때와 `dev`·`main`에 반영될 때 돕니다. 같은 PR에 새 커밋이 오면 이전 실행을 멈춥니다.

| 워크플로 | 작업 | 검사 내용 |
|---|---|---|
| `ci.yml` | Gradle build | `./gradlew build`로 모든 서비스의 컴파일·테스트. 서비스 통합 테스트는 Testcontainers로 Docker를 씁니다 |
| `ci.yml` | Terraform | `infra/aws` 형식(`fmt -check`), 본 root와 State bootstrap root의 `validate`·`test`. State·AWS 자격 증명 없이 모의 provider로 검사합니다 |
| `ci.yml` | Python 3.9 | 인프라 스크립트 테스트. 3.9는 Data·앱 호스트(AL2023)의 `python3` 버전이며, DB 초기화 SQL을 실제 PostgreSQL로 검사합니다 |
| `ci.yml` | Python 3.14 | 같은 테스트를 인프라 담당자 PC 도구(비밀값 준비·이미지 게시)용 최신 버전으로 |
| `ci-container.yml` | Image and monitoring | 실행 이미지를 실제로 빌드해 실행 사용자·권한·시작 스크립트를 확인하고, 운영 Alloy·Loki 설정으로 로그 레이블을 확인합니다. 오래 걸리므로 Dockerfile·시작 스크립트·Gradle 빌드 설정·모니터링 설정이 바뀐 경우에만 돕니다 |

실패하면 Actions 화면의 해당 작업 로그를 봅니다. Gradle 테스트가 실패하면 테스트 보고서(`gradle-test-reports`)를 7일 동안 내려받을 수 있습니다. 로컬에서 같은 검사를 돌리는 명령은 각 작업의 `run` 줄과 같습니다.

### 실행 환경과 보안

- GitHub가 제공하는 Ubuntu 24.04 실행기를 씁니다. 실행마다 새 가상 머신이라 이전 실행이 남긴 것이 없고, 공개 저장소의 표준 실행기는 비용이 들지 않습니다. 자체 실행기는 외부 PR의 코드가 기계에 남을 수 있어 쓰지 않습니다.
- CI는 저장소 읽기 권한만 가지며 비밀값과 AWS 권한을 쓰지 않습니다. 외부 저장소에서 온 PR도 같은 조건으로 돕니다. 대상 저장소의 쓰기 권한·비밀값으로 실행되는 `pull_request_target`은 쓰지 않습니다.
- 액션은 태그가 아니라 커밋 SHA로 고정하고, 주석에 릴리스 태그를 적습니다. 태그는 옮겨질 수 있어 검토한 코드와 실행되는 코드가 달라질 수 있기 때문입니다(GitHub 보안 권고). 나온 지 2주가 지난 릴리스를 씁니다.
- 체크아웃한 저장소에 GitHub 토큰을 남기지 않습니다(`persist-credentials: false`).
- Gradle 캐시는 기본 브랜치(`dev`)의 실행에서만 저장하고 PR에서는 읽기만 합니다. 다른 PR이 캐시를 바꿔 이후 실행에 영향을 주지 못하게 하는 `setup-gradle` 기본 동작입니다. Gradle wrapper 파일이 공식 배포본인지도 함께 검사합니다.

### 액션 버전 올리기

액션을 올릴 때는 SHA와 주석의 태그를 함께 바꿉니다. SHA는 그 액션 저장소의 릴리스 태그가 가리키는 커밋인지 확인합니다.

```
curl -s https://api.github.com/repos/<소유자>/<저장소>/git/ref/tags/<태그>
```

결과의 `object.type`이 `tag`이면 그 `object.url`을 한 번 더 조회해 커밋 SHA를 얻습니다.

### 머지 조건으로 걸기(선택)

저장소 설정의 브랜치 보호 규칙에서 `Gradle build`, `Terraform`, `Python 3.9`, `Python 3.14`를 필수 검사로 지정할 수 있습니다. `Image and monitoring`은 관련 파일이 바뀐 PR에서만 돌기 때문에 필수로 지정하면 다른 PR이 머지되지 않습니다.
