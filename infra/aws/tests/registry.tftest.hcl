# 앱 이미지 저장소·로그 그룹·사설 발견 이름·공개 인증서가 실행 역할 권한과 같은 계약을 쓰는지 검사합니다.
mock_provider "aws" {
  override_during = plan
  mock_data "aws_availability_zones" {
    defaults = { names = ["ap-northeast-2a", "ap-northeast-2c"] }
  }
  mock_data "aws_ami" {
    defaults = {
      id   = "ami-0123456789abcdef0", architecture = "x86_64",
      name = "al2023-ami-ecs-hvm-test", root_device_name = "/dev/xvda",
    }
  }
}

variables {
  app_host_count                   = 0
  enable_database_bootstrap_access = false
  enable_github_deployment         = false
  aws_account_id                   = "123456789012"
  alert_email                      = "ops@example.com"
  availability_zones               = { a = "ap-northeast-2a", b = "ap-northeast-2c" }
  enable_app_foundation            = true
  enable_data_foundation           = true
  enable_app_runtime               = false
  application_images               = null
  ecs_ami_id                       = "ami-0123456789abcdef0"
  data_ami_id                      = "ami-0123456789abcdef1"
}

run "registry_and_logs_match_execution_role_permissions" {
  command = plan

  assert {
    condition = length(aws_ecr_repository.application) == 8 && alltrue([
      for key, repository in aws_ecr_repository.application :
      "arn:aws:ecr:ap-northeast-2:123456789012:repository/${repository.name}" == local.repository_arns[key]
    ])
    error_message = "ECR 이름이 실행 역할·배포 역할의 저장소 권한과 같아야 합니다."
  }

  assert {
    condition = alltrue([
      for repository in aws_ecr_repository.application : (
        repository.image_tag_mutability == "IMMUTABLE" &&
        one(repository.encryption_configuration).encryption_type == "AES256" &&
        one(repository.image_scanning_configuration).scan_on_push
      )
    ])
    error_message = "이미지 태그는 덮어쓸 수 없고, 저장소는 암호화·푸시 시 스캔을 사용해야 합니다."
  }

  assert {
    condition = alltrue([
      for policy in aws_ecr_lifecycle_policy.application :
      jsondecode(policy.policy).rules[0].selection.countNumber == 10 && jsondecode(policy.policy).rules[0].action.type == "expire"
    ]) && length(aws_ecr_lifecycle_policy.application) == 8
    error_message = "서비스별 최근 이미지 10개만 남겨야 합니다."
  }

  assert {
    condition = length(aws_cloudwatch_log_group.application) == 8 && alltrue([
      for key, group in aws_cloudwatch_log_group.application :
      "arn:aws:logs:ap-northeast-2:123456789012:log-group:${group.name}:*" == local.application_log_arns[key] && group.retention_in_days == 7
    ])
    error_message = "앱 로그 그룹은 실행 역할의 로그 권한과 같은 이름으로 7일 보관해야 합니다."
  }
}

run "discovery_names_and_certificate" {
  command = plan

  assert {
    condition = (
      aws_service_discovery_service.application["eureka"].name == "eureka" &&
      aws_service_discovery_service.application["mock_bank"].name == "mock-bank" &&
      length(aws_service_discovery_service.application) == 2 &&
      alltrue([
        for service in aws_service_discovery_service.application : (
          one(service.dns_config).routing_policy == "MULTIVALUE" &&
          one(one(service.dns_config).dns_records).type == "A" &&
          one(one(service.dns_config).dns_records).ttl == 10 &&
          one(service.health_check_custom_config).failure_threshold == 1
        )
      ])
    )
    error_message = "Eureka·Mock Bank는 ECS 헬스 체크를 따르는 짧은 TTL의 사설 A 레코드로 찾아야 합니다."
  }

  assert {
    condition     = aws_acm_certificate.api[0].domain_name == "api.travelpay.store" && aws_acm_certificate.api[0].validation_method == "DNS"
    error_message = "공개 API 인증서는 api.travelpay.store를 DNS로 검증해야 합니다."
  }
}
