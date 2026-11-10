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
