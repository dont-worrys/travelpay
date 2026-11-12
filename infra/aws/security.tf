locals {
  security_group_roles = toset([
    "alb", "gateway", "user", "wallet", "payment", "exchange", "ledger", "mock_bank", "eureka",
    "app_host", "data", "rds", "monitoring", "log_collector",
  ])

  # 출처: https://api.cloudflare.com/client/v4/ips (2026-11-10 확인).
  # 이 배포는 IPv4만 사용합니다. 목록 변경은 코드와 Gateway 신뢰 설정을 함께 검토합니다.
  cloudflare_ipv4_cidrs = toset([
    "173.245.48.0/20", "103.21.244.0/22", "103.22.200.0/22", "103.31.4.0/22",
    "141.101.64.0/18", "108.162.192.0/18", "190.93.240.0/20", "188.114.96.0/20",
    "197.234.240.0/22", "198.41.128.0/17", "162.158.0.0/15", "104.16.0.0/13",
    "104.24.0.0/14", "172.64.0.0/13", "131.0.72.0/22",
  ])

  # 연결을 추가할 때는 여기에서 출발·도착·단일 TCP 포트를 함께 정의합니다.
  tcp_connections = merge(
    {
      alb_gateway_api      = { source = "alb", target = "gateway", port = 8080 }
      alb_gateway_health   = { source = "alb", target = "gateway", port = 18080 }
      gateway_user         = { source = "gateway", target = "user", port = 8080 }
      gateway_wallet       = { source = "gateway", target = "wallet", port = 8080 }
      gateway_payment      = { source = "gateway", target = "payment", port = 8080 }
      gateway_exchange     = { source = "gateway", target = "exchange", port = 8080 }
      wallet_user          = { source = "wallet", target = "user", port = 8080 }
      payment_user         = { source = "payment", target = "user", port = 8080 }
      wallet_ledger        = { source = "wallet", target = "ledger", port = 8080 }
      payment_ledger       = { source = "payment", target = "ledger", port = 8080 }
      exchange_ledger      = { source = "exchange", target = "ledger", port = 8080 }
      payment_wallet       = { source = "payment", target = "wallet", port = 8080 }
      exchange_wallet      = { source = "exchange", target = "wallet", port = 8080 }
      wallet_bank          = { source = "wallet", target = "mock_bank", port = 8090 }
      user_session_redis   = { source = "user", target = "data", port = 16379 }
      exchange_cache_redis = { source = "exchange", target = "data", port = 16380 }
      wallet_kafka         = { source = "wallet", target = "data", port = 9092 }
      # 모니터링 구성 요소끼리는 Data 호스트의 Compose 네트워크와 루프백으로만 통신하므로, 앱 호스트 수집기가 Loki로 보내는
      # 연결만 엽니다(monitoring-compose.yaml).
      collector_loki = { source = "log_collector", target = "monitoring", port = 3100 }
    },
    {
      for source in toset(["gateway", "user", "wallet", "payment", "exchange", "ledger"]) :
      "${source}_eureka" => { source = source, target = "eureka", port = 8761 }
    },
    {
      for source in toset(["user", "wallet", "payment", "exchange", "ledger", "mock_bank"]) :
      "${source}_rds" => { source = source, target = "rds", port = 5432 }
    },
    {
      for target, port in {
        gateway  = 18080, user = 8080, wallet = 8080, payment = 8080,
        exchange = 8080, ledger = 8080, eureka = 8761, mock_bank = 8090,
      } : "monitoring_${target}" => { source = "monitoring", target = target, port = port }
    },
    var.enable_database_bootstrap_access ? {
      data_rds_bootstrap = { source = "data", target = "rds", port = 5432 }
    } : {},
    # 공개 진입점을 켠 뒤에만 ALB가 Data 호스트의 Grafana(모니터링 역할)로 보냅니다(public-ingress.tf).
    var.enable_public_entry ? {
      alb_grafana = { source = "alb", target = "monitoring", port = 3000 }
    } : {},
  )

  # NAT는 통신 경로이고 SG는 통신 허용입니다. 필요한 HTTPS 포트만 열어 둡니다.
  https_egress_roles = toset(["app_host", "data", "exchange", "monitoring"])
}

resource "aws_security_group" "boundary" {
  for_each = local.security_group_roles

  name        = "travelpay-${replace(each.key, "_", "-")}"
  description = "Travelpay ${replace(each.key, "_", " ")} traffic boundary"
  vpc_id      = aws_vpc.main.id

  tags = { Name = "travelpay-${replace(each.key, "_", "-")}" }
}

# SG 본체에 상호 참조를 넣지 않고 규칙을 별도 리소스로 관리합니다.
resource "aws_vpc_security_group_ingress_rule" "internal" {
  for_each = local.tcp_connections

  security_group_id            = aws_security_group.boundary[each.value.target].id
  referenced_security_group_id = aws_security_group.boundary[each.value.source].id
  ip_protocol                  = "tcp"
  from_port                    = each.value.port
  to_port                      = each.value.port
  description                  = replace(each.key, "_", " ")
}

resource "aws_vpc_security_group_egress_rule" "internal" {
  for_each = local.tcp_connections

  security_group_id            = aws_security_group.boundary[each.value.source].id
  referenced_security_group_id = aws_security_group.boundary[each.value.target].id
  ip_protocol                  = "tcp"
  from_port                    = each.value.port
  to_port                      = each.value.port
  description                  = replace(each.key, "_", " ")
}

resource "aws_vpc_security_group_ingress_rule" "cloudflare" {
  for_each = local.cloudflare_ipv4_cidrs

  security_group_id = aws_security_group.boundary["alb"].id
  cidr_ipv4         = each.key
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
  description       = "Cloudflare HTTPS"
}

resource "aws_vpc_security_group_egress_rule" "https" {
  for_each = local.https_egress_roles

  security_group_id = aws_security_group.boundary[each.key].id
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
  description       = "Required HTTPS egress"
}

# 이 파일에서 규칙을 단독 관리하며 inline 규칙이나 다른 관리 주체의 규칙과 섞지 않습니다.
resource "aws_vpc_security_group_rules_exclusive" "managed" {
  for_each = local.security_group_roles

  security_group_id = aws_security_group.boundary[each.key].id
  ingress_rule_ids = concat(
    [for name, rule in aws_vpc_security_group_ingress_rule.internal : rule.id if local.tcp_connections[name].target == each.key],
    each.key == "alb" ? [for rule in aws_vpc_security_group_ingress_rule.cloudflare : rule.id] : [],
  )
  egress_rule_ids = concat(
    [for name, rule in aws_vpc_security_group_egress_rule.internal : rule.id if local.tcp_connections[name].source == each.key],
    contains(local.https_egress_roles, each.key) ? [aws_vpc_security_group_egress_rule.https[each.key].id] : [],
  )
}

resource "aws_vpc_security_group_rules_exclusive" "default" {
  security_group_id = aws_vpc.main.default_security_group_id
  ingress_rule_ids  = []
  egress_rule_ids   = []
}
