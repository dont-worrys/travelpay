# Prometheus·Loki·Grafana·Alloy 배치가 로컬 대시보드·알림과 같은 레이블을 쓰고, 공개 범위·권한·공급망 고정을 지키는지 검사합니다.
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
  mock_resource "aws_lb" {
    defaults = {
      arn        = "arn:aws:elasticloadbalancing:ap-northeast-2:123456789012:loadbalancer/app/travelpay-production/0123456789abcdef"
      arn_suffix = "app/travelpay-production/0123456789abcdef"
    }
  }
  mock_resource "aws_lb_listener" {
    defaults = { arn = "arn:aws:elasticloadbalancing:ap-northeast-2:123456789012:listener/app/travelpay-production/0123456789abcdef/0123456789abcdef" }
  }
  mock_resource "aws_lb_target_group" {
    defaults = {
      arn        = "arn:aws:elasticloadbalancing:ap-northeast-2:123456789012:targetgroup/travelpay-production-gateway/0123456789abcdef"
      arn_suffix = "targetgroup/travelpay-production-gateway/0123456789abcdef"
    }
  }
  mock_resource "aws_acm_certificate" {
    defaults = { arn = "arn:aws:acm:ap-northeast-2:123456789012:certificate/00000000-0000-0000-0000-000000000000" }
  }
  mock_resource "aws_sns_topic" {
    defaults = { arn = "arn:aws:sns:ap-northeast-2:123456789012:travelpay-production-alerts" }
  }
  mock_resource "aws_cloudwatch_metric_alarm" {
    defaults = { arn = "arn:aws:cloudwatch:ap-northeast-2:123456789012:alarm:travelpay-production-test" }
  }
}

override_resource {
  override_during = plan
  target          = aws_db_instance.main[0]
  values = {
    address            = "travelpay-production.test.ap-northeast-2.rds.amazonaws.com"
    master_user_secret = [{ secret_arn = "arn:aws:secretsmanager:ap-northeast-2:123456789012:secret:rds-test", kms_key_id = "", secret_status = "active" }]
  }
}

override_resource {
  override_during = plan
  target          = aws_ebs_volume.data["kafka"]
  values          = { id = "vol-0123456789abcdef0" }
}

override_resource {
  override_during = plan
  target          = aws_ebs_volume.data["monitoring"]
  values          = { id = "vol-0123456789abcdef1" }
}

override_resource {
  override_during = plan
  target          = aws_instance.data[0]
  values          = { id = "i-0123456789abcdef0", private_ip = "10.20.20.10" }
}

override_resource {
  override_during = plan
  target          = aws_s3_bucket.data_config[0]
  values = {
    id  = "travelpay-production-data-config-123456789012"
    arn = "arn:aws:s3:::travelpay-production-data-config-123456789012"
  }
}

variables {
  aws_account_id                   = "123456789012"
  alert_email                      = "ops@example.com"
  availability_zones               = { a = "ap-northeast-2a", b = "ap-northeast-2c" }
  enable_app_foundation            = true
  enable_data_foundation           = true
  enable_app_runtime               = true
  enable_app_services              = false
  enable_public_entry              = false
  app_host_count                   = 2
  ecs_ami_id                       = "ami-0123456789abcdef0"
  data_ami_id                      = "ami-0123456789abcdef1"
  enable_database_bootstrap_access = false
  enable_github_deployment         = false
  application_images = {
    gateway   = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/travelpay/gateway@sha256:1111111111111111111111111111111111111111111111111111111111111111"
    eureka    = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/travelpay/eureka-server@sha256:2222222222222222222222222222222222222222222222222222222222222222"
    user      = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/travelpay/user-service@sha256:3333333333333333333333333333333333333333333333333333333333333333"
    wallet    = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/travelpay/wallet-service@sha256:4444444444444444444444444444444444444444444444444444444444444444"
    payment   = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/travelpay/payment-service@sha256:5555555555555555555555555555555555555555555555555555555555555555"
    exchange  = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/travelpay/exchange-service@sha256:6666666666666666666666666666666666666666666666666666666666666666"
    ledger    = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/travelpay/ledger-service@sha256:7777777777777777777777777777777777777777777777777777777777777777"
    mock_bank = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/travelpay/mock-bank@sha256:8888888888888888888888888888888888888888888888888888888888888888"
  }
}

run "data_host_monitoring_is_pinned_private_and_least_privileged" {
  command = plan

  # 로컬 Compose(compose.monitoring.yaml)와 같은 버전을 digest로 고정합니다. 로컬 버전만 바뀌면 여기서 알립니다.
  assert {
    condition = alltrue([
      for name in ["prometheus", "loki", "grafana", "alloy"] :
      yamldecode(jsondecode(local.data_bundle_json)["monitoring-compose.yaml"]).services[name].image == local.monitoring_images[name] &&
      can(regex("@sha256:[0-9a-f]{64}$", local.monitoring_images[name])) &&
      split("@", local.monitoring_images[name])[0] == yamldecode(file("${path.module}/../../compose.monitoring.yaml")).services[name].image
    ])
    error_message = "모니터링 이미지는 로컬과 같은 버전을 digest로 고정해 써야 합니다."
  }

  # 모니터링 준비·상태 확인이 실패해도 Redis·Kafka의 시작·반영을 막지 않도록 Compose 프로젝트와 유닛을 나눕니다.
  assert {
    condition = (
      sort(keys(yamldecode(jsondecode(local.data_bundle_json)["compose.yaml"]).services)) == tolist(["kafka", "redis-cache", "redis-session"]) &&
      sort(keys(yamldecode(jsondecode(local.data_bundle_json)["monitoring-compose.yaml"]).services)) == tolist(["alloy", "grafana", "loki", "prometheus"]) &&
      yamldecode(jsondecode(local.data_bundle_json)["monitoring-compose.yaml"]).name == "travelpay-production-monitoring" &&
      !strcontains(jsondecode(local.data_bundle_json)["travelpay-data.service"], "prepare-monitoring") &&
      !strcontains(jsondecode(local.data_bundle_json)["prepare-data.py"], "GRAFANA") &&
      # 모니터링 유닛의 Compose 명령은 모두 모니터링 정의만 다루고(기본 compose.yaml은 Redis·Kafka), 시작·반영 전마다 준비합니다.
      [for line in split("\n", jsondecode(local.data_bundle_json)["travelpay-monitoring.service"]) : line if startswith(line, "Exec")] == [
        "ExecStartPre=/usr/bin/python3 /opt/travelpay/data/prepare-monitoring.py",
        "ExecStart=/usr/bin/docker compose --file monitoring-compose.yaml --env-file runtime.env up --detach --wait --wait-timeout 300",
        "ExecReload=/usr/bin/python3 /opt/travelpay/data/prepare-monitoring.py",
        "ExecReload=/usr/bin/docker compose --file monitoring-compose.yaml --env-file runtime.env up --detach --wait --wait-timeout 300",
        "ExecStop=/usr/bin/docker compose --file monitoring-compose.yaml --env-file runtime.env stop --timeout 30",
      ] &&
      alltrue([for line in split("\n", jsondecode(local.data_bundle_json)["travelpay-data.service"]) : !strcontains(line, "monitoring") if startswith(line, "Exec")]) &&
      can(regex("\\nAfter=[^\\n]*travelpay-data\\.service", jsondecode(local.data_bundle_json)["travelpay-monitoring.service"])) &&
      !can(regex("\\n(Requires|Requisite|BindsTo|PartOf)=[^\\n]*travelpay-data", jsondecode(local.data_bundle_json)["travelpay-monitoring.service"]))
    )
    error_message = "모니터링은 Redis·Kafka와 다른 Compose 프로젝트·유닛·준비 스크립트로 실행해야 합니다."
  }

  assert {
    condition = (
      yamldecode(jsondecode(local.data_bundle_json)["monitoring-compose.yaml"]).services.prometheus.ports == ["127.0.0.1:9090:9090"] &&
      yamldecode(jsondecode(local.data_bundle_json)["monitoring-compose.yaml"]).services.grafana.ports == ["127.0.0.1:3000:3000"] &&
      yamldecode(jsondecode(local.data_bundle_json)["monitoring-compose.yaml"]).services.alloy.ports == ["127.0.0.1:12345:12345"] &&
      yamldecode(jsondecode(local.data_bundle_json)["monitoring-compose.yaml"]).services.loki.ports == ["$${DATA_PRIVATE_IP:?}:${local.tcp_connections.collector_loki.port}:3100"]
    )
    error_message = "Grafana·Prometheus·Alloy는 호스트 루프백에만, Loki는 수집기가 보내는 사설 IP의 SG 허용 포트에만 열어야 합니다."
  }

  assert {
    condition = alltrue([
      for name in ["prometheus", "loki", "grafana", "alloy"] : (
        yamldecode(jsondecode(local.data_bundle_json)["monitoring-compose.yaml"]).services[name].cap_drop == ["ALL"] &&
        yamldecode(jsondecode(local.data_bundle_json)["monitoring-compose.yaml"]).services[name].security_opt == ["no-new-privileges:true"] &&
        can(regex("^[0-9]+m$", yamldecode(jsondecode(local.data_bundle_json)["monitoring-compose.yaml"]).services[name].mem_limit))
      )
    ])
    error_message = "모니터링 컨테이너는 추가 권한 없이 실행하고 메모리 상한을 둬야 합니다."
  }

  assert {
    condition = (
      yamldecode(jsondecode(local.data_bundle_json)["monitoring-compose.yaml"]).services.grafana.environment.GF_AUTH_ANONYMOUS_ENABLED == "false" &&
      yamldecode(jsondecode(local.data_bundle_json)["monitoring-compose.yaml"]).services.grafana.environment.GF_USERS_ALLOW_SIGN_UP == "false" &&
      yamldecode(jsondecode(local.data_bundle_json)["monitoring-compose.yaml"]).services.grafana.environment.GF_SECURITY_ADMIN_PASSWORD__FILE == "/run/secrets/grafana-admin-password" &&
      !contains(keys(yamldecode(jsondecode(local.data_bundle_json)["monitoring-compose.yaml"]).services.grafana.environment), "GF_SECURITY_ADMIN_PASSWORD")
    )
    error_message = "Grafana는 익명·가입을 막고 관리자 암호를 설정 파일이 아닌 비밀 파일로 받아야 합니다."
  }

  assert {
    condition = alltrue([
      for name in ["prometheus", "loki", "grafana", "alloy"] :
      yamldecode(jsondecode(local.data_bundle_json)["monitoring-compose.yaml"]).services[name].environment.TRAVELPAY_CONFIG_SHA256 == local.monitoring_config_sha256[name]
    ])
    error_message = "설정 파일이 바뀌면 해당 컨테이너만 다시 만들도록 설정 해시를 Compose 정의에 넣어야 합니다."
  }
}

run "metrics_and_logs_use_the_local_dashboard_labels" {
  command = plan

  assert {
    condition = (
      [for config in yamldecode(jsondecode(local.data_bundle_json)["prometheus.yml"]).scrape_configs : config.job_name] == ["prometheus", "loki", "alloy", "spring-jvm", "spring-jvm-dns"] &&
      one([for config in yamldecode(jsondecode(local.data_bundle_json)["prometheus.yml"]).scrape_configs : config if config.job_name == "spring-jvm"]).eureka_sd_configs[0].server == "http://eureka.travelpay.internal:8761/eureka" &&
      [for target in one([for config in yamldecode(jsondecode(local.data_bundle_json)["prometheus.yml"]).scrape_configs : config if config.job_name == "spring-jvm-dns"]).dns_sd_configs : [target.names[0], target.port]] == [["eureka.travelpay.internal", 8761], ["mock-bank.travelpay.internal", 8090]]
    )
    error_message = "업무 서비스는 Eureka로, Eureka·Mock Bank는 Cloud Map 사설 DNS의 관리 포트로 찾아야 합니다."
  }

  # 서비스 레이블이 틀리면 수집 중단 규칙이 그 서비스를 계속 경보로 봅니다. Gateway는 Eureka 메타데이터의 관리 포트로 수집합니다.
  assert {
    condition = (
      [
        for rule in one([for config in yamldecode(jsondecode(local.data_bundle_json)["prometheus.yml"]).scrape_configs : config if config.job_name == "spring-jvm-dns"]).relabel_configs :
        [rule.regex, rule.replacement] if lookup(rule, "source_labels", null) == ["__meta_dns_name"]
      ] == [["eureka\\.travelpay\\.internal", "eureka-server"], ["mock-bank\\.travelpay\\.internal", "mock-bank"]] &&
      [
        for rule in one([for config in yamldecode(jsondecode(local.data_bundle_json)["prometheus.yml"]).scrape_configs : config if config.job_name == "spring-jvm"]).relabel_configs :
        [rule.target_label, rule.replacement] if lookup(rule, "source_labels", null) == ["__meta_eureka_app_instance_ip_addr", "__meta_eureka_app_instance_metadata_management_port"]
      ] == [["__address__", "$1:$2"]]
    )
    error_message = "Eureka·Mock Bank의 service 레이블과 Eureka 대상의 관리 포트 주소가 맞아야 합니다."
  }

  assert {
    condition = alltrue([
      for config in yamldecode(jsondecode(local.data_bundle_json)["prometheus.yml"]).scrape_configs :
      contains([for rule in config.relabel_configs : rule.replacement if lookup(rule, "target_label", "") == "environment"], "production")
      if startswith(config.job_name, "spring-jvm")
    ]) && strcontains(jsondecode(local.data_bundle_json)["prometheus.yml"], "replacement: spring-jvm")
    error_message = "앱 지표는 로컬 대시보드와 같은 job=spring-jvm, environment=production 레이블을 가져야 합니다."
  }

  assert {
    condition = (
      yamldecode(jsondecode(local.data_bundle_json)["loki.yml"]).limits_config.retention_period == "168h" &&
      yamldecode(jsondecode(local.data_bundle_json)["loki.yml"]).compactor.retention_enabled &&
      !yamldecode(jsondecode(local.data_bundle_json)["loki.yml"]).analytics.reporting_enabled &&
      strcontains(jsondecode(local.data_bundle_json)["monitoring-compose.yaml"], "--storage.tsdb.retention.time=7d") &&
      contains(yamldecode(jsondecode(local.data_bundle_json)["monitoring-compose.yaml"]).services.alloy.command, "--disable-reporting") &&
      strcontains(local.app_bundle["travelpay-alloy.service"], " --disable-reporting ")
    )
    error_message = "로그·지표는 CloudWatch와 같은 7일 동안 보관하고 외부로 사용 통계를 보내지 않아야 합니다."
  }

  assert {
    condition = (
      strcontains(local.app_bundle["config.alloy"], "url = \"http://data.travelpay.internal:3100/loki/api/v1/push\"") &&
      strcontains(local.app_bundle["config.alloy"], "com_amazonaws_ecs_container_name") &&
      strcontains(local.app_bundle["config.alloy"], "job = \"ecs-tasks\", environment = \"production\"") &&
      strcontains(jsondecode(local.data_bundle_json)["data.alloy"], "job = \"docker-data\", environment = \"production\"") &&
      yamldecode(jsondecode(local.data_bundle_json)["monitoring-compose.yaml"]).services.alloy.hostname == "travelpay-data"
    )
    error_message = "앱·Data 로그는 대시보드가 찾는 job과 서비스 이름 레이블로 Data 호스트의 Loki에 보내야 합니다."
  }

  # 실제 실행에서 찾은 결함 두 가지를 고정합니다. relabel_rules의 drop은 레이블만 지우고 pause 로그를 그대로 보냈고,
  # 권한을 모두 뺀 root는 이미지의 /var/lib/alloy(alloy 사용자 전용)에 들어가지 못해 Alloy가 시작하지 못했습니다.
  assert {
    condition = (
      can(regex("\\n\\s*targets\\s*=\\s*discovery\\.relabel\\.ecs\\.output\\n", local.app_bundle["config.alloy"])) &&
      can(regex("regex\\s*=\\s*\"~internal~ecs~pause\"\\n\\s*action\\s*=\\s*\"drop\"", local.app_bundle["config.alloy"])) &&
      can(regex("\\n\\s*targets\\s*=\\s*discovery\\.relabel\\.data\\.output\\n", jsondecode(local.data_bundle_json)["data.alloy"])) &&
      can(regex("regex\\s*=\\s*\"travelpay-production-\\(data\\|monitoring\\)\"\\n\\s*action\\s*=\\s*\"keep\"", jsondecode(local.data_bundle_json)["data.alloy"])) &&
      !can(regex("\\n\\s*relabel_rules\\s*=", join("\n", [local.app_bundle["config.alloy"], jsondecode(local.data_bundle_json)["data.alloy"]]))) &&
      strcontains(local.app_bundle["travelpay-alloy.service"], "--volume /var/lib/travelpay/alloy:/alloy-data") &&
      strcontains(local.app_bundle["travelpay-alloy.service"], "--storage.path=/alloy-data") &&
      strcontains(jsondecode(local.data_bundle_json)["monitoring-compose.yaml"], "--storage.path=/alloy-data") &&
      !can(regex("storage\\.path=/var/lib/alloy|:/var/lib/alloy/data|target: /var/lib/alloy", join("\n", [local.app_bundle["travelpay-alloy.service"], jsondecode(local.data_bundle_json)["monitoring-compose.yaml"]])))
    )
    error_message = "pause 컨테이너는 발견 단계에서 빼고, Alloy 저장 경로는 권한 없는 root가 쓸 수 있는 곳이어야 합니다."
  }
}

run "production_dashboard_and_rules_come_from_the_local_files" {
  command = plan

  # 원본에서 바꿀 곳의 수입니다. 원본이 바뀌어 수가 달라지면 운영용 변환을 다시 검토해야 합니다.
  assert {
    condition = {
      for token in [
        "prometheus-local", "loki-local", "travelpay-local-perf", "TRAVELPAY | Local Performance", "local|load-test", "docker-local|spring-local", "\"local\"",
        "local=로컬 개발 앱, load-test=전용 부하 시험 앱. 한 환경씩 선택합니다. 이 필터는 메트릭에만 적용되며 로그에는 적용하지 않습니다.",
        "수집된 로컬 앱·개발 인프라 로그입니다.", "Trace ID는 로그 원문을 검색하며 메트릭 환경 필터는 로그에 적용되지 않습니다.",
        "메트릭 환경(local/load-test)을 한 번에 하나씩 선택해 서비스 상태",
      ] :
      token => length(split(token, file("${path.module}/../monitoring/grafana/dashboards/travelpay-local.json"))) - 1
      } == {
      "prometheus-local"                                                                   = 23, "loki-local" = 1, "travelpay-local-perf" = 1,
      "TRAVELPAY | Local Performance"                                                      = 1, "local|load-test" = 2, "docker-local|spring-local" = 1, "\"local\"" = 3,
      "local=로컬 개발 앱, load-test=전용 부하 시험 앱. 한 환경씩 선택합니다. 이 필터는 메트릭에만 적용되며 로그에는 적용하지 않습니다." = 1,
      "수집된 로컬 앱·개발 인프라 로그입니다."                                                             = 1, "Trace ID는 로그 원문을 검색하며 메트릭 환경 필터는 로그에 적용되지 않습니다." = 1,
      "메트릭 환경(local/load-test)을 한 번에 하나씩 선택해 서비스 상태"                                       = 1,
    }
    error_message = "로컬 대시보드의 로컬 전용 값 위치가 바뀌었습니다. 운영용 변환(monitoring.tf)을 다시 검토하세요."
  }

  assert {
    condition = (
      jsondecode(jsondecode(local.data_bundle_json)["dashboard-production.json"]).uid == "travelpay-production" &&
      jsondecode(jsondecode(local.data_bundle_json)["dashboard-production.json"]).title == "TRAVELPAY | Production" &&
      one([for variable in jsondecode(jsondecode(local.data_bundle_json)["dashboard-production.json"]).templating.list : variable.current if variable.name == "environment"]) == { text = "production", value = "production" } &&
      alltrue([for token in ["prometheus-local", "loki-local", "travelpay-local-perf", "local|load-test", "docker-local"] : !strcontains(jsondecode(local.data_bundle_json)["dashboard-production.json"], token)])
    )
    error_message = "운영 대시보드에 로컬 데이터소스·환경·로그 job이 남으면 빈 화면이 됩니다."
  }

  # 운영에는 환경이 하나뿐이라 환경 선택을 숨기고, 로컬·부하 시험을 설명하는 문구를 남기지 않습니다.
  assert {
    condition = (
      one([for variable in jsondecode(jsondecode(local.data_bundle_json)["dashboard-production.json"]).templating.list : variable.hide if variable.name == "environment"]) == 2 &&
      jsondecode(jsondecode(local.data_bundle_json)["dashboard-production.json"]).tags == ["travelpay", "production", "monitoring"] &&
      length(regexall("local|load-test|로컬|부하", jsondecode(local.data_bundle_json)["dashboard-production.json"])) == 0
    )
    error_message = "운영 대시보드는 환경 선택을 숨기고 local·load-test·로컬·부하 문구가 없어야 합니다."
  }

  assert {
    condition = (
      !strcontains(jsondecode(local.data_bundle_json)["grafana-alert-transfer.yml"], "prometheus-local") &&
      length(split("datasourceUid: prometheus\n", jsondecode(local.data_bundle_json)["grafana-alert-transfer.yml"])) - 1 == 2 &&
      [for rule in yamldecode(jsondecode(local.data_bundle_json)["grafana-alert-transfer.yml"]).groups[0].rules : rule.uid] == ["transfer-manual-review", "transfer-processing-stuck"]
    )
    error_message = "송금 알림 규칙 두 개는 로컬 규칙 그대로 운영 Prometheus를 봐야 합니다."
  }

  assert {
    condition = (
      [for rule in yamldecode(jsondecode(local.data_bundle_json)["grafana-alert-operations.yml"]).groups[0].rules : rule.uid] == ["service-metrics-missing", "payment-recovery-scheduler-stalled"] &&
      [for rule in yamldecode(jsondecode(local.data_bundle_json)["grafana-alert-operations.yml"]).groups[0].rules : [rule.noDataState, rule.for]] == [["Alerting", "2m"], ["OK", "2m"]] &&
      # 식의 뜻(0으로 채우기·실행 기록 없음 보완)은 실제 Prometheus·Grafana로 확인했으므로 식 전체를 고정합니다.
      yamldecode(jsondecode(local.data_bundle_json)["grafana-alert-operations.yml"]).groups[0].rules[0].data[0].model.expr == join(" or ", concat(
        ["sum by (service) (up{job=\"spring-jvm\",environment=\"production\"} == 1)"],
        [for name in sort([for service in local.application_services : service.name]) : "label_replace(vector(0), \"service\", \"${name}\", \"\", \"\")"]
      )) &&
      yamldecode(jsondecode(local.data_bundle_json)["grafana-alert-operations.yml"]).groups[0].rules[1].data[0].model.expr == join(" or ", concat(
        ["sum by (code_namespace) (increase(tasks_scheduled_execution_seconds_count{job=\"spring-jvm\",environment=\"production\",service=\"payment-service\",code_function=\"recover\"}[2m]))"],
        [for scheduler in local.payment_recovery_schedulers : "label_replace(count(up{job=\"spring-jvm\",environment=\"production\",service=\"payment-service\"} == 1) * 0, \"code_namespace\", \"${scheduler}\", \"\", \"\")"]
      ))
    )
    error_message = "수집 중단 규칙은 8개 서비스 모두를 0으로 채워 감시하고, 복구 스케줄러 규칙은 두 스케줄러의 실행 횟수와 실행 기록 없음을 봐야 합니다."
  }

  # 복구 스케줄러 클래스 목록이 payment-service 코드와 같은지 봅니다(담당 서비스 코드는 읽기만 합니다).
  assert {
    condition = length(local.payment_recovery_schedulers) == 2 && alltrue([
      for scheduler in local.payment_recovery_schedulers :
      can(regex("@Scheduled\\([^)]*\\)\\s+public void recover\\(\\)", file("${path.module}/../../payment-service/src/main/java/${replace(scheduler, ".", "/")}.java")))
    ])
    error_message = "복구 스케줄러 규칙의 클래스는 payment-service에서 @Scheduled recover()를 가진 클래스여야 합니다."
  }

  assert {
    condition     = jsondecode(local.data_bundle_json)["grafana-datasources.yml"] == file("${path.module}/templates/monitoring/grafana-datasources.yml") && strcontains(jsondecode(local.data_bundle_json)["grafana-datasources.yml"], "/d/travelpay-production?")
    error_message = "로그의 Trace ID 연결은 운영 대시보드로 가야 합니다."
  }
}

run "app_host_collector_is_delivered_by_tag_with_minimal_read_access" {
  command = plan

  assert {
    condition = (
      one(aws_ssm_association.app_apply[0].targets).key == "tag:Role" &&
      one(aws_ssm_association.app_apply[0].targets).values == tolist(["app_host"]) &&
      aws_ssm_association.app_apply[0].parameters["ApplyScript"] == "apply-app-bundle.py" &&
      aws_ssm_association.app_apply[0].parameters["Key"] == "app/bundle.json.gz" &&
      aws_ssm_association.app_apply[0].parameters["Sha256"] == sha256(local.app_bundle_json) &&
      aws_ssm_association.app_apply[0].name == aws_ssm_document.config_apply[0].name
    )
    error_message = "앱 호스트 수집기는 Role=app_host 태그로 새 호스트까지 자동 적용되고 이 묶음의 해시를 확인해야 합니다."
  }

  assert {
    condition = (
      jsondecode(aws_iam_role_policy.app_config_read[0].policy).Statement[0].Action == ["s3:GetObject"] &&
      jsondecode(aws_iam_role_policy.app_config_read[0].policy).Statement[0].Resource == ["arn:aws:s3:::travelpay-production-data-config-123456789012/app/bundle.json.gz"] &&
      contains(aws_iam_role_policies_exclusive.hosts["app_host"].policy_names, "read-app-config-bundle")
    )
    error_message = "앱 호스트 역할은 앱 묶음 객체 하나만 읽고, 그 정책이 역할의 관리 목록에 있어야 합니다."
  }

  assert {
    condition = (
      sort(keys(local.app_bundle)) == tolist(["apply-app-bundle.py", "config.alloy", "travelpay-alloy.service"]) &&
      strcontains(local.app_bundle["travelpay-alloy.service"], local.monitoring_images.alloy) &&
      strcontains(local.app_bundle["travelpay-alloy.service"], "--memory 256m") &&
      strcontains(local.app_bundle["travelpay-alloy.service"], "--cap-drop ALL") &&
      strcontains(local.app_bundle["travelpay-alloy.service"], "--publish 127.0.0.1:12345:12345")
    )
    error_message = "앱 호스트 수집기는 고정한 이미지·메모리 상한·최소 권한으로 실행하고 준비 확인 포트는 루프백에만 열어야 합니다."
  }

  assert {
    condition = (
      length(jsondecode(aws_cloudwatch_dashboard.operations[0].dashboard_body).widgets) == 11 &&
      jsondecode(aws_cloudwatch_dashboard.operations[0].dashboard_body).widgets[0].type == "alarm"
    )
    error_message = "CloudWatch 대시보드는 경보 상태와 RDS·호스트·ECS·ALB 지표를 함께 보여야 합니다."
  }
}

run "no_app_collector_without_app_hosts" {
  command = plan
  variables {
    app_host_count = 0
  }

  assert {
    condition     = length(aws_ssm_association.app_apply) == 0 && length(aws_s3_object.app_bundle) == 0 && length(aws_iam_role_policy.app_config_read) == 0
    error_message = "앱 호스트가 없으면 association이 성공 상태에 이를 수 없으므로 만들지 않아야 합니다."
  }
}
