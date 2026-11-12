# AWS Terraform 실행 가이드

Terraform 코드는 `infra/aws/`에서 관리합니다. Spring Boot Run과 별개이며 실제 리소스 생성은 Terraform의 `apply`로 실행합니다.

**State S3와 네트워크·SG(PR1), IAM·SSM·OIDC·ECS 실행 기반·RDS·Data EC2·Redis·Kafka(PR2), 앱 이미지·ECR·Task 정의·ALB·ACM·CloudWatch 경보·AWS 모니터링·운영 실행 설정(PR3), DB 암호 교체·main 이미지·ECS Service 첫 기동(PR4의 4-1·4-2)의 실제 적용과 검증을 완료**했습니다. 공개 진입점(Cloudflare 레코드 전환·원본 인증·Grafana 공개), CD, 운영 검증은 후속 단계(PR4의 4-3~4-5)입니다.

설계·구현 기준은 [Terraform 설계·구현 원칙](https://app.notion.com/p/413530b4fe5980cf9edbc37ec93b957e)에 모으고, 이 문서는 인프라 담당자의 실행·이전 절차만 다룹니다.

## 코드로 관리하는 이유

VPC·서브넷·라우팅·SG를 Terraform 코드로 관리하는 이유는 다음과 같습니다.

- **재현성:** 같은 구성과 명시한 입력으로 인프라를 재구축하고 환경별 차이를 관리합니다.
- **변경 검토:** Git 이력·PR·plan으로 변경 내용과 영향을 확인한 뒤 반영합니다.
- **실수 감소:** 입력 검증·구성 테스트·plan 검토로 잘못된 주소나 과도한 접근 허용을 적용 전에 발견할 가능성을 높입니다.
- **자동화:** 반복적인 설정 작업을 줄이고 향후 CI/CD에 연결할 기반을 만듭니다.

현재는 운영 환경 한 개를 대상으로 합니다. 코드만으로 환경의 완전한 동일성이나 오류 방지가 보장되지는 않으며 실제 AWS 검증을 함께 수행합니다.

근거: [Terraform 버전 관리](https://developer.hashicorp.com/terraform/intro/phases/adopt), [변경 계획 검토](https://developer.hashicorp.com/terraform/tutorials/cli/plan).

## 실행 구조

```text
infra/aws/
├── .terraform-version       # Terraform 1.17.1
├── versions.tf              # AWS Provider 6.72.0, S3 Backend 선언
├── providers.tf             # 서울 리전·허용 계정·공통 태그
├── variables.tf             # 계정·CIDR·AZ 검증, 단계별 활성화·이미지 digest·경보 이메일 입력
├── network.tf               # VPC·서브넷·IGW·NAT·라우팅
├── security.tf              # 역할별 SG·통신 규칙·규칙 소유권
├── runtime-contract.tf      # 서비스 이름·DB 계정·Secret ARN 공통 계약
├── iam-hosts.tf / iam-tasks.tf # 호스트·서비스별 최소 권한
├── github-oidc.tf            # production 환경 신뢰, 배포 권한은 기본 비활성
├── compute.tf               # ECS·빈 ASG·Capacity Provider
├── rds.tf                   # 사설 RDS·관리형 관리자 암호·복구 보호
├── data-ec2.tf / private-dns.tf # Data EC2·별도 EBS·공유 사설 DNS
├── data-config.tf           # Data 설정 묶음의 S3 전달·SSM 적용
├── registry.tf              # 앱 이미지 저장소(ECR)·앱 로그 그룹
├── task-definitions.tf      # 서비스 8개 Task 정의(이미지 digest·자원·비밀값·헬스 체크)
├── public-ingress.tf        # 공개 API 인증서(ACM)·ALB·HTTPS 리스너·Gateway Target Group
├── alarms.tf                # SNS 이메일·CloudWatch 경보
├── monitoring.tf            # 모니터링 설정 묶음·앱 호스트 수집기·CloudWatch 대시보드
├── host-observability.tf    # 호스트 메모리·디스크·초기화 로그
├── templates/               # EC2 기동·Data·모니터링 Compose·systemd·monitoring/ 수집 설정
├── scripts/                 # 설정 묶음 적용·비밀값 준비·이미지 게시·디스크·DB 초기화 도구
├── outputs.tf               # 식별자·실행 계약 출력, 비밀값 제외
├── .terraform.lock.hcl      # Provider 버전·패키지 체크섬
├── tests/                   # Terraform 모의 검증·Python 핵심 동작 검사
├── backend.hcl.example      # 개인 Backend 설정 예시
├── terraform.tfvars.example # 개인 입력 예시
└── bootstrap/               # State S3를 처음 만드는 독립 root
    ├── versions.tf          # Terraform·AWS Provider 버전
    ├── providers.tf         # 서울 리전·허용 계정·태그
    ├── variables.tf         # 대상 계정 검증
    ├── state.tf             # 버킷과 보안 설정
    ├── outputs.tf           # Backend 설정·최소 IAM 정책 출력
    ├── .terraform.lock.hcl  # bootstrap Provider 버전·체크섬
    ├── backend.tf           # State 이전 후 활성화한 S3 Backend
    ├── backend.tf.example   # 최초 생성 후 S3로 State를 옮길 때 활성화
    ├── terraform.tfvars.example
    └── tests/               # State 보안·IAM 범위 테스트 3개
```

Terraform은 현재 작업 디렉터리의 `.tf`를 함께 읽습니다. 하위 `bootstrap/`을 본 인프라에 자동 포함하지 않으므로 두 root는 각각 실행합니다. `.terraform.lock.hcl`은 Git에 포함하고 `.terraform/`, State·plan·개인 입력값은 제외합니다.

두 root 모두 실제 `hashicorp/aws` Provider를 사용합니다. `required_providers`는 사용할 플러그인·버전을 정하고 `provider "aws"`는 리전·허용 계정·태그를 설정합니다. 테스트의 `mock_provider "aws"`는 그 테스트에서 AWS API 동작을 대체합니다. 운영 설정에서 AWS Provider를 제거하는 구성이 아닙니다.

## State를 먼저 준비하는 이유

State는 코드의 리소스와 실제 AWS ID를 연결하는 기록입니다. 여러 작업자가 같은 인프라를 관리할 때 S3의 같은 State를 사용하고, lockfile로 동시에 쓰는 작업을 막습니다.

S3 Backend 초기화에는 이미 존재하는 버킷이 필요합니다. 따라서 다음 순서로 진행합니다.

1. `bootstrap`이 기본 local State로 State용 S3를 생성합니다.
2. S3 생성이 끝나면 `bootstrap`의 State도 S3로 옮깁니다.
3. 본 인프라 root는 처음부터 같은 버킷의 별도 State를 사용합니다.

```text
travelpay-tfstate-<실제 계정 ID>-ap-northeast-2
├── bootstrap/terraform.tfstate
├── bootstrap/terraform.tfstate.tflock
├── production/terraform.tfstate
└── production/terraform.tfstate.tflock
```

`.tflock`는 Terraform 작업 중 사용하는 잠금 파일입니다. State와 lockfile의 삭제 권한을 구분합니다. S3 lockfile을 사용하므로 DynamoDB 잠금 테이블은 만들지 않습니다.

## 적용한 보호 설정

| 항목 | 동작 |
|---|---|
| 계정 ID 형식 | 숫자 12자리만 허용 |
| 허용 계정 | Provider와 Backend가 지정한 계정으로만 동작하도록 설정 |
| 리전 | 서울 `ap-northeast-2` |
| 공개 접근 | Public Access Block 네 항목 활성화, 객체 ACL 비활성화 |
| 저장 암호화 | SSE-S3 `AES256` |
| 전송 | 버킷 정책에서 일반 주체의 HTTP 요청 거절 |
| 복구 | 버전 관리 활성화 |
| 버킷 삭제 | `prevent_destroy=true`, `force_destroy=false` |
| Backend 권한 | 두 State는 읽기·쓰기, 삭제는 두 lockfile에만 허용 |

이 보호 설정은 자격 증명 자체의 권한을 줄이지 않습니다. `state_backend_access_policy_json`은 최소 Backend IAM 정책을 **출력만** 하며 역할에 자동 연결하지 않습니다. 버킷을 처음 만드는 권한·이력 복구 권한은 일상적인 Backend 사용 권한과 별도로 준비합니다. 다른 정책으로 추가된 삭제 권한까지 이 출력이 제거하지는 않습니다.

## 실행 준비

Terraform 1.17.1과 AWS CLI 2.x를 사용하는 인프라 담당자용 절차입니다. 개인 OS 설치 방법은 이 가이드에서 다루지 않습니다. AWS Provider는 각 root의 `terraform init`이 내려받고 `.terraform.lock.hcl`로 버전·체크섬을 고정합니다.

AWS 접속은 기존 SSO 또는 CLI 프로필을 사용합니다. 아래 `travelpay`는 예시 프로필명이므로 실제 준비한 이름으로 바꿉니다.

```bash
export AWS_PROFILE=travelpay
aws sts get-caller-identity
```

출력된 Account와 사용할 계정 ID가 같은지 확인합니다. 액세스 키·비밀번호를 `.tf`, tfvars, Backend 설정이나 Git에 넣지 않습니다. `.env`는 Terraform이 자동으로 읽는 입력 파일이 아닙니다.

예시의 `123456789012`는 설명용 계정 ID이며 이 프로젝트의 실제 계정으로 확인한 값이 아닙니다. 계정 ID는 인증 비밀값과 구분하지만 실제 개인 입력·Backend 파일은 Git에서 제외합니다. 실행 권한은 SSO·IAM 자격 증명으로 확인하며 숫자 12자리 검사만으로 확보되는 것은 아닙니다.

## 기존 State에 연결

현재는 최초 생성·이전이 끝났으므로 이후 작업자는 이 절차부터 시작합니다. 개인 `terraform.tfvars`와 `backend.hcl`을 준비하고 이미 있는 파일은 덮어쓰지 않습니다. 입력 예시와 `infra/aws/backend.hcl.example`에서 실제 계정·버킷·AZ를 맞춥니다. 같은 버킷 안에서 bootstrap의 key는 `bootstrap/terraform.tfstate`, 본 root의 key는 `production/terraform.tfstate`입니다.

```bash
terraform -chdir=infra/aws/bootstrap init -backend-config=backend.hcl -lockfile=readonly
terraform -chdir=infra/aws init -backend-config=backend.hcl -lockfile=readonly
```

이후 변경은 현재 Backend에서 새 plan으로 검토합니다. 이미 적용했거나 State 이전 전에 저장한 plan은 재사용하지 않습니다.

다음 1·2절은 S3 Backend 선언이 없었던 **최초 생성·이전 기록**입니다. 기존 State를 사용하는 담당자가 반복 실행하는 절차가 아닙니다. 새 계정에서 처음 구성할 경우에는 기존 운영 State와 분리하고 S3 Backend 선언이 없는 초기 bootstrap 구성부터 준비합니다.

## 1. 최초 State S3 생성 계획

아래 명령은 저장소 루트에서 실행합니다.

```bash
cp infra/aws/bootstrap/terraform.tfvars.example infra/aws/bootstrap/terraform.tfvars
```

생성한 `terraform.tfvars`의 설명용 계정 ID를 실제 ID로 수정합니다. 최초에는 `bootstrap/backend.tf.example`을 복사하지 않습니다. 버킷 생성 전 S3 Backend를 활성화하면 초기화할 수 없습니다.

```bash
terraform -chdir=infra/aws/bootstrap init
terraform -chdir=infra/aws/bootstrap validate
terraform -chdir=infra/aws/bootstrap plan -out=bootstrap.tfplan
```

계정·리전·버킷 이름·생성 항목과 비용을 확인합니다. 계획에는 State S3와 그 보안 설정만 있어야 합니다. 이 단계에는 EC2·RDS·NAT·앱 생성이 포함되지 않습니다.

다음 명령은 실제 S3를 생성합니다. **해당 plan을 검토하고 생성 승인을 받은 뒤** 실행합니다.

```bash
terraform -chdir=infra/aws/bootstrap apply bootstrap.tfplan
```

## 2. 최초 Backend 설정 생성과 State 이전

먼저 local State를 읽을 수 있는 상태에서 두 설정을 출력합니다.

```bash
terraform -chdir=infra/aws/bootstrap output -raw bootstrap_backend_config > infra/aws/bootstrap/backend.hcl
terraform -chdir=infra/aws/bootstrap output -raw production_backend_config > infra/aws/backend.hcl
```

그다음 bootstrap의 S3 Backend를 활성화하고 State를 이전합니다.

```bash
cp infra/aws/bootstrap/backend.tf.example infra/aws/bootstrap/backend.tf
terraform -chdir=infra/aws/bootstrap init -migrate-state -backend-config=backend.hcl
```

이전 확인 질문에서 버킷·기존 local State를 확인한 뒤 진행합니다. 기존 S3에 이미 State가 있다면 덮어쓰지 말고 먼저 어느 작업자의 State인지 확인합니다. `-force-copy`나 `-lock=false`로 우회하지 않습니다. 이전 후 `terraform state list`와 S3의 State 객체·버전·잠금 동작을 확인합니다. local backup State에도 민감 정보가 포함될 수 있으므로 Git 제외 상태를 유지합니다.

첫 부트스트랩이 완료되어 활성화한 `bootstrap/backend.tf`도 코드에 포함합니다. 이후 작업자는 기존 버킷을 새로 생성하지 않고 자신의 `backend.hcl`로 초기화합니다.

## 3. 본 인프라 root 연결

```bash
cp infra/aws/terraform.tfvars.example infra/aws/terraform.tfvars
```

같은 실제 계정 ID와 논리 A/B에 사용할 서로 다른 AZ 이름을 입력합니다. 예시 AZ를 실제 계정에서 사용할 수 있다고 가정하지 말고 다음 조회 결과로 확인합니다.

```bash
aws ec2 describe-availability-zones --region ap-northeast-2 --filters Name=zone-type,Values=availability-zone --query "AvailabilityZones[?State=='available'].[ZoneName,ZoneId]" --output table
```

VPC 기본값은 `10.20.0.0/16`입니다. 생성 전에 기존 VPC/VPN과 주소 충돌을 확인합니다. 앞에서 생성한 `infra/aws/backend.hcl`로 초기화합니다.

```bash
terraform -chdir=infra/aws init -backend-config=backend.hcl
terraform -chdir=infra/aws validate
terraform -chdir=infra/aws plan -out=foundation.tfplan
```

본 root는 현재 네트워크와 SG를 계획합니다. 실제 apply에는 NAT·EIP 등의 과금 리소스 생성이 포함되므로 계정·리전·AZ·CIDR과 생성/삭제/교체를 확인한 뒤 승인된 계획을 적용합니다. 위 plan 명령은 이 문서의 코드 검증 과정에서 자동 실행하지 않습니다. 실제 생성·수집·롤백 검증을 끝내기 전까지 전체 이슈 #245를 완료로 표시하지 않습니다.

## 네트워크 동작

`vpc_cidr`에서 주소를 파생하고, 입력한 AZ의 논리 A/B에 서브넷을 배치합니다. 모든 서브넷은 명시적으로 라우팅 테이블에 연결합니다.

| 서브넷 | 기본 CIDR | 기본 외부 경로 |
|---|---|---|
| Public A/B | 10.20.0.0/24, 10.20.1.0/24 | IGW |
| App A/B | 10.20.10.0/24, 10.20.11.0/24 | Public A의 Zonal NAT 한 개 |
| Data A | 10.20.20.0/24 | 같은 NAT |
| Data B | 10.20.21.0/24 | 없음(local만) |

VPC의 원래 Main Route Table도 local만 유지합니다. Public 여부는 IGW 경로로 정하며 EC2 공인 IP 자동 할당은 모든 서브넷에서 비활성화합니다. 같은 VPC 내부 통신은 local 경로로 이루어지며 SG로 필요한 연결만 허용합니다.

네트워크 입력을 생성 후 변경하면 VPC·서브넷·NAT 등이 교체될 수 있으므로 단순 설정 변경으로 보고 재적용하지 않습니다. Main Route Table의 기존 경로를 정리하는 구성은 새 VPC를 전제로 하며, 기존 VPC에 적용/import할 때는 실제 경로를 먼저 검토합니다. AWS 생성 후에는 ALB 진입·사설 통신·NAT 송신·Data B 외부 경로 부재를 실제로 확인합니다.

## SG 동작

`security.tf`는 앱 8개와 ALB·App 호스트·Data·RDS·모니터링·로그 수집기의 **14개 역할**을 분리합니다. 현재 운영 설정·호출 코드에 필요한 기본 TCP 연결 38개를 하나의 맵에 정의하고, 연결마다 출발 SG의 송신과 도착 SG의 수신 규칙을 생성합니다. 응답 트래픽은 SG의 상태 추적이 허용하므로 역방향 신규 연결을 추가하지 않습니다.

| 경계 | 허용 범위 |
|---|---|
| 인터넷 진입 | 공식 Cloudflare IPv4 → ALB TCP 443 |
| 서비스 간 통신 | Gateway·업무·Eureka·Mock Bank의 실제 호출 방향과 포트 |
| 저장소 | DB를 쓰는 앱 → RDS 5432, User → 세션 Redis 16379, Exchange → 캐시 Redis 16380, Wallet → Kafka 9092 |
| 수집 | 모니터링 → 앱 메트릭, 로그 수집기 → Loki 등 필요한 내부 연결 |
| 외부 HTTPS | App 호스트·Data·Exchange·모니터링의 TCP 443 송신 |

관리 포트·Grafana는 인터넷에 공개하지 않고 SSH 포트도 열지 않습니다. 운영 접속은 후속 IAM·Compute 구현에서 SSM으로 연결합니다. Cloudflare 목록은 코드에 확인한 값을 기록하며, 변경 시 ALB 규칙과 Gateway 신뢰 설정을 함께 검토합니다.

SG 본체와 개별 규칙을 분리해 상호 참조에 따른 생성 순환을 피합니다. `aws_vpc_security_group_rules_exclusive`가 선언한 규칙 전체를 관리하고 VPC 기본 SG는 닫습니다. 다른 도구·수동 규칙을 같은 SG에 섞으면 다음 apply에서 제거될 수 있으므로 보존할 변경을 먼저 코드에 반영합니다.

DB 계정·스키마 초기화 때만 `enable_database_bootstrap_access=true`로 Data → RDS 5432와 해당 RDS 관리자 Secret 조회 권한, 앱 DB 암호 조회를 엽니다. 완료 후 `false`로 되돌려 plan/apply합니다. 평상시 Data는 Redis 암호 두 개와 Grafana 관리자 암호만 조회합니다. EC2에는 역할별 SG를 연결하며 앱 Task 연결과 실제 허용·차단 검증은 배치 단계에서 확인합니다.

## 실행 기반 적용 순서

같은 PR의 코드도 순서대로 적용합니다. 아래 명령은 저장한 계획과 계정·비용을 검토하고 생성이 승인된 경우에만 실행합니다. `travelpay` 프로필과 `123456789012`는 실제 값으로 바꿉니다.

활성화 옵션을 앱 기반과 데이터 기반으로 분리했습니다. 이미 켠 앞 단계의 옵션은 유지하고 해당 단계만 추가로 켭니다. 초기 작성 코드가 한 PR에 있어도 실제 적용·검증은 한 단계씩 마칩니다.

| 단계 | 설정 | 적용 후 확인 |
|---|---|---|
| 4 · IAM·SSM·OIDC | 두 기반 옵션 `false`, `app_host_count=0` | 역할·정책 범위, 임시 권한 비활성, 필요한 SSM 경로 |
| 5 · ECS·앱 호스트 | `enable_app_foundation=true`, `enable_data_foundation=false`, ECS AMI 고정 | 빈 ASG·Capacity Provider 연결 → 새 plan으로 2대 기동 → SSM·ECS Agent·등록 메모리 |
| 6 · RDS·Data | 앞 단계 설정과 호스트 수를 유지하고 `enable_data_foundation=true`, Data AMI 고정 | 사설 RDS·TLS·디스크 마운트·Redis 인증·Kafka 응답·재기동 보존 |

각 단계에서 **새 plan → 검토 → 승인된 apply → 실제 확인 → 변경 없음 확인** 순서로 진행합니다. 단계 5에서 ASG와 Capacity Provider를 처음 만들 때는 호스트를 0대로 둡니다. AWS 연결 확인 후 `app_host_count=2`의 새 plan을 만들고 검사 도구를 통과시켜 적용합니다. EC2의 running 상태만으로 기동 완료를 판단하지 않습니다.

아래는 최초 4단계의 예시입니다. 이후에는 단계에 맞는 입력과 별도의 plan 파일을 사용합니다. `check-runtime-plan.py`는 ASG 초기 연결 순서만 검사하므로 전체 변경 검토를 대신하지 않습니다.

```bash
terraform -chdir=infra/aws plan -out=stage-4.tfplan
python3 infra/aws/scripts/check-runtime-plan.py infra/aws/stage-4.tfplan --profile travelpay
python3 infra/aws/scripts/prepare-parameters.py infra/aws/stage-4.tfplan --profile travelpay --account 123456789012
```

비밀값 준비 도구는 기본적으로 미리보기만 실행합니다. 승인 후 같은 명령에 `--apply`를 추가하면 DB 6개·Redis 2개·Grafana 관리자 암호를 SecureString으로 만듭니다. 인프라 담당자의 Linux에서 비밀값을 익명 메모리 파일로 AWS CLI에 전달하며 기존 SecureString을 덮어쓰지 않습니다. AWS CLI 기록(`cli_history`)이 켜져 있으면 요청 내용이 로컬 기록에 남으므로 쓰기를 거절합니다.

- Wallet 계좌번호 키(무작위 32바이트의 Base64)는 `--apply --create-wallet-key`를 줄 때만 만듭니다. 데이터가 생긴 뒤 바꾸거나, 지워진 뒤 새로 만들면 저장된 계좌번호를 풀 수 없으므로 Wallet 데이터가 생기기 전 처음 한 번만 씁니다.
- 수출입은행에서 발급받은 환율 API 키는 `--apply --external`을 줄 때만 입력받습니다. 입력을 숨길 수 있는 터미널에서 직접 실행해야 하며, 파이프 입력처럼 입력이 화면에 보일 수 있으면 거절합니다. 키를 명령 인자·파일·채팅에 남기지 않습니다.
- DB 앱 계정 암호 6개는 `--apply --rotate-database-passwords`를 줄 때만 새 임의 값으로 바꿉니다. SSM 값만 바뀌므로 아래 DB 작업 창에서 초기화 스크립트를 다시 실행해야 DB에 반영되고, 그 전에 시작한 Task는 DB 로그인에 실패합니다. 새 값은 출력하지 않으며 사람이 알 필요가 없습니다. Task는 시작할 때, 초기화 스크립트는 실행할 때 SSM에서 읽습니다.

```bash
terraform -chdir=infra/aws apply stage-4.tfplan
```

4~6단계는 이미 적용됐으므로 위 최초 생성 절차를 그대로 반복하지 않습니다. 실제 운영 입력은 Git에서 제외한 개인 파일에 유지하고 매번 최신 State로 새 plan을 만듭니다. 다음 단계는 앞 단계의 옵션·호스트 수를 유지합니다. 분리 전 또는 적용 전 State로 만든 plan을 재사용하지 않습니다.

DB 계정 초기화는 별도 작업입니다. `enable_database_bootstrap_access=true`의 plan/apply 후 SSM으로 Data에 접속해 아래 명령을 실행합니다. 암호는 출력하지 않으며 DB 역할·스키마를 한 트랜잭션으로 준비합니다. 앱 계정 암호는 평문 대신 SCRAM-SHA-256 값으로 보냅니다. RDS는 `pg_stat_statements`를 기본으로 로드하므로 평문을 보내면 쿼리 통계에 원문이 남습니다. Flyway는 이후 앱 기동 시 실행합니다.

```bash
sudo python3 /opt/travelpay/data/bootstrap-database.py
```

초기화 스크립트는 실행할 때마다 SSM의 현재 암호로 앱 계정 암호를 다시 설정하고, 끝에 계정 6개가 각자 SSM 암호로 로그인해 자기 스키마를 쓰는지 확인합니다. 이름을 바꾸기 전에 쓰던 계정(`RETIRED_ROLES`, 현재 `bank_app`)은 새 계정이 스키마를 넘겨받은 뒤 같은 트랜잭션에서 지웁니다. 옛 계정에 테이블 같은 객체가 남아 있으면 데이터를 지우지 않고 전체를 되돌립니다. 실패하면 psql의 오류 줄을 보여 주되, 실행한 문장과 SCRAM 값은 빼고 보여 줍니다.

`--reset-statement-stats`를 붙이면 끝에 `pg_stat_statements` 통계를 비웁니다.
- 통계 수집은 미리 불러온 라이브러리가 하지만, 비우는 함수는 확장을 만들어야 생깁니다. 그래서 확장이 없으면 먼저 만듭니다(통계 조회·비우기 함수만 추가).
- RDS 관리자 계정이 이 확장을 만들거나 함수를 실행할 수 있는지는 AWS 문서로 확인하지 못했지만, 2026-11-12 실제 RDS에서 성공했습니다. 거절되면 원인을 오류로 보여 줍니다.
- 이 단계가 실패해도 암호는 이미 바뀐 뒤라, 통계와 백업에 남은 옛 평문 암호로는 로그인할 수 없습니다.

PR2 때 평문으로 보낸 DB 암호를 교체하는 작업 창은 아래 순서를 지킵니다. 교체보다 초기화를 먼저 하면 옛 암호가 그대로 유효한 채 통계만 지워집니다.

1. `enable_database_bootstrap_access=true`로 plan을 만들어 저장하고 검토합니다.
2. 그 plan으로 `prepare-parameters.py <plan> --profile <프로필> --account <계정 ID> --apply --rotate-database-passwords`를 실행하고, DB 암호 6개가 모두 `rotated`로 출력되는지 확인합니다. 이 도구는 plan에서 비밀값 목록만 읽습니다.
3. 저장한 plan을 적용합니다. 새 설정 묶음(초기화 스크립트·계정 이름)도 이때 Data 호스트에 반영됩니다.
4. Data 호스트에서 초기화 스크립트를 `--reset-statement-stats`와 함께 실행하고, 계정 6개 로그인 확인 문구를 봅니다.
5. 옵션을 `false`로 되돌려 plan/apply하고, Data → RDS 연결과 DB 암호 조회가 거절되는지 확인합니다.

2와 4 사이에는 SSM 값과 DB 암호가 다르므로, 그동안 앱 Task를 시작하지 않습니다(첫 기동 전에 하는 이유).

```bash
sudo python3 /opt/travelpay/data/bootstrap-database.py --reset-statement-stats
```

초기화 성공과 앱 계정의 자기 스키마 접근·타 스키마 차단을 확인한 뒤 옵션을 `false`로 되돌려 새 plan/apply합니다. 공용 namespace는 Kafka 주소 때문에 먼저 만들며, 후속 Eureka·Mock Bank도 같은 `travelpay.internal`을 재사용합니다.

초기화 명령은 반드시 `aws ssm start-session`으로 접속한 그 창 안(Data 호스트)에서 실행합니다. 다른 터미널에서 실행하면 운영자 PC에서 실행되어 아무것도 바뀌지 않습니다.

**운영 DB 직접 접속.** RDS는 사설망에만 있고 5432는 앱 Task의 SG에서만 받으므로, 운영자는 위 작업 창을 연 동안에만 Data 호스트를 거쳐 접속합니다. 암호는 외우거나 적어 두지 않고 쓸 때마다 SSM에서 꺼냅니다.

| 서비스 | 스키마 | DB 계정 | 암호(SSM) |
|---|---|---|---|
| user | `user_service` | `user_app` | `/travelpay/production/USER_DB_PASSWORD` |
| wallet | `wallet_service` | `wallet_app` | `/travelpay/production/WALLET_DB_PASSWORD` |
| payment | `payment_service` | `payment_app` | `/travelpay/production/PAYMENT_DB_PASSWORD` |
| exchange | `exchange_service` | `exchange_app` | `/travelpay/production/EXCHANGE_DB_PASSWORD` |
| ledger | `ledger_service` | `ledger_app` | `/travelpay/production/LEDGER_DB_PASSWORD` |
| mock-bank | `mock_bank` | `mock_bank_app` | `/travelpay/production/MOCK_BANK_DB_PASSWORD` |

1. `enable_database_bootstrap_access=true`로 plan을 만들어 검토한 뒤 적용합니다.
2. `terraform -chdir=infra/aws output data_runtime`에서 `instance_id`와 `database_host`를 확인합니다.
3. 터널을 엽니다(접속하는 동안 켜 둠). Windows PowerShell은 줄 바꿈 기호 `\` 대신 백틱(`` ` ``)을 씁니다.

   ```bash
   aws ssm start-session --profile <프로필> --region ap-northeast-2 --target <instance_id> \
     --document-name AWS-StartPortForwardingSessionToRemoteHost \
     --parameters "host=<database_host>,portNumber=5432,localPortNumber=15432"
   ```

4. 다른 창에서 암호를 클립보드로 꺼냅니다(Linux Wayland `| wl-copy`, Windows `| Set-Clipboard`).

   ```bash
   aws ssm get-parameter --profile <프로필> --region ap-northeast-2 --name /travelpay/production/USER_DB_PASSWORD \
     --with-decryption --query Parameter.Value --output text | wl-copy
   ```

5. DB 도구에서 `localhost:15432`, DB `travelpay`, 표의 계정, SSL `require`로 접속합니다. 터널은 주소가 `localhost`라 인증서 이름 검사(`verify-full`)는 맞지 않습니다.
6. 끝나면 터널을 닫고 옵션을 `false`로 되돌려 plan/apply합니다.

관리 작업에만 RDS 관리자 계정을 씁니다. 암호는 Secrets Manager(`master_secret_arn`)에 있고 RDS가 관리하므로 쓸 때마다 꺼냅니다. 이 경로를 쓸 수 있는 AWS 자격 증명은 인프라 관리자 프로필뿐이며, 팀원에게 열려면 포트 포워딩과 자기 서비스 암호 하나만 허용하는 별도 IAM 권한을 만들어야 합니다.

`data.travelpay.internal`은 Cloud Map의 서비스·인스턴스 등록으로 생성합니다. Cloud Map 소유 영역에 Route 53 레코드를 직접 쓰는 방식은 AWS에서 거절됩니다. RDS는 비밀번호 DDL이 오류의 문맥에 남지 않도록 `log_statement=none`, `log_min_error_statement=panic`, `log_error_verbosity=terse`를 사용합니다. 오류의 상세·문맥 대신 기본 오류와 애플리케이션의 안전한 진단 정보를 확인합니다.

현재 확인한 TLS는 **Data EC2 → RDS**이며 RDS 서버 인증서와 AWS CA 번들을 사용합니다. 클라이언트의 `verify-full` 접속과 평문 접속 거절을 실제로 확인했습니다. ACM 인증서를 사용하는 **브라우저 → ALB HTTPS**와 구분합니다. `rds.force_ssl`은 AWS 제어면의 설정과 실제 접속 거절로 검증하며, 이 환경에서는 PostgreSQL의 `current_setting()`에 같은 이름이 노출되지 않았습니다.

Data는 16GiB root, Kafka 30GiB, 모니터링 20GiB의 별도 암호화 gp3를 사용합니다. Kafka 3072MiB·Redis 두 개 512MiB의 컨테이너 상한을 두며, 모니터링을 포함한 메모리·디스크 여유는 'AWS 모니터링(Prometheus·Loki·Grafana·Alloy)' 절에 정리합니다. 두 EBS와 RDS에는 `prevent_destroy`가 있으며, RDS에는 추가 삭제 보호와 최종 스냅샷을 설정합니다. Data EC2 인스턴스에는 고유 데이터가 없으므로 삭제 보호를 두지 않습니다. 교체가 필요하면 plan의 `must be replaced`와 디스크 재부착을 검토해 승인합니다. 삭제·교체는 데이터 보존과 중단 영향을 검토한 별도 작업입니다. 반복 종료 시 최종 스냅샷 이름 충돌도 확인합니다.

Data 호스트의 스크립트·Compose·CloudWatch Agent 설정은 user data가 아니라 설정 묶음으로 전달합니다. `data-config.tf`가 묶음을 비공개 S3 객체로 올리고, SSM association이 Data 호스트에서 묶음을 받아 Terraform이 계산한 SHA-256과 비교한 뒤 `apply-data-bundle.py`로 설치합니다. 실행 중인 서비스는 `systemctl reload-or-restart`의 `ExecReload`로 Compose 정의가 바뀐 컨테이너만 다시 만듭니다. Provider는 association을 처음 만들 때만 성공을 기다리므로, 묶음·SSM 문서·인스턴스가 바뀌면 association을 다시 만들어 호스트의 적용 실패가 `terraform apply` 실패로 드러나게 합니다. user data는 패키지 설치만 하는 고정 스크립트이므로 이 파일이나 AMI를 바꿀 때만 인스턴스가 교체됩니다.

- Compose 정의가 아닌 파일만 바뀌는 경우(예: Redis 암호 파일)는 컨테이너를 다시 만들지 않으므로 별도 재시작이 필요합니다.
- Redis는 로컬 정책처럼 영속 저장하지 않아 인스턴스 교체나 Redis 재시작 시 세션·견적이 사라집니다. Kafka EBS와 DB의 영속 데이터와 구분합니다.
- 7일 운영 범위에서 바꾸지 않은 알려진 한계: 데이터 볼륨은 설정한 크기와 정확히 같아야 마운트하므로 확장하려면 스크립트 변경이 필요합니다. fstab에 `nofail`이 없어 볼륨 없이 부팅하면 응급 모드로 들어갑니다. 볼륨 분리·확장 작업을 계획하지 않으므로 이번에는 변경하지 않습니다.

## 앱 이미지와 공개 인증서 준비

앱 기반을 켜면 서비스별 ECR 저장소 8개, 앱 로그 그룹 8개(7일 보관), Eureka·Mock Bank의 Cloud Map 이름, `api.travelpay.store` 공개 인증서를 만듭니다. 저장소·로그 그룹 이름은 실행 역할과 배포 역할의 권한 ARN과 같습니다. ECR은 태그를 덮어쓸 수 없고(Git SHA 태그), 푸시할 때 이미지를 스캔하며, 서비스별 최근 10개만 남깁니다. 지워진 이미지는 Task 재시작·롤백에 쓸 수 없으므로 보관 개수는 7일 동안의 배포 횟수보다 넉넉하게 둡니다. 보관 개수는 ECR 항목 수로 세므로 이미지는 빌드 증명(provenance) 없이 만듭니다. BuildKit 기본값처럼 증명을 붙이면 푸시 1번에 index·이미지·증명 3개 항목이 생겨 실제로는 배포 3~4번분만 남고, 스캔 결과도 태그가 아닌 하위 이미지 digest에 붙습니다(2026-11-11 첫 게시에서 확인).

Cloud Map 이름에는 ECS가 관리하는 custom 헬스 체크를 씁니다. AWS ECS 문서는 사설 namespace에 이 방식을 권장하며, 컨테이너 헬스 체크를 통과한 Task만 DNS에 남깁니다. Provider가 대체로 안내하는 `health_check_config`는 Route 53 헬스 체크라 공개 namespace에만 맞습니다.

이미지는 `deploy/docker/Dockerfile` 하나로 모든 서비스를 만듭니다. 빌드는 JDK 21, 실행은 JRE 21이며 기본 이미지는 digest로 고정합니다. 실행 사용자는 root가 아니고(uid 10001), 앱 파일은 읽기만 할 수 있습니다. JDBC `verify-full`에 쓰는 RDS CA 번들은 체크섬을 확인해 넣습니다. `.dockerignore`는 빌드에 필요한 Gradle 파일과 서비스 소스만 보내며 `.env`·인프라 State·문서·부하 시험 파일은 보내지 않습니다.

시작 스크립트(`deploy/docker/entrypoint.sh`)는 Eureka에 등록하는 서비스에서 종료 신호를 받으면 먼저 Eureka의 상태를 `OUT_OF_SERVICE`로 바꾸고 기다린 뒤 Java를 종료합니다. Spring은 웹 서버를 먼저 닫고 Eureka 등록 해제를 맨 나중에 하므로(종료 순서 값 2147482623과 0), 이 순서를 앞당기지 않으면 다른 서비스가 캐시된 주소로 종료 중인 Task에 요청을 보냅니다. Task IP는 ECS 메타데이터에서 읽어 Eureka 인스턴스 ID로 고정합니다. 인자를 주면 앱 대신 그 명령을 실행하므로 시험 실행에 씁니다.

이미지는 커밋된 소스로만 게시합니다. 기본 실행은 미리보기이고 `--push`를 주면 증명 없이 빌드해 푸시합니다. 같은 Git SHA 태그가 이미 있으면 다시 빌드하지 않고 그 digest를 씁니다. 결과는 Git에서 제외한 `infra/aws/application-images.auto.tfvars.json`에 기록합니다.

```bash
python3 infra/aws/scripts/publish-images.py --profile <프로필>
python3 infra/aws/scripts/publish-images.py --profile <프로필> --push
```

공개 인증서는 DNS로 검증합니다. `terraform -chdir=infra/aws output api_certificate`의 검증 레코드를 Cloudflare DNS에 CNAME으로 추가하고 Proxy status는 **DNS only**로 둡니다. 프록시를 켜면 ACM이 레코드를 확인하지 못합니다. 발급(ISSUED) 뒤에도 자동 갱신에 쓰이므로 레코드를 지우지 않습니다. 발급 전 인증서로는 HTTPS 리스너를 만들 수 없으므로 ALB는 발급을 확인한 뒤 적용합니다.

## 앱 실행 정의·공개 진입점·경보

이미지 게시와 인증서 발급을 확인한 뒤 `enable_app_runtime=true`로 Task 정의 8개, ALB, 경보를 만듭니다. 이 옵션은 앱·데이터 기반이 켜져 있고 `application_images`에 8개 서비스의 digest가 모두 있어야 통과합니다. 태그 주소나 다른 저장소의 이미지는 입력 검증에서 거절합니다. 이 옵션만으로는 Task를 상시 실행하지 않습니다. ECS Service는 `enable_app_services`로 따로 만듭니다(아래 앱 상시 실행).

Task 정의의 설정은 두 곳에서 옵니다. 비밀이 아닌 값(DB 주소·계정 이름, Redis·Kafka·Eureka·Mock Bank 주소)은 `task-definitions.tf`의 환경 변수입니다. Redis·Kafka·Eureka·Mock Bank 주소는 사설 DNS 이름과 SG가 허용한 포트에서 만들고, DB는 인증서 이름과 맞아야 `verify-full`이 통과하므로 RDS 엔드포인트 이름을 그대로 씁니다. 서비스 이름·포트는 각 서비스의 `application-prod.yml`과 테스트로 대조합니다. 비밀값은 Task 정의에 Parameter Store ARN만 적고, Task 시작 때 서비스별 실행 역할이 꺼내 주입합니다. 각 서비스는 자기 비밀값만 받습니다. 이미지에는 설정·비밀값이 들어가지 않습니다.

| 설정 | 값 | 이유 |
|---|---|---|
| 설정 파일 | `SPRING_CONFIG_NAME=application-prod`, `SPRING_PROFILES_ACTIVE=prod` | 로컬 전용 파일 로그를 읽지 않고, `@Profile("!prod")`인 Mock Bank 장애 주입을 끕니다 |
| CPU | 컨테이너 단위 예약(256·512), Task 단위 상한 없음 | Task 단위 CPU는 넘을 수 없는 상한이라 0.25 vCPU에서 user-service 기동이 114초 걸렸습니다(2 vCPU 13초). 컨테이너 단위 값은 같은 크기로 배치를 예약하고 경합 때 비율을 보장하며 남는 CPU는 더 씁니다 |
| 메모리·힙 | Task 메모리는 상한, 힙은 그 절반, OOM 시 종료 | 메타스페이스·스레드·직접 버퍼 몫을 남기고, 메모리 부족 상태로 남지 않고 ECS가 다시 띄우게 합니다 |
| health 확인 | 관리 포트의 liveness, 시작 후 180초 유예 | 여러 Task가 한꺼번에 시작해 예약 몫만 받는 최악의 기동 시간(114초)보다 길게 둡니다. liveness는 DB·Redis 상태를 보지 않습니다 |
| DB | `sslmode=verify-full`, 이미지의 RDS CA, Hikari 최대 5·최소 1 | DB 서비스 6개가 롤링 중 2배가 되어도 60개로 `max_connections=100` 안에 둡니다 |
| Eureka | 목록 조회 5초, LoadBalancer 캐시 5초, 서버 응답 캐시 5초 | 세 캐시의 합(15초)이 시작 스크립트의 종료 전 대기 20초보다 짧고, 대기·종료 처리 합(최대 55초)이 `stopTimeout` 60초 안에 끝납니다(테스트로 확인) |
| 지표 | `SPRING_APPLICATION_JSON`의 HTTP histogram | `application.yml`에만 있는 설정이며 점이 든 지표 이름은 환경 변수 이름으로 쓸 수 없습니다 |
| 파일시스템 | 루트 읽기 전용, `/tmp`만 Task 볼륨 | 앱 파일 변조를 막습니다. Tomcat·Netty 임시 파일은 Task와 함께 지워집니다 |

ALB는 Public A/B에 두고 기존 SG가 Cloudflare 주소의 443만 허용합니다. 리스너는 HTTPS만 열고 TLS 1.2·1.3 정책(`ELBSecurityPolicy-TLS13-1-2-2021-06`)과 발급 확인을 거친 인증서를 씁니다. Host가 `api.travelpay.store`인 요청만 Gateway Target Group(IP, 8080)으로 보내고 나머지는 404입니다. Target 상태는 관리 포트 18080의 readiness로 판단하며 등록 해제 대기는 30초입니다. Cloudflare는 마지막 요청 뒤 최대 900초 동안 원본 연결을 다시 쓰므로(Proxy Idle Timeout) ALB 유휴 제한은 1000초로 길게 두어 ALB가 먼저 끊는 순간의 520 오류를 피합니다. Cloudflare의 API 레코드를 ALB로 바꾸는 작업은 PR4의 4-3에서 합니다.

경보는 SNS 주제 하나로 보내며 이메일 구독은 받은 메일의 확인 링크를 눌러야 활성화됩니다. 경보 이메일은 Git에서 제외한 개인 입력 파일의 `alert_email`에 둡니다.

상태 값은 일시적으로 튀는 값이나 새 지표의 첫 공백에 울리지 않도록 15분(5분 3번) 연속을 봅니다.

| 대상 | 경보 조건 | 지표가 없을 때 |
|---|---|---|
| RDS | CPU 80% 초과, 여유 메모리 512MiB 미만, 여유 저장 공간 4GiB 미만, 연결 80개 초과가 각각 15분 | 판단 보류 |
| Data 호스트 | 메모리 80% 초과, `/`·Kafka·모니터링 디스크 75% 초과가 각각 15분 | 경보(호스트·Agent 중단) |
| 앱 호스트 | ASG 전체 중 최대 메모리 80% 초과, 루트 디스크 75% 초과가 각각 15분 | 경보 |
| ALB | ALB 5xx 또는 Gateway 5xx가 5분에 5건 이상 | 정상(요청 없음) |

앱 호스트는 ASG가 교체해 인스턴스 ID가 바뀌므로 CloudWatch Agent가 ASG 이름 차원으로 합친 지표를 경보에 씁니다. 이 설정은 앱 호스트 user data에 있으므로 적용하면 ASG instance refresh로 두 대가 한 대씩 교체됩니다. Data 호스트 Agent 설정은 바꾸지 않아 설정 묶음이 다시 적용되지 않습니다.

이 단계에서 확인한 앱 설정 사실과 남은 일입니다.

- Ledger의 Hikari `connection-timeout`·`validation-timeout`은 `application.yml`에만 있어 운영에서는 기본값(30초·5초)이 적용됩니다. 의도인지 서비스 담당자에게 확인합니다.
- Exchange는 환율 API 키(`KOREAEXIM_API_KEY`)가 없으면 시작하지 않습니다. 배포 전 비밀값 준비 도구의 `--external`로 저장합니다.
- ALB 접근 로그는 켜지 않았습니다. Cloudflare만 접근할 수 있고 요청 기록은 Gateway 로그와 ALB 지표로 확인합니다.
- SG의 Cloudflare 주소 허용은 원본을 인증하지 않습니다. 다른 Cloudflare 사용자 영역을 거친 요청도 ALB에 닿을 수 있어 이 영역의 WAF·속도 제한을 우회할 수 있습니다. Host 규칙이 다른 이름을 404로 막지만 Host를 바꾼 요청까지 막지는 못합니다. PR4의 4-3에서 Cloudflare가 붙이는 비밀 헤더(`Origin-Auth`)를 ALB 규칙이 확인하도록 해 막습니다.
- CD(PR4의 4-4)가 새 리비전으로 Service를 갱신하면, 이미지가 같아도 Service의 Task 정의 ARN이 Terraform이 아는 리비전과 달라집니다. 그대로 두면 다음 apply가 Terraform의 리비전으로 되돌리며 다시 배포합니다. 그래서 4-4에서 Service의 Task 정의는 CD가 소유하게 하고(`ignore_changes`), Terraform 설정을 바꾸기 전에는 운영 중인 digest를 `application_images`에 맞춥니다. CD를 켜기 전까지는 Terraform만 Service를 갱신합니다.

## 앱 상시 실행(ECS Service)

Task 정의·ALB와 앱 호스트 2대를 확인한 뒤 `enable_app_services=true`로 8개 ECS Service를 만듭니다(`app-services.tf`). 이 옵션은 `enable_app_runtime`과 `app_host_count=2`가 있어야 통과합니다. false로 되돌리면 Service와 실행 중인 Task를 지웁니다.

| 설정 | 값 | 이유 |
|---|---|---|
| Task 수 | 서비스마다 1개 | 앱 호스트 2대(등록 메모리 각 6759MiB)에 8개 Task 메모리 합계 8448MiB가 들어가고, 롤링 중 가장 큰 Task(1536MiB) 하나가 더 떠도 남습니다. 호스트가 한 대만 남으면 8개가 다 들어가지 않아, ASG가 호스트를 교체할 때까지 일부 Task가 대기합니다 |
| 배포 | 최소 100%·최대 200% 롤링, 실패 시 자동 롤백(circuit breaker) | 새 Task가 정상이 된 뒤 이전 Task를 내려 배포 중에도 1개를 유지합니다. 스케줄러가 있는 Payment·Wallet·Exchange는 두 Task에서 함께 돌아도 안전하다고 담당자가 확인했고, 선점 쿼리·유니크 제약을 코드로 대조했습니다(2026-11-12). 모든 DB 서비스가 함께 롤링해도 커넥션 풀 상한 합계는 60입니다 |
| 배치 | 앱 호스트 Capacity Provider, App A/B 서브넷, 공인 IP 없음, 서비스별 SG | Capacity Provider는 클러스터 기본값과 같지만, 생략하면 AWS가 기본값을 Service에 기록해 다음 plan부터 갱신이 거절됩니다(provider 6.72.0 소스) |
| 서비스 찾기 | Eureka·Mock Bank만 Cloud Map(`eureka`·`mock-bank.travelpay.internal`), 나머지는 Eureka | 다른 앱이 고정 이름으로 찾는 두 서비스입니다. 컨테이너 헬스 체크를 통과한 Task가 있으면 그 Task만 응답하고, 모두 비정상이면 비정상 Task도 응답합니다(Route 53 다중 값 응답 규칙) |
| 공개 진입점 | Gateway만 ALB Target(IP, 8080), 기동 유예 180초 | 기동 중 ALB 상태 확인을 보지 않습니다. 컨테이너 헬스 체크는 Task 정의의 시작 유예(180초)가 같은 역할을 하므로 다른 서비스에는 두지 않습니다 |
| apply | 안정 상태까지 대기(`wait_for_steady_state`) | 배포가 실패하거나 되돌려지면 apply가 그 이유와 함께 실패합니다(provider 6.72.0 소스) |

기동 순서는 두지 않습니다.

- 호스트 교체나 장애 복구 때도 Task는 순서 없이 시작하므로 앱이 이를 견뎌야 합니다.
- Eureka 클라이언트는 서버에 닿지 못하면 30초마다 등록을 다시 시도합니다. 서버가 모르는 인스턴스라고 답하면(heartbeat 404) 다시 등록합니다(eureka-client 2.0.6 소스).
- Eureka·Mock Bank 이름이 아직 등록되지 않았을 때의 "이름 없음" 응답은 이 영역의 SOA TTL(15초)만큼만 캐시됩니다.

Eureka 재시작·재배포 때는 짧은 공백이 생길 수 있습니다.

- eureka-server는 단독 서버라 받아 올 목록이 없습니다. Spring Cloud는 이 경우에도 조회를 바로 허용하므로, 새 Eureka는 시작 직후부터 비었거나 일부만 찬 목록으로 답합니다(`InstanceRegistry.openForTraffic`, Spring Cloud Netflix 5.0.3 소스).
- 클라이언트는 200 응답이면 받은 목록으로 바꿉니다. 그래서 각 서비스가 다음 heartbeat(최대 30초)에 다시 등록할 때까지 Gateway가 일부 서비스를 찾지 못해 503을 낼 수 있습니다.
- 서버에 닿지 못한 동안에는 받아 둔 목록을 그대로 씁니다.
- 롤링 중에는 이전·새 Eureka가 같은 이름으로 잠시 함께 응답해 목록이 갈릴 수도 있습니다.

이 시간은 4-5의 Eureka 재시작·재배포 시험에서 측정합니다. 길면 Eureka만 이전 Task를 먼저 내리는 배포나 시작 대기 설정을 검토합니다.

Gateway Service가 생긴 뒤부터 원본 인증(4-3)을 넣기 전까지는, 다른 Cloudflare 영역을 거친 요청이 ALB를 통해 Gateway에 닿을 수 있습니다(앞의 SG 한계). API 레코드는 아직 ALB를 가리키지 않지만, 이 기간은 짧게 둡니다.

## AWS 모니터링(Prometheus·Loki·Grafana·Alloy)

중앙 저장·조회는 Data 호스트에, 앱 로그 수집기는 앱 호스트마다 둡니다. 버전은 로컬 Compose(`compose.monitoring.yaml`)와 같고 이미지는 digest로 고정합니다. 두 버전이 다르면 테스트가 실패합니다. 설정은 S3 묶음과 SSM association으로 전달하므로 인스턴스를 교체하지 않고 바뀐 컨테이너만 다시 만듭니다. Data와 앱 호스트는 같은 SSM 문서를 쓰고, 묶음 안에서 실행할 적용 스크립트는 허용한 두 이름으로만 고릅니다. 묶음 읽기 권한과 association을 같은 apply에서 만들면 IAM 전파 전에 첫 읽기가 거절될 수 있습니다. 그래서 묶음 받기는 AccessDenied일 때만 10초 간격으로 6번까지 시도합니다.

Data 호스트의 모니터링은 Redis·Kafka와 Compose 프로젝트, systemd 유닛, 준비 스크립트를 따로 둡니다(`monitoring-compose.yaml`, `travelpay-monitoring.service`, `prepare-monitoring.py`). Grafana 암호가 없거나 모니터링 컨테이너가 준비되지 않아도, 재부팅이나 재적용 때 Redis·Kafka는 시작·반영됩니다. 적용 스크립트는 두 유닛을 먼저 켜 둔 뒤 Redis·Kafka 유닛부터 반영하고, 모니터링 반영이 실패하면 association 실패로 드러냅니다. Redis·Kafka 반영이 실패해도 모니터링 유닛은 다음 부팅에 시작합니다. Grafana 암호 읽기도 권한 거부와 연결 실패일 때만 10초 간격으로 6번까지 시도합니다. 같은 apply에서 이 매개변수 읽기 권한이 생기고, 부팅 직후에는 SSM에 아직 닿지 않을 수 있기 때문입니다. 다만 모니터링 EBS는 Kafka EBS와 함께 Data 유닛의 마운트 단계가 붙이므로(1단계 구성), 이 볼륨 자체의 장애는 지금도 Data 유닛을 막습니다.

| 위치 | 구성 | 공개 범위 |
|---|---|---|
| Data 호스트(Compose 프로젝트 `travelpay-production-monitoring`) | Prometheus(7일·8GB), Loki(7일), Grafana, Alloy(Data 호스트 컨테이너 로그) | Loki만 사설 IP 3100(SG가 로그 수집기 역할에서 온 연결만 허용), 나머지는 호스트 루프백 |
| 앱 호스트(systemd) | Alloy(ECS Task 로그) | 준비 확인용 12345만 호스트 루프백 |

- **지표:** Prometheus가 Eureka로 업무 서비스를 찾습니다. Gateway는 Eureka 메타데이터의 관리 포트 18080으로 수집합니다. Eureka 서버와 Mock Bank는 Cloud Map 사설 DNS로 찾습니다. 레이블은 로컬과 같은 `job=spring-jvm`, `service`, `environment=production`이므로 로컬 대시보드를 그대로 씁니다. 가짜 Eureka 응답과 실제 Prometheus로 주소와 레이블을 확인했습니다.
- **로그:** 앱 컨테이너는 awslogs로 CloudWatch에 보냅니다. Docker는 원격 드라이버의 로그를 로컬 캐시(dual logging)에도 남기고, 앱 호스트의 Alloy가 이 캐시를 Docker API로 읽어 Loki로 보냅니다. 캐시는 Docker 기본값으로 켜져 있고, 앱 호스트에는 이를 끄는 Docker 설정 파일이 없습니다(2026-11-11 확인). Docker 문서는 원격 드라이버에 네트워크 문제가 있으면 로컬 캐시에도 쓰지 않는다고 설명합니다. 따라서 CloudWatch 전송이 막힌 동안의 로그는 Loki에도 없을 수 있습니다.
- **레이블:** 앱 로그의 `service_name`은 ECS 컨테이너 이름이고 `job=ecs-tasks`입니다. Data 호스트의 Redis·Kafka·모니터링 컨테이너 로그는 `job=docker-data`, `host=travelpay-data`입니다. awsvpc의 pause 컨테이너는 발견 단계에서 뺍니다.
- **대시보드·알림:** 운영 대시보드와 송금 알림 규칙은 `infra/monitoring`의 로컬 파일에서 데이터소스, 환경, 로그 job, uid만 바꿔 만듭니다. 원본에서 바꿀 곳의 수는 테스트가 확인합니다.
- **AWS 기본 지표:** RDS·호스트·ECS·ALB 지표와 경보 상태는 CloudWatch 대시보드(`travelpay-production`)로 봅니다. Data 호스트는 IMDS hop 제한(1)으로 컨테이너가 인스턴스 역할 자격 증명을 받지 못하게 했습니다. 그래서 Grafana는 AWS API를 조회하지 않습니다.

Grafana 알림 규칙은 네 개입니다. 알림 채널(contact point)은 두지 않으므로 상태는 Grafana Alerting 화면에서 확인합니다.

| 규칙 | 조건 | 지표가 없을 때 |
|---|---|---|
| 송금 MANUAL_REVIEW 발생 | 운영자 확정이 필요한 송금이 1건 이상 | 정상(서비스 중단은 수집 중단 규칙이 알림) |
| 송금 30분 넘게 PROCESSING | 오래 확정되지 않은 송금이 1건 이상 | 정상 |
| 서비스 지표 수집 중단 | 서비스별 정상 수집 대상이 0개인 상태가 2분 지속. Eureka에서 사라진 서비스도 0으로 셈 | 경보 |
| 결제·송금 복구 스케줄러 정지 | `recover` 스케줄러의 실행 횟수가 2분 동안 늘지 않은 상태가 2분 지속. payment-service는 수집되는데 실행 기록 지표가 없는 경우(스케줄러 비활성, 클래스 이름 변경)도 포함 | 정상(payment-service 중단은 수집 중단 규칙이 알림) |

Grafana는 외부에 공개하지 않고 관리자 계정 `admin` 하나로 씁니다. 암호는 Parameter Store의 `GRAFANA_ADMIN_PASSWORD`이며, 비밀값 준비 도구가 처음에 무작위 값으로 만듭니다. 이 값이 없으면 모니터링 유닛이 시작하지 않으므로, 이 단계는 plan을 저장한 뒤 apply 전에 준비 도구를 `--apply`로 실행합니다. 기존 값은 그대로 두고 없는 값만 만듭니다.

접속은 SSM 포트 포워딩으로 합니다. 작업 PC에 AWS CLI용 Session Manager 플러그인(1.2.764.0 이상)이 필요합니다. 출력의 명령을 실행한 뒤 `http://localhost:3000`에 `admin`과 Parameter Store의 암호로 로그인합니다.

```bash
terraform -chdir=infra/aws output -json monitoring
aws ssm get-parameter --name /travelpay/production/GRAFANA_ADMIN_PASSWORD --with-decryption --query Parameter.Value --output text --profile <프로필>
```

암호를 직접 정하려면 준비 도구에 `--set-grafana-password`를 붙입니다. 화면에 표시하지 않고 두 번 입력받아 Parameter Store 값을 바꿉니다. 규칙은 앞뒤 공백 없이 영문·숫자·기호·공백으로 10~128자이며, 문장형도 됩니다. 한글은 브라우저 암호 칸에서 입력기가 꺼지는 환경이 있어 받지 않습니다. Data 호스트의 준비 스크립트도 같은 규칙으로 확인합니다.

```bash
python3 infra/aws/scripts/prepare-parameters.py <저장한 plan> --profile <프로필> --account <계정 ID> --apply --set-grafana-password
```

Grafana는 암호 파일을 데이터가 처음 만들어질 때만 적용합니다. 그래서 Parameter Store 값을 바꾼 뒤에는 실행 중인 Grafana에도 반영해야 합니다. Data 호스트에서 모니터링 유닛을 다시 읽어 Parameter Store 값을 암호 파일로 받고, 그 파일을 Grafana CLI에 넘깁니다(2026-11-12 Grafana 13.3.0에서 공백·기호가 든 암호로 확인). 아래는 Session Manager 플러그인 없이 작업 PC에서 실행하는 형태입니다. 암호는 명령이나 출력에 나타나지 않습니다.

```bash
aws ssm send-command --profile <프로필> --region ap-northeast-2 --instance-ids <Data 인스턴스 ID> --document-name AWS-RunShellScript \
  --parameters '{"commands":["systemctl reload travelpay-monitoring.service && docker exec -i travelpay-production-monitoring-grafana-1 grafana cli admin reset-admin-password --password-from-stdin < /run/travelpay/grafana/admin-password"]}'
```

- **메모리:** Data 호스트의 컨테이너 메모리 상한 합계는 6,400MiB입니다(Kafka 3,072, Redis 512, Prometheus 1,024, Loki 768, Grafana 768, Alloy 256). Data 호스트 메모리는 7,779MiB입니다. 상한은 예약이 아니라 넘을 수 없는 값입니다. 로컬에서 같은 정의로 띄운 뒤 Grafana는 2분 뒤 279MiB, 5분 유휴 뒤 333MiB(익명 254MiB·파일 캐시 64MiB)를 썼습니다. Loki는 65MiB, Alloy는 49~64MiB, Prometheus는 40~58MiB였습니다. 상한에는 회수 가능한 파일 캐시도 들어갑니다. 독립 리뷰의 측정에서는 Grafana가 512MiB 가까이 올랐으므로 여유를 두어 768MiB로 둡니다. 앱 호스트 Alloy(256MiB 상한)는 ECS 밖이라 호스트 예약 메모리(1,024MiB)를 씁니다.
- **권한:** Alloy는 Docker 소켓으로 컨테이너 로그를 읽으므로 사실상 호스트 root 권한입니다. 로컬과 같은 방식이며, 이미지를 digest로 고정하고 다른 권한은 모두 뺐습니다. 사용 통계 전송도 끕니다. Loki는 인증 없이 SG로만 접근을 막습니다.
- **디스크:** 이미지는 Data 루트 볼륨(16GiB)에 받습니다. 적용 전 사용량은 2.9GiB(18%)였고, 모니터링 이미지 네 개는 로컬 기준 최대 약 3.5GB라 받은 뒤에도 75% 경보 기준보다 낮습니다. 지표·로그는 모니터링 EBS(20GiB)에 쌓습니다.
- **이미지 받기:** 모니터링 이미지는 Docker Hub에서 받습니다. 비로그인 받기는 NAT 공인 IP 하나에 6시간 100회로 제한되며, 이번 적용에서 받는 것은 Data 4회와 앱 호스트 2회입니다.
- **복구 스케줄러 규칙의 전제:** payment Task가 하나라고 보고 Task를 합쳐 셉니다. payment를 여러 Task로 늘리면(지금은 1개) Task별로 나눠야 한 Task의 정지가 가려지지 않습니다. 스케줄러 클래스 목록(`monitoring.tf`)은 테스트가 payment-service 코드와 대조합니다.
- **ECS Service 전:** 첫 ECS Service 전에는 Eureka와 Mock Bank가 없어 Prometheus의 대상 발견 오류가 로그에 남고, 수집 중단 규칙은 8개 서비스 모두 경보 상태였습니다. 4-2에서 Service를 만든 뒤 해소됐습니다.
- **앱 호스트 Alloy 지표:** 호스트 주소를 찾을 수단이 없어 수집하지 않습니다. 대신 Loki의 `host` 레이블별 로그 수신으로 동작을 확인합니다.

## 운영 실행 설정(application-prod.yml) 점검

Task 정의는 `SPRING_CONFIG_NAME=application-prod`로 운영 파일만 읽습니다. 그래서 `application.yml`에만 있는 값은 운영에서 빠지고 기본값이 적용됩니다. 2026-11-12 서비스 8개의 두 파일과 실제 Task 정의 환경 변수를 기계적으로 대조해 세 가지를 뽑고 하나씩 판단했습니다.

- 기본값 없는 자리표시자가 Task 정의로 채워지는지
- 개발 파일에만 있는 키
- 두 파일에서 값이 다른 키

| 항목 | 판단 |
|---|---|
| Ledger DB 커넥션 대기 | Ledger의 개발 파일과 wallet 운영 파일에는 획득 대기 2초·검증 1초가 있습니다. 담당자가 커밋 `91f28cd`("원장 DB 장애 시 503 응답과 커넥션 대기 제한")로 넣은 의도인데, Ledger 운영 파일에만 빠져 있었습니다. 운영 파일에 같은 값을 넣었습니다. DB가 응답하지 않을 때 커넥션을 얻지 못해 실패하기까지 30.0초에서 2.0초(503)로 줄어, 호출하는 쪽의 시간 제한(5~6초) 안에 들어옵니다(실측). 호출하는 쪽은 503을 "결과 모름"으로 보고 같은 키로 나중에 복구합니다 |
| Eureka 자기 보호 | 기본값(켜짐)에서는 갱신이 기준(85%) 아래로 떨어지면 만료된 인스턴스를 지우지 않습니다. 등록 6개에서는 1개만 비정상 종료돼도(메모리 부족 종료 등) 기준 아래가 됩니다. 실험에서 1개·3개를 멈췄을 때 켜 둔 서버는 7~8분 넘게 그대로였고, 끈 서버는 1개일 때 3~4분, 3개일 때 4~6분 안에 지웠습니다. 새 Task가 떠도 죽은 IP가 요청을 받으므로, 서버 응답 캐시와 같은 Task 환경 변수로 끕니다 |
| Gateway 전달 연결 대기 | Gateway(직접 담당)가 다른 서비스로 넘기는 요청에는 연결 대기 제한이 없어 기본 30초가 적용됐습니다. Eureka에 남은 죽은 인스턴스(응답 없이 패킷을 버리는 상태)로 간 요청이 30.0초 뒤 500이 됐습니다. 개발·운영 파일에 2초를 넣은 뒤 2.0초로 줄었습니다(리허설 실측). 세션 확인 연결과 같은 값입니다. 응답 대기는 서비스마다 처리 시간이 달라 제한하지 않았습니다. 연결을 맺지 못한 요청은 요청을 보내기 전의 실패라 이후 Gateway 오류 처리에서 503(`COMMON_SERVICE_UNAVAILABLE`)으로 응답하도록 고쳤습니다(로컬 실험: 연결 시간 초과 500 → 503) |
| User DB 커넥션 대기 | User(직접 담당)는 개발·운영 두 파일 모두 Hikari 획득 대기 설정이 없어 기본 30초였습니다. 운영 풀은 최대 5개라 DB가 느려지면 Ledger와 같은 문제가 생깁니다. Ledger·Wallet과 같은 획득 대기 2초·검증 1초를 두 파일에 넣었습니다. 로그인·가입·비밀번호 변경은 해시 계산을 트랜잭션 밖에서 하므로 정상 상황에서 2초는 충분하고, Gateway 세션 확인 응답 대기(3초)보다 짧습니다. 실제 PostgreSQL을 일시 정지한 실험에서 약관 조회가 503을 받기까지 30.07초에서 2.03초로 줄었습니다 |
| 개발 전용으로 둔 것 | 파일 로그, Swagger, 장애 주입, 가짜 환율 설정, Feign 주소(운영은 Eureka 이름으로 찾음). exchange 복구·만료 스케줄러와 예약 설정은 코드 기본값이 개발 값과 같습니다. Flyway 위치는 Boot 기본값과 같습니다 |
| 의도된 차이 | 포트 8080, 세션 쿠키 `secure`, Swagger 끄기, Redis 기본 포트, 환율 실제 API, CORS 기본 도메인 |
| Eureka `hostname` 고정 | 시작 스크립트가 Task IP로 인스턴스 ID(`IP:포트`)와 등록 주소를 정하므로 롤링 중 두 Task가 서로 덮어쓰지 않습니다 |

운영 파일은 이미지 안의 jar에 들어갑니다. 그래서 yml을 바꾸면 이미지를 다시 게시하고 Task 정의의 이미지 digest를 갱신해야 운영에 반영됩니다. 적용 뒤에는 게시한 이미지의 jar에서 바뀐 설정을 확인합니다. Eureka 설정은 Task 환경 변수라 Task 정의 갱신만으로 반영됩니다.

같은 실수를 막도록 Terraform 테스트가 세 가지를 확인합니다.

- 운영 파일의 기본값 없는 `${이름}`은 모두 Task 정의의 환경 변수나 비밀값이 채워야 합니다.
- 개발 파일에 Hikari·OpenFeign·Gateway 전달(httpclient) 설정이 있으면 운영 파일에도 블록 전체가 같아야 합니다. 파일을 해석하지 못하면 검사가 꺼진 채 통과하지 않고 실패합니다.
- Gateway의 전달 연결 대기는 5초 이하여야 합니다.

**운영 리허설.** 2026-11-12 병합 결과로 이미지 8개를 운영과 같은 방식으로 빌드했습니다. 실제 Task 정의의 환경 변수, 메모리, 읽기 전용 루트, 종료 대기 시간 그대로 로컬에서 띄웠습니다. DB는 RDS 초기화 SQL을 적용한 Postgres 18.6이고, Kafka와 Redis는 운영 정의 그대로 썼습니다. Prometheus·Loki·Grafana·앱 호스트 Alloy도 운영 설정 그대로 띄웠습니다.

- 8개 모두 liveness·readiness 200, 재시작·메모리 부족 종료 없음
- Flyway 6개 스키마 성공. 자동 생성을 끈 Kafka에 wallet이 `wallet.charge.requested`(파티션 3)를 직접 만듦
- Eureka에 6개가 `IP:포트` 인스턴스 ID로 등록(Gateway 관리 포트 18080 메타데이터)
- Prometheus 대상 8개 up. 수집 중단·복구 스케줄러 규칙 정상
- 운영 대시보드 패널 21개 모두 데이터 표시
- 로그는 8개 서비스 모두 서비스 이름으로 Loki에 들어옴. 오류 로그 0건
- 종료 신호를 보내면 Eureka에 `OUT_OF_SERVICE`, 20초 대기 뒤 정상 종료까지 23.3초(종료 대기 60초 안)

리허설이 AWS와 다른 점은 다음과 같습니다.

- DB TLS 검증(`verify-full`)을 끔. 로컬 Postgres에는 RDS 인증서가 없음
- Redis 주소 이름이 다름
- 앱 Alloy가 보내는 Loki 주소가 다름
- ECS 메타데이터는 가짜 서버로 대신함
- 인터넷이 없어 환율 API 호출이 실패함

실제 AWS에서의 첫 기동과 RDS TLS 접속은 4-2에서 확인했습니다(실제 AWS 검증 결과).

**참고로 남긴 것.**

- Spring Cloud LoadBalancer가 운영에서는 Caffeine 캐시를 권하는 경고를 남깁니다. 기본 캐시도 5초 만료로 동작해 의존성을 추가하지 않았습니다.
- 비정상 종료된 인스턴스는 자기 보호를 꺼도 Eureka에서 지워지기까지 시간이 걸립니다. 1개는 약 3~4분이고, 여러 개는 1분에 정리하는 수에 상한이 있어 3개에 4~6분입니다(임대 만료와 정리 주기 동작). 그동안 그쪽으로 간 요청은 Gateway에서 2초, 서비스 간 호출에서 1~2초 만에 실패합니다. 정상 종료는 시작 스크립트가 바로 빼므로 해당하지 않고, 임대 시간은 조정하지 않았습니다.
- Ledger 수정은 커넥션을 얻을 때까지의 대기만 줄입니다. DB가 멈췄을 때 이미 실행 중인 쿼리는 JDBC 소켓 시간 제한이 없어(기본 0) 더 오래 걸릴 수 있습니다. 바꾸면 Flyway 마이그레이션 같은 긴 작업에도 걸리므로 조정하지 않았습니다.

## AWS에 접속하지 않는 코드 검증

```bash
terraform fmt -check -recursive infra/aws
terraform -chdir=infra/aws/bootstrap init -backend=false
terraform -chdir=infra/aws/bootstrap validate
terraform -chdir=infra/aws/bootstrap test
terraform -chdir=infra/aws init -backend=false
terraform -chdir=infra/aws validate
terraform -chdir=infra/aws test
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s infra/aws/tests -p 'test_*.py'
```

`-backend=false`는 이 검증에서 S3 Backend 초기화를 생략하는 옵션입니다. 실제 운영 명령에 계속 붙이는 설정이 아닙니다. `test`는 AWS Provider 경계를 mock으로 바꾸고 Terraform이 평가한 리소스 설정·정책을 검사합니다. 모의 입력과 식별자는 실제 AWS 리소스가 아닙니다.

Terraform test의 run은 기본적으로 apply입니다. 기본 구성 테스트는 `mock_provider "aws"`와 `command = plan`을 명시합니다. SG 규칙 ID 참조와 OIDC 역할 신뢰 등 적용 후 계산값이 필요한 일부 검사는 AWS 동작 전체를 mock 처리한 `command = apply`를 명시합니다. 이 경우에도 실제 AWS 리소스는 생성하지 않습니다. 새 Provider나 실제 AWS 시험을 추가할 때는 모의 처리 범위와 실행·정리 대상을 별도로 검토합니다.

`terraform test`도 모듈 폴더의 `terraform.tfvars`·`*.auto.tfvars.json`을 읽습니다. 저장소에는 실제 운영 입력 파일이 있으므로 테스트 파일은 모든 입력 변수를 직접 정해 실제 값이 섞이지 않게 합니다. 변수를 추가하면 모든 테스트 파일의 `variables`에도 값을 넣습니다(2026-11-11 실제 입력 파일이 섞여 8개 파일이 실패한 것을 확인하고 고정).

DB 초기화 SQL은 별도 경계 검사로도 확인합니다. 로컬에 `postgres:18.6-bookworm` 이미지가 준비된 경우 `TRAVELPAY_TEST_POSTGRES=1`을 위 Python 검사 명령 앞에 추가합니다. 포트·호스트 볼륨·외부 네트워크 없는 임시 컨테이너에서 비슈퍼유저 초기화·재실행·스키마 격리를 검사하고 제거합니다. TCP 접속에 암호 인증을 강제해 SCRAM 값으로 로그인되는지, `pg_stat_statements`에 평문 암호가 남지 않는지도 확인합니다. 실제 RDS의 확장 권한 동작과 접속 검증은 적용 후 확인합니다.

`TRAVELPAY_TEST_DOCKER_MONITORING=1`을 붙이면 Docker 실행 검사를 함께 돌립니다. 운영 Alloy·Loki 설정과 권한 제한(모든 권한 제거, 권한 상승 금지)으로 임시 컨테이너를 외부와 막힌 네트워크에 띄우고, 확인이 끝나면 지웁니다. 다음을 확인합니다.

- ECS Task 로그가 원격 로그 드라이버의 로컬 캐시를 거쳐 대시보드 레이블과 함께 저장되는지
- Data 호스트 로그가 두 Compose 프로젝트의 것만 들어가고, pause 컨테이너 로그는 빠지는지
- 모니터링 이미지의 실행 사용자가 준비 스크립트의 폴더 소유자와 같은지

설정 문법 검사로는 드러나지 않았던 두 결함을 실제 실행으로 다시 막기 위한 검사입니다. 하나는 권한을 모두 뺀 root가 이미지의 저장 경로에 들어가지 못해 Alloy가 시작하지 못한 것이고, 다른 하나는 수집기 단계의 drop이 레이블만 지우고 pause 로그를 그대로 보낸 것입니다. 운영 유닛·Compose의 해당 설정은 Terraform 테스트가 고정합니다.

`TRAVELPAY_TEST_DOCKER_IMAGE=1`을 붙이면 실제 이미지를 빌드해 실행 사용자, 파일 권한, 시작 스크립트 실행, RDS CA 체크섬을 확인합니다. 실행 이미지(Ubuntu 26.04)에서만 드러나는 결함을 잡기 위한 검사입니다. 이 이미지의 `env`는 다른 이름으로 불리면 실행을 거부하므로 시작 스크립트는 `#!/bin/bash`를 쓰고, `ADD --chmod`가 폴더를 만들면 파일 권한이 폴더에도 적용되므로 앱 폴더는 먼저 0755로 만듭니다.

첫 PR의 구성 테스트는 총 16개입니다. State 보안·IAM 범위 3개, 네트워크 파생·입력·라우팅 7개, SG 허용 행렬·공개 범위·초기화 옵션·규칙 소유권 6개를 확인합니다. 넓은 공개 CIDR·불필요한 DB 연결·HTTPS 포트 확대·규칙 관리 목록 누락을 넣은 임시 복사본에서 해당 테스트가 실패하는 것도 확인했습니다.

## 실제 AWS 검증 결과

2026-11-10, 선택한 프로젝트 IAM 사용자·서울 리전에서 확인한 결과입니다.

| 대상 | 확인한 결과 |
|---|---|
| S3 생성 | 저장한 plan 적용: 버킷 1개·보안 설정 5개 생성, 기존 항목 변경·삭제 없음 |
| 실제 S3 설정 | 공개 차단 4개·AES256·버전 관리·ACL 비활성화·TLS 거절 정책 확인 |
| State 이전 | S3 객체의 암호화·버전과 이전 전후 6개 리소스 ID·속성·출력값 일치 확인 |
| 동시 잠금 | 한 Terraform 작업의 잠금 보유 중 다른 plan 차단, 첫 작업 취소 후 해제·State 불변 확인 |
| 이전 후 재검사 | 실제 bootstrap plan: `No changes` |
| 네트워크·SG 적용 | 156개 설정 적용, 기존 항목 변경·삭제 없음 |
| 실제 네트워크 | VPC DNS·서브넷 6개·주소/AZ·라우팅 연결 확인, NAT available·EIP 연결 확인 |
| 실제 SG | 역할 SG 14개·규칙 101개·규칙 관리 목록이 State와 일치, 기본 SG 닫힘 확인 |
| 적용 후 재검사 | 실제 본 root plan: `No changes` |

NAT Gateway 1개와 공인 IPv4 1개가 실제로 실행 중입니다. 서울 리전 기본 요금 합계는 약 USD0.064/h(24시간 약 USD1.54)이며 NAT 처리량·전송·세금은 별도입니다. 앱 배치 전에도 켜둔 기간의 요금이 발생합니다.

2026-11-11에는 4~6단계를 차례로 적용하고 다음을 확인했습니다.

| 대상 | 실제 확인 결과 |
|---|---|
| IAM·OIDC | 허용·차단 시뮬레이션 19건 통과, 일반 Task 8개와 GitHub 역할의 정책 비어 있음, GitHub 배포 권한 비활성 |
| SSM | DB 6개·Redis 2개의 SecureString 생성, 값은 Terraform State·코드·로그에 넣지 않음 |
| ECS on EC2 | m7i.large 2대·두 AZ·Capacity Provider 연결·SSM/ECS Agent 정상, Trunk ENI 실제 부착, ECS 등록 메모리 각 6759MiB |
| Data EC2 | m7i.large 1대·실제 메모리 7779MiB, Redis 두 개·Kafka 정상, 사설 바인딩·암호화 EBS·메모리 상한 확인 |
| RDS | PostgreSQL 18.6·db.t4g.medium·사설·암호화·백업 7일·삭제 보호, TLS·평문 거절·max_connections=100 확인 |
| DB 초기화 | 배포된 스크립트 최초 실행·재실행 성공, 6개 계정 자기 스키마 쓰기·타 스키마 거절, SCRAM 인증 설정 확인 |
| 임시 권한 회수 | Data → RDS TCP 차단, 관리자 Secret·앱 DB 암호 조회 AccessDenied 실제 확인 |
| Data 재기동 | Boot ID 변경, Kafka cluster ID·시험 메시지·두 EBS UUID 유지, Redis 임시 키 초기화·인증 복구, 시험 토픽 삭제 |
| 기본 관측 | 호스트 메모리 3개·디스크 5개 지표 수집, 호스트 초기화 로그 스트림 3개·보관 7일 확인 |
| 적용 후 재검사 | 본 root plan `No changes`, 구성 테스트 33개·Python 검사 22개 통과 |

2026-11-11 개선 코드는 실제 AWS에 적용하기 전에 다음을 확인했습니다.

| 대상 | 확인한 결과 |
|---|---|
| 정적·모의 검사 | fmt·validate, 본 root 구성 테스트 35개·bootstrap 3개, Python 검사 33개 통과 |
| Data 호스트 환경 | AL2023 컨테이너의 Python 3.9에서 같은 Python 검사 통과, cloud-init 22.2.2의 `status --wait` 지원, systemd 유닛 문법 검사 |
| systemd 동작 | `oneshot`·`RemainAfterExit` 유닛에서 `reload-or-restart`가 여러 `ExecReload`를 순서대로 실행하고 `TimeoutStartSec`이 reload 시간도 제한함을 임시 유닛으로 확인 |
| DB 초기화 | PostgreSQL 18.6에서 SCRAM 값으로 로그인·틀린 암호 거절, `pg_stat_statements` 평문 0건. 수정 전 스크립트는 같은 검사에서 평문 1건으로 실패 |
| 실제 plan(잠금 없는 조회) | 추가 12·변경 2·삭제 3: S3 묶음·SSM 문서·association 생성, Data EC2와 볼륨 부착 2개 교체(원인은 user data), Cloud Map·정책 목록 갱신. EBS·RDS·SG·App 호스트 변경 없음 |

같은 날 저장한 plan을 적용하고 다음을 확인했습니다. 교체 전 Kafka cluster ID와 시험 메시지를 기록해 두고 교체 후 같은 값인지 비교했습니다.

| 대상 | 실제 확인 결과 |
|---|---|
| 적용 | 저장한 plan 적용 성공: 추가 12·변경 2·삭제 3, 오류·경고 없음 |
| 교체 시간 | 새 인스턴스 시작 33초 후 association 실행, 94초 후 서비스 준비. 이전 인스턴스 정지부터 재기동까지 약 2분 40초 |
| 묶음 무결성 | Terraform이 넘긴 SHA-256, S3 객체를 받아 계산한 값, 호스트가 적용한 값이 모두 같음 |
| 데이터 보존 | Kafka cluster ID·시험 메시지 유지, 같은 볼륨 ID의 두 EBS가 같은 경로에 마운트, Redis 두 개 인증 응답 정상 |
| 실행 설정 | Kafka 종료 대기 60초, Redis 설정 해시 값이 코드 계산값과 같음, 데이터 파일 0600·유닛 0644 |
| 주소·관측 | `data.travelpay.internal`이 새 사설 IP를 가리킴, 새 인스턴스의 메모리 1개·디스크 3개 지표와 cloud-init 로그 수집 |
| 제자리 반영 | association 재실행 8초 성공. 인스턴스·컨테이너 ID 3개·서비스 시작 시각·Redis 시험 키가 그대로임 |
| 적용 후 재검사 | 본 root plan `No changes`. 시험 토픽·시험 키 삭제 |

2026-11-11 앱 실행 정의·공개 진입점·경보 단계는 다음 순서로 확인했습니다. 적용 전에 사본 코드에 결함 14개를 일부러 넣어 테스트가 모두 잡는지 보고, 독립 리뷰의 지적 11건을 근거로 확인해 반영했습니다.

| 대상 | 확인한 결과 |
|---|---|
| 적용 전 검사 | 본 root 구성 테스트 54개·bootstrap 3개, Python 검사 61개 통과. 저장소에서 잠금을 건 plan이 검토한 잠금 없는 plan과 동작·값까지 같음 |
| 적용 | 저장한 plan 적용: 추가 27·변경 2·삭제 0, 오류 없음(ALB 생성 2분 22초) |
| Task 정의 | 8개 ACTIVE(리비전 1): 게시한 digest, Task 단위 CPU 없음·컨테이너 CPU와 Task 메모리가 설계표와 같음, 읽기 전용 루트, 비특권, 서비스별 비밀값·역할 |
| ALB | HTTPS 443·TLS 1.2/1.3 정책·발급된 인증서, `api.travelpay.store`만 Gateway로·나머지 404, 유휴 1000초. Cloudflare가 아닌 주소(작업 PC)에서는 연결 시간 초과 |
| 앱 호스트 교체 | instance refresh 약 5분 성공, 새 호스트 2대(2a·2c) ECS ACTIVE·Agent 연결·등록 메모리 6759MiB·Trunk ENI 부착 |
| 경보 | 12개 생성, ASG 이름으로 합친 메모리·디스크 지표가 경보 차원과 같게 수집됨. 이메일 구독은 수신자 확인 대기 |
| 시험 실행 | 앱 명령을 바꾼 Task를 실제 호스트에서 실행: 8개 서비스 모두 게시 digest로 실행, uid 10001·루트 읽기 전용·`/tmp` 쓰기, Parameter Store 비밀값 주입(값은 출력하지 않음), CPU 상한 없음(`cpu.max=max`), 시작 스크립트의 Task IP 추출, Task ENI에서 Redis 두 개·Kafka·RDS TCP 연결, 로그가 서비스 로그 그룹에 기록 |
| 비밀값 | 키 준비 전에는 Exchange Task가 `TaskFailedToStart`(파라미터 없음)로 시작 전에 멈춤. 준비 후 Wallet 키는 32바이트로 풀리고, 환율 API 키는 공백·끝 `;` 없이 실제 API 1회 호출이 HTTP 200·23개 통화·`result=1` |
| 적용 후 재검사 | 본 root plan `No changes`, 실행 중 Task·사용 중 Task ENI 0개, Data 설정 묶음은 다시 적용되지 않음 |

ECS의 애플리케이션 Task는 아직 상시 실행하지 않습니다. 앱의 실제 기동(RDS 인증서 검증 접속·Flyway)과 업무 통신은 ECS Service를 만드는 PR4에서 검증합니다(4-2 결과는 아래 표).

2026-11-12 AWS 모니터링 단계는 다음 순서로 확인했습니다.

- 적용 전에 독립 리뷰를 두 번 받았습니다. 지적 19건을 근거로 확인해 반영했습니다.
- 사본 코드에 결함을 일부러 넣어 테스트가 잡는지 봤습니다(Terraform 29개, Python 8개, Docker 실행 검사 3개).
- 로컬에서 같은 이미지·설정으로 Compose, 알림 발동·해소, 로그 수집을 실행해 봤습니다.

| 대상 | 확인한 결과 |
|---|---|
| 적용 전 검사 | 본 root 구성 테스트 59개·bootstrap 3개, Python 검사 90개 통과(기본 실행은 Docker·PostgreSQL 선택 실행 8개를 건너뛰며, 2026-11-12 dev 병합 결과에서 이 8개도 켜서 모두 통과). Python 3.9에서는 시험 이미지에 `curl`이 없어 시작 스크립트 검사 3개만 실패하고, 호스트 스크립트 검사는 모두 통과. 저장소에서 잠금을 건 plan이 검토한 잠금 없는 plan과 동작·값까지 같음 |
| 적용 | 저장한 plan 적용: 추가 6·변경 5·삭제 8, 오류 없음. 삭제 8개는 SSM 문서·association 교체 2개와, 쓰이지 않게 된 모니터링 SG 규칙 3개의 수신·송신 6개. 앱 호스트 association 53초, Data association 1분 23초에 성공. 이어서 Grafana 암호 규칙만 바꾼 적용: 추가 1·변경 1·삭제 1 |
| Redis·Kafka | 두 번의 적용 뒤에도 컨테이너 3개의 ID·시작 시각·재시작 0회가 적용 전과 같음 |
| Data 호스트 | 두 유닛 실행·자동 시작. 모니터링 4종 준비 응답 200. Loki만 사설 IP 3100, 나머지는 루프백 |
| Grafana | 익명 401. 데이터소스 2개·대시보드(TRAVELPAY 폴더)·규칙 4개, 플러그인 설치·프로비저닝 오류 0건. 사용자가 정한 암호를 Parameter Store에서 반영해 로그인 200 |
| 알림 | 수집 중단 규칙은 8개 서비스 모두 경보 대기(앱 Service 전이라 예상한 상태). 나머지 세 규칙은 지표 없음으로 정상 |
| 앱 호스트 | 두 대 모두 Alloy 실행·자동 시작·준비 응답 200, 12345는 루프백만. 사용 통계 전송 0건 |
| 로그 경로 | 시험 Task(mock-bank 정의, 명령만 바꿈)의 출력 6줄이 `job=ecs-tasks`, `service_name=mock-bank`, 실행 호스트 레이블로 Loki에 도착 |
| 접근 차단 | 수집기 역할이 아닌 Task SG에서 Data의 3100·3000·9090 연결 차단. Data 인스턴스에 공인 IP 없음. SG에는 수집기→Loki 3100만 남음 |
| IMDS | Grafana·Alloy 컨테이너 네트워크에서 토큰 요청 시간 초과(호스트는 200) |
| 자원 | Data 메모리 7,779MiB 중 1,876MiB, 루트 디스크 34%. Grafana 246MiB·Loki 52MiB·Alloy 49MiB·Prometheus 35MiB |
| 기타 | CloudWatch 대시보드 위젯 11개, association 두 개 Success, 이전 SSM 문서 삭제 |
| 적용 후 재검사 | 본 root plan `No changes` |

2026-11-12 운영 실행 설정 점검 반영은 다음과 같이 확인했습니다.

| 대상 | 확인한 결과 |
|---|---|
| 이미지 게시 | 커밋 `53e270f` 기준 8개를 태그 `53e270f9d4a3`으로 게시. ECR digest의 Ledger·Gateway 이미지 jar에서 바뀐 설정(커넥션 대기 2000·검증 1000, 전달 연결 대기 2000) 확인 |
| 적용 | 저장한 plan 적용: Task 정의 8개 새 리비전(2), 다른 리소스 변경 없음. 이전 리비전(1)은 롤백용으로 남음 |
| Task 정의 | 8개 모두 새 digest, Eureka에 자기 보호 끄기와 응답 캐시 5초 |
| 시험 실행 | 8개 서비스 모두 새 이미지로 시험 Task 실행, 종료 코드 0. uid 10001, 루트 읽기 전용, `/tmp` 쓰기, 비밀값 주입, Task IP 확인, 허용 대상(RDS·Redis·Kafka) 연결 |
| 적용 후 재검사 | 본 root plan `No changes`, 실행 중 Task 0개 |

이번 이미지는 기능 브랜치 커밋으로 만든 검증용입니다. 운영에는 main에 머지된 커밋의 이미지만 배포합니다. 그래서 첫 Service를 만들기 전에 main 커밋으로 다시 빌드해 Task 정의의 digest를 바꿨습니다(아래 `c22b304` 결과 표). 이후 배포는 CD가 main 커밋으로만 합니다.

2026-11-12 PR2 때 평문으로 보낸 DB 앱 계정 암호를 교체하고 mock-bank 계정 이름을 맞췄습니다.

| 대상 | 확인한 결과 |
|---|---|
| 적용 전 검사 | Gradle 전체 3,425개, 본 root 구성 테스트 59개·bootstrap 3개, Python 103개(실제 PostgreSQL 18.6 시나리오 5개 포함) 통과, 독립 리뷰 지적 반영 |
| 암호 교체 | `--rotate-database-passwords`로 DB 암호 6개 `rotated`, 나머지 11개 `preserved` |
| 작업 창 열기 | 추가 5·변경 5·삭제 2. mock-bank Task 정의 리비전 3(`mock_bank_app`), 리비전 2는 롤백용으로 남음. 설정 묶음에서 초기화 스크립트·`runtime.json` 두 파일만 바뀌어 컨테이너 재생성 없음 |
| 초기화 | 계정 6개가 새 SSM 암호로 로그인해 자기 스키마 확인, `bank_app` 삭제와 스키마 소유자 이전, 쿼리 통계 비우기 성공 |
| 작업 창 닫기 | 변경 4·삭제 3. Data → RDS 5432 차단, Data 역할의 DB 암호·관리자 Secret 조회 `AccessDeniedException`, Redis 암호 조회는 정상 |
| 실행 중인 것 | Redis·Kafka·모니터링 컨테이너 7개와 두 유닛의 시작 시각이 적용 전과 같음 |
| 적용 후 재검사 | 본 root plan `No changes` |

처음 작업 창에서는 초기화가 실행되지 않은 채 창이 닫혀(SSM 세션·명령 기록 없음) 다시 열어 실행했습니다. 그 사이 앱 Task가 없어 영향은 없었습니다.

2026-11-12 main 머지 커밋 `c22b304`(PR #290)으로 이미지를 다시 만들어 Task 정의를 바꿨습니다.

| 대상 | 확인한 결과 |
|---|---|
| 이미지 게시 | 8개를 태그 `c22b3046ee28`로 게시. `53e270f` 이후 실행 코드가 바뀌지 않은 Eureka·Exchange·Ledger·mock-bank·Wallet은 digest가 이전과 같고, Gateway·Payment·User만 새 digest. 새 이미지 jar에서 Gateway의 `Origin-Auth` 제거·연결 실패 503 처리·전달 연결 대기 2000, User의 커넥션 대기 2000·검증 1000, Payment의 송금 외부 호출 시간 제한(#284) 확인 |
| 적용 | 저장한 plan 적용: 추가 3·삭제 3, Gateway·Payment·User Task 정의 새 리비전(3)이고 이미지 외 변경 없음. 삭제는 Terraform 상태에서만 빠진 것이고 이전 리비전(2)은 롤백용으로 ACTIVE 유지 |
| Task 정의 | 8개 모두 이미지 목록 파일의 digest와 같음. Gateway·Payment·User·mock-bank는 리비전 3, 나머지는 2 |
| 시험 실행 | Gateway·User·Payment·mock-bank 시험 Task 종료 코드 0. uid 10001, `/tmp` 쓰기, 비밀값 주입, Redis·RDS 연결 |
| 적용 후 재검사 | 본 root plan `No changes`, 실행 중 Task 0개 |

2026-11-12 ECS Service 첫 기동(4-2)은 다음과 같이 확인했습니다.

| 대상 | 확인한 결과 |
|---|---|
| 적용 전 검사 | 본 root 구성 테스트 65개·bootstrap 3개 통과(Git에서 제외한 입력 파일이 없는 사본에서도 65개 통과). 사본에 결함 21개를 하나씩 넣어 모두 테스트 실패로 잡힘. 독립 리뷰 지적 7건을 근거로 확인해 반영(설명 오류 4건, 테스트 2건, 4-4 위험 1건) |
| plan | Service 8개 추가만, 변경·삭제 0. 서브넷·SG·Cloud Map·Target Group·Task 정의 리비전을 실제 ID와 대조. 지적 반영 뒤 다시 만든 plan이 검토한 plan과 같음 |
| 적용 | 추가 8, 오류 없음. Service별 1분 47초~2분 48초에 안정 상태 |
| 기동 | Task 8개 HEALTHY(2a 5개·2c 3개), 앱 기동 18~62초, ERROR 로그 0건 |
| Flyway | DB 서비스 6개가 RDS에 마이그레이션 적용: user v3, wallet v5, payment v20261109.1, exchange v8, ledger v7, mock-bank v3 |
| 서비스 찾기 | Eureka에 6개 서비스 UP, Cloud Map의 eureka·mock-bank HEALTHY, Gateway ALB Target healthy |
| 업무 경로 | ALB SG를 단 시험 Task에서 Gateway로 `GET /api/v1/terms` 200(약관 응답), `GET /api/v1/wallets` 401(로그인 필요). 공개 경로(Cloudflare → ALB)는 4-3에서 확인 |
| Kafka | Wallet이 `wallet.charge.requested`(파티션 3) 생성 |
| 모니터링 | Prometheus 수집 대상 8개 up, Loki에 8개 서비스 로그, Grafana "서비스 지표 수집 중단" 해소, 나머지 규칙 정상 |
| 자원 | RDS 연결 13개, 앱 호스트 메모리 사용 최대 34%, 남은 예약 메모리 871MiB·4199MiB, CloudWatch 경보 ALARM 0건 |
| 실행 중인 것 | Data 컨테이너 7개의 시작 시각·재시작 0회가 적용 전과 같음 |
| 적용 후 재검사 | 본 root plan `No changes`(Capacity Provider·배포 설정의 반복 차이 없음) |

근거: [Terraform S3 Backend](https://developer.hashicorp.com/terraform/language/backend/s3), [Provider mocking](https://developer.hashicorp.com/terraform/language/tests/mocking), [Terraform 파일 구성](https://developer.hashicorp.com/terraform/language/files).
