locals {
  state_keys = {
    bootstrap  = "bootstrap/terraform.tfstate"
    production = "production/terraform.tfstate"
  }

  state_object_arns = [for key in values(local.state_keys) : "${local.state_bucket_arn}/${key}"]

  # S3 Backend는 init 시점에 설정되므로 Terraform 변수를 직접 참조할 수 없습니다.
  backend_configs = {
    for name, key in local.state_keys : name => <<-HCL
      bucket              = "${local.state_bucket_name}"
      key                 = "${key}"
      region              = "ap-northeast-2"
      encrypt             = true
      use_lockfile        = true
      allowed_account_ids = ["${var.aws_account_id}"]
    HCL
  }
}

output "state_bucket_name" {
  description = "이 프로젝트의 Terraform State 전용 S3 버킷 이름입니다."
  value       = local.state_bucket_name
}

output "bootstrap_backend_config" {
  description = "S3 생성 후 bootstrap의 local State를 옮길 때 사용할 backend.hcl 내용입니다."
  value       = local.backend_configs.bootstrap
}

output "production_backend_config" {
  description = "본 인프라 root가 사용할 backend.hcl 내용입니다."
  value       = local.backend_configs.production
}

output "state_backend_access_policy_json" {
  description = "State 사용 역할에 필요한 IAM 정책입니다. 출력만 하며 어떤 역할에도 자동 부여하지 않습니다."
  value = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid      = "ListStateBucket"
        Effect   = "Allow"
        Action   = ["s3:ListBucket"]
        Resource = [local.state_bucket_arn]
      },
      {
        Sid      = "ReadWriteState"
        Effect   = "Allow"
        Action   = ["s3:GetObject", "s3:PutObject"]
        Resource = local.state_object_arns
      },
      {
        Sid      = "ManageStateLocks"
        Effect   = "Allow"
        Action   = ["s3:GetObject", "s3:PutObject", "s3:DeleteObject"]
        Resource = [for arn in local.state_object_arns : "${arn}.tflock"]
      },
    ]
  })
}
