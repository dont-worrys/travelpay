# Prometheus·Loki·Grafana·Alloy 운영 배치입니다. 중앙 저장·조회는 Data 호스트(Compose)에, 앱 로그 수집기는 앱 호스트마다 둡니다.
# 설정은 Data 묶음·앱 묶음(S3 + SSM association)으로 전달해 인스턴스를 교체하지 않고 반영합니다.
locals {
  # 로컬 Compose(compose.monitoring.yaml)와 같은 버전입니다. 공급망 고정을 위해 레지스트리에서 확인한 digest를 함께 씁니다.
  monitoring_images = {
    prometheus = "prom/prometheus:v3.16.0@sha256:e04987641b57704f408e31f6373ba4138ecf27cb4a165d24aabb6c38fddd0f06"
    loki       = "grafana/loki:3.6.20@sha256:be040ec94865dabe4821ce1c0f109452bd4b5acb566ae376672696eb43caedb7"
    grafana    = "grafana/grafana:13.3.0@sha256:488984b716c0862639f91a698adbe2862677a5a07bad42da5f03f3030883b205"
    alloy      = "grafana/alloy:v1.21.3@sha256:8ab871206bd2d8f89277f53abffee5f452a293980788d52f85fa183a87a505a4"
  }

  # payment-service에서 @Scheduled로 실행되는 recover()의 클래스입니다. 복구 스케줄러 규칙이 클래스별 실행 기록을 보므로,
  # 클래스 이름이 바뀌면 규칙이 실행 기록 없음으로 알립니다. 테스트가 payment-service 코드와 대조합니다.
  payment_recovery_schedulers = [
    "com.travelpay.payment.presentation.payment.PendingSettlementScheduler",
    "com.travelpay.payment.transfer.application.TransferRecoveryScheduler",
  ]

  # 로컬 대시보드와 송금 알림 규칙을 원본으로 두고 로컬 전용 값만 바꿉니다. 원본이 바뀌어 바꿀 곳의 수가 달라지면 테스트가 알립니다.
  local_grafana_root = "${path.module}/../monitoring/grafana"
  # 운영에는 환경이 production 하나뿐이라 로컬·부하 시험을 설명하는 문구도 운영 문구로 바꿉니다("local" 태그·저장된 선택값 포함).
  production_dashboard_text = replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(
    file("${local.local_grafana_root}/dashboards/travelpay-local.json"),
    "prometheus-local", "prometheus"),
    "loki-local", "loki"),
    "travelpay-local-perf", "travelpay-production"),
    "TRAVELPAY | Local Performance", "TRAVELPAY | Production"),
    "local|load-test", "production"),
    "docker-local|spring-local", "ecs-tasks|docker-data"),
    "\"local\"", "\"production\""),
    "local=로컬 개발 앱, load-test=전용 부하 시험 앱. 한 환경씩 선택합니다. 이 필터는 메트릭에만 적용되며 로그에는 적용하지 않습니다.", "운영 지표의 환경은 production 하나입니다."),
    "수집된 로컬 앱·개발 인프라 로그입니다.", "운영 앱(ECS Task)과 Data 호스트 컨테이너의 로그입니다."),
    "Trace ID는 로그 원문을 검색하며 메트릭 환경 필터는 로그에 적용되지 않습니다.", "Trace ID는 로그 원문을 검색합니다."),
  "메트릭 환경(local/load-test)을 한 번에 하나씩 선택해 서비스 상태", "운영 서비스 상태")
  production_dashboard_document = jsondecode(local.production_dashboard_text)
  # 고를 환경이 없으므로 환경 선택 칸을 숨깁니다(hide 2). 선택값은 위에서 production으로 바뀌어 처음 열 때도 빈 그래프가 아닙니다.
  production_dashboard = jsonencode(merge(local.production_dashboard_document, {
    templating = merge(local.production_dashboard_document.templating, {
      list = [
        for variable in local.production_dashboard_document.templating.list :
        variable.name == "environment" ? merge(variable, { hide = 2 }) : variable
      ]
    })
  }))
  production_transfer_alerts = join("\n", [
    "# 운영용: infra/monitoring의 로컬 송금 알림 규칙에서 데이터소스만 운영 Prometheus로 바꾼 파일입니다(monitoring.tf).",
    replace(
      file("${local.local_grafana_root}/provisioning/alerting/transfer-alerts.yml"),
      "datasourceUid: prometheus-local", "datasourceUid: prometheus",
    ),
  ])

  # Data 호스트 묶음에 들어가는 모니터링 설정입니다(apply-data-bundle.py가 설치 위치를 정합니다).
  monitoring_files = var.enable_data_foundation ? {
    "prometheus.yml" = templatefile("${path.module}/templates/monitoring/prometheus.yml.tftpl", {
      eureka_url = "http://${local.application_discovery_names.eureka}.${aws_service_discovery_private_dns_namespace.main[0].name}:${local.application_services.eureka.port}/eureka"
      dns_targets = [
        for key, service in { eureka = "eureka-server", mock_bank = "mock-bank" } : {
          dns_name                   = "${local.application_discovery_names[key]}.${aws_service_discovery_private_dns_namespace.main[0].name}"
          port                       = local.application_services[key].management_port
          service                    = service
        }
      ]
    })
    "loki.yml"                   = file("${path.module}/templates/monitoring/loki.yml")
    "data.alloy"                 = file("${path.module}/templates/monitoring/data.alloy")
    "grafana-datasources.yml"    = file("${path.module}/templates/monitoring/grafana-datasources.yml")
    "grafana-dashboards.yml"     = file("${path.module}/templates/monitoring/grafana-dashboards.yml")
    "grafana-alert-transfer.yml" = local.production_transfer_alerts
    "grafana-alert-operations.yml" = templatefile("${path.module}/templates/monitoring/grafana-alert-operations.yml.tftpl", {
      services            = sort([for service in local.application_services : service.name])
      recovery_schedulers = local.payment_recovery_schedulers
    })
    "dashboard-production.json" = local.production_dashboard
  } : {}
  # Compose가 감지하지 못하는 bind 설정 파일의 변화를 컨테이너별 해시로 드러냅니다(monitoring-compose.yaml.tftpl).
  monitoring_config_sha256 = var.enable_data_foundation ? {
    prometheus = sha256(local.monitoring_files["prometheus.yml"])
    loki       = sha256(local.monitoring_files["loki.yml"])
    alloy      = sha256(local.monitoring_files["data.alloy"])
    grafana = sha256(jsonencode({
      for name in ["grafana-datasources.yml", "grafana-dashboards.yml", "grafana-alert-transfer.yml", "grafana-alert-operations.yml", "dashboard-production.json"] :
      name => local.monitoring_files[name]
    }))
  } : null
  # Redis·Kafka(compose.yaml, travelpay-data.service)와 나눈 모니터링 실행 정의입니다. 모니터링 준비나 상태 확인이
  # 실패해도 Redis·Kafka의 시작·반영을 막지 않습니다.
  monitoring_runtime_files = var.enable_data_foundation ? {
    "monitoring-compose.yaml" = templatefile("${path.module}/templates/monitoring-compose.yaml.tftpl", {
      prometheus_image         = local.monitoring_images.prometheus, loki_image = local.monitoring_images.loki,
      grafana_image            = local.monitoring_images.grafana, alloy_image = local.monitoring_images.alloy,
      prometheus_config_sha256 = local.monitoring_config_sha256.prometheus, loki_config_sha256 = local.monitoring_config_sha256.loki,
      grafana_config_sha256    = local.monitoring_config_sha256.grafana, alloy_config_sha256 = local.monitoring_config_sha256.alloy,
      grafana_public_url       = var.enable_public_entry ? "https://${local.grafana_domain_name}" : ""
      # 상태 확인(apply-data-bundle.py)은 루프백을 보므로 공개 때도 루프백을 남기고 ALB용 사설 IP를 더합니다.
      grafana_ports = jsonencode(concat(
        ["127.0.0.1:3000:3000"],
        var.enable_public_entry ? ["$${DATA_PRIVATE_IP:?}:${local.tcp_connections.alb_grafana.port}:3000"] : [],
      ))
    })
    "travelpay-monitoring.service" = file("${path.module}/templates/travelpay-monitoring.service")
    "prepare-monitoring.py"        = file("${path.module}/scripts/prepare-monitoring.py")
  } : {}

  # 앱 호스트 묶음은 호스트가 있을 때만 전달합니다. 대상이 없으면 association이 성공 상태에 이를 수 없습니다.
  enable_app_config = var.enable_app_foundation && var.enable_data_foundation && var.app_host_count > 0
  app_bundle_key    = "app/bundle.json.gz"
  app_bundle = local.enable_app_config ? {
    "config.alloy" = templatefile("${path.module}/templates/monitoring/app.alloy.tftpl", {
      loki_host = "${aws_service_discovery_service.data[0].name}.${aws_service_discovery_private_dns_namespace.main[0].name}"
      loki_port = local.tcp_connections.collector_loki.port
    })
    "travelpay-alloy.service" = templatefile("${path.module}/templates/travelpay-alloy.service.tftpl", {
      alloy_image = local.monitoring_images.alloy
    })
    "apply-app-bundle.py" = file("${path.module}/scripts/apply-app-bundle.py")
  } : {}
  app_bundle_json = jsonencode(local.app_bundle)
}

resource "aws_s3_object" "app_bundle" {
  count = local.enable_app_config ? 1 : 0

  bucket         = aws_s3_bucket.data_config[0].id
  key            = local.app_bundle_key
  content_base64 = base64gzip(local.app_bundle_json)
  content_type   = "application/gzip"

  depends_on = [
    aws_s3_bucket_public_access_block.data_config,
    aws_s3_bucket_ownership_controls.data_config,
    aws_s3_bucket_server_side_encryption_configuration.data_config,
    aws_s3_bucket_policy.data_config,
  ]
}

resource "aws_iam_role_policy" "app_config_read" {
  count = local.enable_app_config ? 1 : 0

  name = "read-app-config-bundle"
  role = aws_iam_role.app_host.name
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = ["s3:GetObject"]
      Resource = ["${aws_s3_bucket.data_config[0].arn}/${local.app_bundle_key}"]
    }]
  })
}

# 태그로 대상을 정해 ASG가 새로 띄운 앱 호스트에도 SSM이 같은 묶음을 자동으로 적용합니다.
resource "aws_ssm_association" "app_apply" {
  count = local.enable_app_config ? 1 : 0

  name             = aws_ssm_document.config_apply[0].name
  association_name = "${local.runtime_name_prefix}-app-apply"
  max_concurrency  = "1"
  max_errors       = "0"
  targets {
    key    = "tag:Role"
    values = ["app_host"]
  }
  parameters = {
    Bucket      = aws_s3_bucket.data_config[0].id
    Key         = aws_s3_object.app_bundle[0].key
    Sha256      = sha256(local.app_bundle_json)
    Region      = local.runtime_region
    ApplyScript = "apply-app-bundle.py"
  }
  # Data association과 같이 생성할 때만 성공을 기다리므로, 묶음·문서가 바뀌면 다시 만들어 실패가 apply에 드러나게 합니다.
  wait_for_success_timeout_seconds = 1800

  lifecycle {
    replace_triggered_by = [aws_s3_object.app_bundle[0], aws_ssm_document.config_apply[0]]
  }

  # S3 조회 권한은 참조로 드러나지 않는 실행 조건입니다.
  depends_on = [aws_iam_role_policies_exclusive.hosts, aws_autoscaling_group.app]
}

# AWS 기본 지표는 이 대시보드로 봅니다. Grafana는 컨테이너가 인스턴스 역할 자격 증명을 받지 못하도록(IMDS hop 1)
# AWS API에 접근하지 않고 Prometheus·Loki만 조회합니다. CloudWatch 대시보드는 3개까지 추가 요금이 없습니다.
locals {
  dashboard_metric_widgets = concat(
    var.enable_data_foundation ? [
      for metric in ["CPUUtilization", "FreeableMemory", "DatabaseConnections", "FreeStorageSpace"] : {
        title   = "RDS ${metric}", stat = metric == "FreeableMemory" || metric == "FreeStorageSpace" ? "Minimum" : "Maximum",
        metrics = [["AWS/RDS", metric, "DBInstanceIdentifier", aws_db_instance.main[0].identifier]]
      }
      ] : [], var.enable_data_foundation ? [
      {
        title   = "Data 호스트 메모리(%)", stat = "Maximum",
        metrics = [["Travelpay/Hosts", "mem_used_percent", "InstanceId", aws_instance.data[0].id]]
      },
      {
        title = "Data 호스트 디스크(%)", stat = "Maximum",
        metrics = [
          for name, path in merge({ root = "/" }, { for name, spec in local.data_volume_specs : name => spec.mount }) :
          ["Travelpay/Hosts", "disk_used_percent", "InstanceId", aws_instance.data[0].id, "path", path, "fstype", name == "root" ? "xfs" : "ext4"]
        ]
      },
      ] : [], var.enable_app_foundation ? [
      {
        title = "앱 호스트 메모리·디스크(%)", stat = "Maximum",
        metrics = [
          ["Travelpay/Hosts", "mem_used_percent", "AutoScalingGroupName", aws_autoscaling_group.app[0].name],
          ["Travelpay/Hosts", "disk_used_percent", "AutoScalingGroupName", aws_autoscaling_group.app[0].name],
        ]
      },
      {
        title = "ECS 예약·사용률(%)", stat = "Maximum",
        metrics = [
          for metric in ["CPUReservation", "MemoryReservation", "CPUUtilization", "MemoryUtilization"] :
          ["AWS/ECS", metric, "ClusterName", aws_ecs_cluster.main[0].name]
        ]
      },
      ] : [], var.enable_app_runtime ? [
      {
        title = "ALB 요청·5xx(5분 합계)", stat = "Sum",
        metrics = [
          for metric in ["RequestCount", "HTTPCode_ELB_5XX_Count", "HTTPCode_Target_5XX_Count"] :
          ["AWS/ApplicationELB", metric, "LoadBalancer", aws_lb.api[0].arn_suffix]
        ]
      },
      {
        title = "Gateway Target 상태", stat = "Minimum",
        metrics = [
          for metric in ["HealthyHostCount", "UnHealthyHostCount"] :
          ["AWS/ApplicationELB", metric, "TargetGroup", aws_lb_target_group.gateway[0].arn_suffix, "LoadBalancer", aws_lb.api[0].arn_suffix]
        ]
      },
    ] : [],
  )
}

resource "aws_cloudwatch_dashboard" "operations" {
  count = var.enable_data_foundation ? 1 : 0

  dashboard_name = local.runtime_name_prefix
  dashboard_body = jsonencode({
    widgets = concat(
      [{
        type       = "alarm", width = 24, height = 4,
        properties = { title = "경보 상태", alarms = sort([for alarm in aws_cloudwatch_metric_alarm.service : alarm.arn]) }
      }],
      [
        for widget in local.dashboard_metric_widgets : {
          type = "metric", width = 8, height = 6,
          properties = {
            title   = widget.title, region = local.runtime_region, view = "timeSeries", stat = widget.stat, period = 300,
            metrics = widget.metrics
          }
        }
      ],
    )
  })
}
