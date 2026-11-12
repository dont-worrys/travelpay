variable "aws_account_id" {
  description = "실제 인프라를 적용할 AWS 계정 ID입니다. Backend와 같은 계정을 지정합니다."
  type        = string
  nullable    = false

  validation {
    condition     = can(regex("^[0-9]{12}$", var.aws_account_id))
    error_message = "AWS 계정 ID는 숫자 12자리여야 합니다."
  }
}

variable "vpc_cidr" {
  description = "VPC의 사설 IPv4 /16 범위입니다. 서브넷 주소는 여기에서 파생합니다."
  type        = string
  default     = "10.20.0.0/16"
  nullable    = false

  validation {
    condition = (
      can(cidrnetmask(var.vpc_cidr)) &&
      can(regex("/16$", var.vpc_cidr)) &&
      try(cidrhost(var.vpc_cidr, 0) == split("/", var.vpc_cidr)[0], false) &&
      can(regex("^(10\\.|172\\.(1[6-9]|2[0-9]|3[0-1])\\.|192\\.168\\.)", var.vpc_cidr))
    )
    error_message = "VPC CIDR은 네트워크 주소로 시작하는 사설 IPv4 /16이어야 합니다(예: 10.20.0.0/16)."
  }
}

variable "availability_zones" {
  description = "논리 A/B에 배치할 실제 서울 리전 AZ 이름입니다. 현재 계정의 가용 AZ를 확인해 입력합니다."
  type = object({
    a = string
    b = string
  })
  nullable = false

  validation {
    condition = (
      var.availability_zones.a != var.availability_zones.b &&
      alltrue([for az in values(var.availability_zones) : can(regex("^ap-northeast-2[a-z]$", az))])
    )
    error_message = "서로 다른 서울 리전 AZ 두 개를 입력해야 합니다."
  }
}

variable "enable_database_bootstrap_access" {
  description = "DB 계정·스키마 초기화 중에만 Data EC2의 RDS 접속을 엽니다. 초기화 후 false로 되돌립니다."
  type        = bool
  default     = false
  nullable    = false
}

variable "github_repository" {
  description = "OIDC 배포를 신뢰할 GitHub 저장소(owner/name)입니다."
  type        = string
  default     = "dont-worrys/travelpay"
  nullable    = false

  validation {
    condition     = can(regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$", var.github_repository))
    error_message = "GitHub 저장소는 와일드카드 없는 owner/name 형식이어야 합니다."
  }
}

variable "github_environment" {
  description = "배포 브랜치·승인 보호를 적용할 GitHub Environment 이름입니다."
  type        = string
  default     = "production"
  nullable    = false

  validation {
    condition     = can(regex("^[A-Za-z0-9_.-]+$", var.github_environment))
    error_message = "GitHub 환경 이름에는 와일드카드나 subject 구분자를 넣을 수 없습니다."
  }
}

variable "enable_github_deployment" {
  description = "GitHub 환경 보호와 워크플로가 검증된 뒤 배포 권한을 붙입니다."
  type        = bool
  default     = false
  nullable    = false
}

variable "enable_app_foundation" {
  description = "5단계 ECS·앱 호스트 기반을 활성화합니다. 데이터 기반은 별도로 활성화합니다."
  type        = bool
  default     = false
  nullable    = false
}

variable "enable_data_foundation" {
  description = "6단계 RDS·Data EC2·EBS·사설 DNS를 활성화합니다. 앱 호스트를 생성하지 않습니다."
  type        = bool
  default     = false
  nullable    = false
}

variable "app_host_count" {
  description = "최초에는 0으로 Capacity Provider를 준비하고 연결 확인 후 2로 올립니다."
  type        = number
  default     = 0
  nullable    = false

  validation {
    condition     = contains([0, 2], var.app_host_count) && (var.enable_app_foundation || var.app_host_count == 0)
    error_message = "앱 호스트 수는 초기 준비 0 또는 정상 운영 2이며 런타임 활성화 후에만 시작합니다."
  }
}

variable "ecs_ami_id" {
  description = "확인한 서울 리전 x86_64 ECS 최적화 AL2023 AMI ID입니다. latest 조회로 자동 갱신하지 않습니다."
  type        = string
  default     = null

  validation {
    condition     = var.ecs_ami_id == null ? !var.enable_app_foundation : can(regex("^ami-[0-9a-f]{17}$", var.ecs_ami_id))
    error_message = "서버 생성 전 고정할 ECS AMI ID를 입력해야 합니다."
  }
}

variable "data_ami_id" {
  description = "확인한 서울 리전 x86_64 AL2023 Data 호스트 AMI ID입니다."
  type        = string
  default     = null

  validation {
    condition     = var.data_ami_id == null ? !var.enable_data_foundation : can(regex("^ami-[0-9a-f]{17}$", var.data_ami_id))
    error_message = "서버 생성 전 고정할 Data AMI ID를 입력해야 합니다."
  }
}

variable "enable_app_runtime" {
  description = "이미지 게시와 공개 인증서 발급(ISSUED)을 확인한 뒤 Task 정의·ALB를 만듭니다. ECS Service는 enable_app_services로 따로 켭니다."
  type        = bool
  default     = false
  nullable    = false

  validation {
    condition     = !var.enable_app_runtime || (var.enable_app_foundation && var.enable_data_foundation)
    error_message = "Task 정의는 ECR·로그 그룹(앱 기반)과 DB·사설 DNS(데이터 기반)를 쓰므로 두 기반을 먼저 켜야 합니다."
  }
}

variable "enable_app_services" {
  description = "Task 정의·ALB와 앱 호스트를 확인한 뒤 8개 ECS Service로 앱을 상시 실행합니다. false로 되돌리면 Service와 Task를 지웁니다."
  type        = bool
  default     = false
  nullable    = false

  validation {
    condition     = !var.enable_app_services || (var.enable_app_runtime && var.app_host_count > 0)
    error_message = "ECS Service는 Task 정의·ALB(enable_app_runtime)와 Task를 띄울 앱 호스트(app_host_count=2)가 있어야 만들 수 있습니다."
  }
}

variable "enable_public_entry" {
  description = "Cloudflare 원본 인증 값(ORIGIN_AUTH)과 Grafana 인증서 발급을 확인한 뒤 ALB에 원본 인증 헤더 검사와 Grafana 공개 경로를 켭니다."
  type        = bool
  default     = false
  nullable    = false

  validation {
    condition     = !var.enable_public_entry || var.enable_app_services
    error_message = "공개 진입점은 상시 실행 중인 앱(enable_app_services)이 있어야 켤 수 있습니다."
  }
}

variable "application_images" {
  description = "서비스별 배포 이미지입니다. publish-images.py가 기록한 application-images.auto.tfvars.json의 digest 주소입니다."
  type        = map(string)
  default     = null

  validation {
    condition = var.application_images == null ? !var.enable_app_runtime : (
      sort(keys(var.application_images)) == sort(keys(local.application_services)) &&
      alltrue([
        for key, image in var.application_images : can(regex(
          "^${var.aws_account_id}\\.dkr\\.ecr\\.${local.runtime_region}\\.amazonaws\\.com/travelpay/${local.application_services[key].name}@sha256:[0-9a-f]{64}$",
          image,
        ))
      ])
    )
    error_message = "8개 서비스 모두 이 계정 ECR의 같은 이름 저장소 이미지를 태그가 아닌 digest(@sha256:)로 지정해야 합니다."
  }
}

variable "alert_email" {
  description = "CloudWatch 경보를 받을 이메일입니다. AWS가 보내는 구독 확인 메일의 링크를 눌러야 알림이 전달됩니다."
  type        = string
  default     = null
  sensitive   = true

  validation {
    condition = var.alert_email == null ? !(var.enable_app_foundation || var.enable_data_foundation) : can(regex(
      "^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$", var.alert_email,
    ))
    error_message = "앱·데이터 기반을 켜면 경보를 받을 올바른 이메일 주소가 필요합니다."
  }
}
