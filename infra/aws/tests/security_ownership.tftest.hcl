# 계산된 규칙 ID의 연결은 mock apply 후 검사하며 AWS API 요청은 모두 대체합니다.
# plan용 고정 ID/override 설정과 분리하여 apply에서 생성되는 값을 검증합니다.
mock_provider "aws" {
  mock_data "aws_availability_zones" {
    defaults = { names = ["ap-northeast-2a", "ap-northeast-2c"] }
  }
}

variables {
  enable_app_foundation            = false
  enable_data_foundation           = false
  alert_email                      = null
  enable_app_runtime               = false
  enable_app_services              = false
  enable_public_entry              = false
  application_images               = null
  app_host_count                   = 0
  enable_database_bootstrap_access = false
  enable_github_deployment         = false
  aws_account_id                   = "123456789012"
  availability_zones               = { a = "ap-northeast-2a", b = "ap-northeast-2c" }
}

# 규칙 ID가 확정된 뒤의 참조를 검사합니다. 모든 AWS 요청은 위 mock Provider가 대체합니다.
# 실제 AWS에 apply하거나 SG의 실제 접속 동작을 입증하는 테스트는 아닙니다.
run "exclusive_sets_preserve_all_declared_rules" {
  command = apply

  variables {
    enable_database_bootstrap_access = true
  }

  assert {
    condition = alltrue([
      for name, owner in aws_vpc_security_group_rules_exclusive.managed :
      sort(tolist(owner.ingress_rule_ids)) == sort(concat(
        [for rule in aws_vpc_security_group_ingress_rule.internal : rule.id if rule.security_group_id == aws_security_group.boundary[name].id],
        [for rule in aws_vpc_security_group_ingress_rule.cloudflare : rule.id if rule.security_group_id == aws_security_group.boundary[name].id],
      ))
    ])
    error_message = "각 SG의 수신 규칙 관리 목록에서 선언한 규칙이 빠지거나 다른 SG 규칙이 섞이면 안 됩니다."
  }

  assert {
    condition = alltrue([
      for name, owner in aws_vpc_security_group_rules_exclusive.managed :
      sort(tolist(owner.egress_rule_ids)) == sort(concat(
        [for rule in aws_vpc_security_group_egress_rule.internal : rule.id if rule.security_group_id == aws_security_group.boundary[name].id],
        [for rule in aws_vpc_security_group_egress_rule.https : rule.id if rule.security_group_id == aws_security_group.boundary[name].id],
      ))
    ])
    error_message = "각 SG의 송신 규칙 관리 목록을 선언한 대상별 규칙과 정확히 일치시켜야 합니다."
  }
}
