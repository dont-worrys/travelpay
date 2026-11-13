# SG의 실제 입력 정책을 검증하며 AWS API는 호출하지 않습니다.
mock_provider "aws" {
  override_during = plan
  mock_data "aws_availability_zones" {
    defaults = { names = ["ap-northeast-2a", "ap-northeast-2c"] }
  }
  mock_resource "aws_vpc" {
    defaults = {
      id                        = "vpc-00000000000000001"
      default_route_table_id    = "rtb-00000000000000000"
      default_security_group_id = "sg-00000000000000000"
    }
  }
}

override_resource {
  target          = aws_security_group.boundary["alb"]
  values          = { id = "sg-00000000000000001" }
  override_during = plan
}

override_resource {
  target          = aws_security_group.boundary["gateway"]
  values          = { id = "sg-00000000000000002" }
  override_during = plan
}

override_resource {
  target          = aws_security_group.boundary["user"]
  values          = { id = "sg-00000000000000003" }
  override_during = plan
}

override_resource {
  target          = aws_security_group.boundary["wallet"]
  values          = { id = "sg-00000000000000004" }
  override_during = plan
}

override_resource {
  target          = aws_security_group.boundary["payment"]
  values          = { id = "sg-00000000000000005" }
  override_during = plan
}

override_resource {
  target          = aws_security_group.boundary["exchange"]
  values          = { id = "sg-00000000000000006" }
  override_during = plan
}

override_resource {
  target          = aws_security_group.boundary["ledger"]
  values          = { id = "sg-00000000000000007" }
  override_during = plan
}

override_resource {
  target          = aws_security_group.boundary["mock_bank"]
  values          = { id = "sg-00000000000000008" }
  override_during = plan
}

override_resource {
  target          = aws_security_group.boundary["eureka"]
  values          = { id = "sg-00000000000000009" }
  override_during = plan
}

override_resource {
  target          = aws_security_group.boundary["app_host"]
  values          = { id = "sg-00000000000000010" }
  override_during = plan
}

override_resource {
  target          = aws_security_group.boundary["data"]
  values          = { id = "sg-00000000000000011" }
  override_during = plan
}

override_resource {
  target          = aws_security_group.boundary["rds"]
  values          = { id = "sg-00000000000000012" }
  override_during = plan
}

override_resource {
  target          = aws_security_group.boundary["monitoring"]
  values          = { id = "sg-00000000000000013" }
  override_during = plan
}

override_resource {
  target          = aws_security_group.boundary["log_collector"]
  values          = { id = "sg-00000000000000014" }
  override_during = plan
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

run "service_connections_match_the_allowed_matrix" {
  command = plan
  assert {
    condition = toset([
      for rule in aws_vpc_security_group_ingress_rule.internal :
      "${rule.referenced_security_group_id}>${rule.security_group_id}:${rule.from_port}-${rule.to_port}/${rule.ip_protocol}"
      ]) == toset([
      for edge in [
        ["alb", "gateway", 8080],
        ["alb", "gateway", 18080],
        ["gateway", "user", 8080],
        ["gateway", "wallet", 8080],
        ["gateway", "payment", 8080],
        ["gateway", "exchange", 8080],
        ["wallet", "user", 8080],
        ["payment", "user", 8080],
        ["wallet", "ledger", 8080],
        ["payment", "ledger", 8080],
        ["exchange", "ledger", 8080],
        ["payment", "wallet", 8080],
        ["exchange", "wallet", 8080],
        ["wallet", "mock_bank", 8090],
        ["user", "data", 16379],
        ["exchange", "data", 16380],
        ["wallet", "data", 9092],
        ["gateway", "eureka", 8761],
        ["user", "eureka", 8761],
        ["wallet", "eureka", 8761],
        ["payment", "eureka", 8761],
        ["exchange", "eureka", 8761],
        ["ledger", "eureka", 8761],
        ["user", "rds", 5432],
        ["wallet", "rds", 5432],
        ["payment", "rds", 5432],
        ["exchange", "rds", 5432],
        ["ledger", "rds", 5432],
        ["mock_bank", "rds", 5432],
        ["monitoring", "gateway", 18080],
        ["monitoring", "user", 8080],
        ["monitoring", "wallet", 8080],
        ["monitoring", "payment", 8080],
        ["monitoring", "exchange", 8080],
        ["monitoring", "ledger", 8080],
        ["monitoring", "eureka", 8761],
        ["monitoring", "mock_bank", 8090],
        ["log_collector", "monitoring", 3100],
      ] : "${aws_security_group.boundary[edge[0]].id}>${aws_security_group.boundary[edge[1]].id}:${edge[2]}-${edge[2]}/tcp"
    ])
    error_message = "실제 업무·수집 호출에 필요한 연결만 정확한 방향과 TCP 포트로 허용해야 합니다."
  }
  assert {
    condition = toset([
      for rule in aws_vpc_security_group_egress_rule.internal :
      "${rule.security_group_id}>${rule.referenced_security_group_id}:${rule.from_port}-${rule.to_port}/${rule.ip_protocol}"
      ]) == toset([
      for rule in aws_vpc_security_group_ingress_rule.internal :
      "${rule.referenced_security_group_id}>${rule.security_group_id}:${rule.from_port}-${rule.to_port}/${rule.ip_protocol}"
    ])
    error_message = "허용 연결마다 출발 SG의 송신과 목적 SG의 수신을 일치시켜야 합니다."
  }
  assert {
    condition = alltrue([
      for rule in aws_vpc_security_group_ingress_rule.internal :
      rule.cidr_ipv4 == null && rule.cidr_ipv6 == null
    ])
    error_message = "내부 연결은 넓은 CIDR 대신 SG 참조로 제한해야 합니다."
  }
}

run "only_cloudflare_https_is_public" {
  command = plan
  assert {
    condition = toset([for rule in aws_vpc_security_group_ingress_rule.cloudflare : rule.cidr_ipv4]) == toset([
      "173.245.48.0/20", "103.21.244.0/22", "103.22.200.0/22", "103.31.4.0/22",
      "141.101.64.0/18", "108.162.192.0/18", "190.93.240.0/20", "188.114.96.0/20",
      "197.234.240.0/22", "198.41.128.0/17", "162.158.0.0/15", "104.16.0.0/13",
      "104.24.0.0/14", "172.64.0.0/13", "131.0.72.0/22",
      ]) && alltrue([
      for rule in aws_vpc_security_group_ingress_rule.cloudflare :
      rule.security_group_id == aws_security_group.boundary["alb"].id &&
      rule.from_port == 443 && rule.to_port == 443 && rule.ip_protocol == "tcp" && rule.cidr_ipv6 == null
    ])
    error_message = "인터넷 진입은 공식 Cloudflare IPv4의 ALB HTTPS443만 허용해야 합니다."
  }
}

run "https_egress_is_not_allow_all" {
  command = plan
  assert {
    condition = toset(keys(aws_vpc_security_group_egress_rule.https)) == toset(["app_host", "data", "exchange", "monitoring"]) && alltrue([
      for name, rule in aws_vpc_security_group_egress_rule.https :
      rule.security_group_id == aws_security_group.boundary[name].id &&
      rule.cidr_ipv4 == "0.0.0.0/0" && rule.cidr_ipv6 == null &&
      rule.from_port == 443 && rule.to_port == 443 && rule.ip_protocol == "tcp"
    ])
    error_message = "필요한 역할의 HTTPS443만 외부로 열고 모든 포트·모든 역할을 열면 안 됩니다."
  }
}

run "group_boundaries_and_default_group_are_owned" {
  command = plan
  assert {
    condition = toset(keys(aws_security_group.boundary)) == toset([
      "alb", "gateway", "user", "wallet", "payment", "exchange", "ledger", "mock_bank", "eureka",
      "app_host", "data", "rds", "monitoring", "log_collector",
      ]) && alltrue([
      for group in aws_security_group.boundary : group.vpc_id == "vpc-00000000000000001"
    ])
    error_message = "역할별 SG를 같은 프로젝트 VPC 안에서 분리해야 합니다."
  }
  assert {
    condition = (
      aws_vpc_security_group_rules_exclusive.default.security_group_id == "sg-00000000000000000" &&
      length(aws_vpc_security_group_rules_exclusive.default.ingress_rule_ids) == 0 &&
      length(aws_vpc_security_group_rules_exclusive.default.egress_rule_ids) == 0 &&
      toset(keys(aws_vpc_security_group_rules_exclusive.managed)) == toset(keys(aws_security_group.boundary)) &&
      alltrue([for name, owner in aws_vpc_security_group_rules_exclusive.managed : owner.security_group_id == aws_security_group.boundary[name].id])
    )
    error_message = "기본 SG를 닫고 역할 SG의 규칙 소유권도 명시해야 합니다."
  }
}

run "database_bootstrap_access_is_explicit" {
  command = plan
  variables {
    enable_database_bootstrap_access = true
  }
  assert {
    condition = length([
      for rule in aws_vpc_security_group_ingress_rule.internal : rule
      if rule.security_group_id == aws_security_group.boundary["rds"].id &&
      rule.referenced_security_group_id == aws_security_group.boundary["data"].id &&
      rule.from_port == 5432 && rule.to_port == 5432 && rule.ip_protocol == "tcp"
      ]) == 1 && length([
      for rule in aws_vpc_security_group_egress_rule.internal : rule
      if rule.security_group_id == aws_security_group.boundary["data"].id &&
      rule.referenced_security_group_id == aws_security_group.boundary["rds"].id &&
      rule.from_port == 5432 && rule.to_port == 5432 && rule.ip_protocol == "tcp"
    ]) == 1 && length(aws_vpc_security_group_ingress_rule.internal) == 39 && length(aws_vpc_security_group_egress_rule.internal) == 39
    error_message = "초기화 옵션은 Data→RDS5432 한 연결만 양방향으로 추가해야 합니다."
  }
}
