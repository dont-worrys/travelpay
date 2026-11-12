# 8개 앱을 서비스마다 Task 1개로 상시 실행합니다. 배포는 새 Task가 정상이 된 뒤 이전 Task를 내리는 롤링입니다.
# 스케줄러가 있는 Payment·Wallet·Exchange는 롤링 중 두 Task에서 함께 돌아도 안전하다고 담당자가 확인했습니다(2026-11-12).
# 기동 순서는 두지 않습니다. 호스트 교체·장애 복구 때도 Task는 순서 없이 시작하므로 앱이 이를 견뎌야 합니다. Eureka 클라이언트는
# 서버에 닿지 못하면 30초마다 등록을 다시 시도하고, 서버가 모르는 인스턴스라고 답하면(heartbeat 404) 다시 등록합니다.
resource "aws_ecs_service" "application" {
  for_each = var.enable_app_services ? local.application_services : {}

  # CD 배포 권한(github-oidc.tf)이 이 이름으로 Service를 지정합니다.
  name            = each.value.name
  cluster         = aws_ecs_cluster.main[0].id
  task_definition = aws_ecs_task_definition.application[each.key].arn
  desired_count   = 1

  # 클러스터 기본값과 같지만 생략하면 AWS가 기본값을 Service에 기록해, 다음 plan부터 설정과 달라 갱신이 거절됩니다.
  capacity_provider_strategy {
    capacity_provider = aws_ecs_capacity_provider.app[0].name
    weight            = 1
  }

  network_configuration {
    subnets         = [aws_subnet.network["app_a"].id, aws_subnet.network["app_b"].id]
    security_groups = [aws_security_group.boundary[each.key].id]
  }

  # 배포 중에도 정상 Task 1개를 유지합니다. 새 Task가 계속 실패하면 마지막으로 성공한 리비전으로 되돌립니다.
  deployment_minimum_healthy_percent = 100
  deployment_maximum_percent         = 200
  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  # 다른 앱이 고정 이름으로 찾는 Eureka·Mock Bank만 Cloud Map에 등록합니다(DNS 응답 규칙은 private-dns.tf).
  dynamic "service_registries" {
    for_each = contains(keys(local.application_discovery_names), each.key) ? [aws_service_discovery_service.application[each.key].arn] : []
    content {
      registry_arn = service_registries.value
    }
  }

  dynamic "load_balancer" {
    for_each = each.key == "gateway" ? [aws_lb_target_group.gateway[0].arn] : []
    content {
      target_group_arn = load_balancer.value
      container_name   = each.value.name
      container_port   = each.value.port
    }
  }
  # ALB 상태 확인을 기동 중에는 보지 않습니다. 여러 Task가 함께 시작할 때의 최악 기동 시간(114초)보다 길게 둡니다.
  # 컨테이너 헬스 체크는 Task 정의의 시작 유예(startPeriod 180초)가 같은 역할을 하므로 ALB에 연결한 Gateway에만 둡니다.
  health_check_grace_period_seconds = each.key == "gateway" ? 180 : null

  # 배포가 실패하거나 되돌려지면 apply가 그 이유와 함께 실패하도록 안정 상태까지 기다립니다.
  wait_for_steady_state = true

  # 실행할 리비전은 CD(scripts/deploy-services.py)가 정합니다. 무시하지 않으면 다음 apply가 CD가 배포한 리비전을
  # Terraform이 만든 리비전(이전 이미지)으로 되돌립니다. Terraform이 바꾼 Task 설정은 새 리비전으로 등록되고,
  # CD가 그 패밀리의 최신 리비전에서 다음 배포를 만들 때 반영됩니다.
  lifecycle {
    ignore_changes = [task_definition]
  }

  # 참조로 드러나지 않는 실행 조건입니다. Target Group은 리스너 규칙으로 ALB에 연결돼야 Service에 붙일 수 있고, Task는 실행 역할 권한·SG 규칙이
  # 있어야 이미지·비밀값을 받고 DB 등에 연결합니다. 삭제 때 provider는 Task가 종료를 시작할 때(INACTIVE)까지만 기다리므로,
  # 전체를 내릴 때는 먼저 enable_app_services=false로 Task를 모두 멈춘 뒤 나머지를 지웁니다.
  depends_on = [
    aws_ecs_cluster_capacity_providers.main,
    aws_iam_role_policies_exclusive.task_execution,
    aws_vpc_security_group_rules_exclusive.managed,
    aws_lb_listener_rule.api,
  ]
}
