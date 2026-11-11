# 경보 내용은 지표 이름·임계값뿐이라 비밀이 없어 주제를 암호화하지 않습니다.
# AWS 관리형 키(aws/sns)는 키 정책을 바꿀 수 없어 CloudWatch가 게시하지 못하고, 고객 관리형 키는 월 비용이 듭니다.
resource "aws_sns_topic" "alerts" {
  count = var.enable_app_foundation || var.enable_data_foundation ? 1 : 0

  name = "${local.runtime_name_prefix}-alerts"
}

# 이메일 구독은 받는 사람이 확인 링크를 눌러야 활성화됩니다. 그전까지 경보는 전달되지 않습니다.
resource "aws_sns_topic_subscription" "alert_email" {
  count = length(aws_sns_topic.alerts)

  topic_arn = aws_sns_topic.alerts[0].arn
  protocol  = "email"
  endpoint  = var.alert_email
}

locals {
  # 경보 하나가 한 줄입니다. 5분 단위로 보고 periods번 연속 넘으면 알립니다.
  # 상태 값(CPU·메모리·저장 공간·연결)은 일시적인 튀는 값이나 새 지표의 첫 공백에 울리지 않도록 15분(3번) 연속을 봅니다.
  # missing: 지표가 끊기는 것이 장애인 호스트는 breaching, 요청이 없을 때 지표가 없는 ALB는 notBreaching입니다.
  metric_alarms = merge(
    var.enable_data_foundation ? {
      rds_cpu = {
        namespace   = "AWS/RDS", metric = "CPUUtilization", statistic = "Average", comparison = "GreaterThanThreshold",
        threshold   = 80, periods = 3, missing = "missing", dimensions = { DBInstanceIdentifier = aws_db_instance.main[0].identifier },
        description = "RDS CPU가 15분 동안 80%를 넘었습니다."
      }
      # db.t4g.medium(4GiB)의 약 1/8입니다.
      rds_memory = {
        namespace   = "AWS/RDS", metric = "FreeableMemory", statistic = "Minimum", comparison = "LessThanThreshold",
        threshold   = 512 * 1024 * 1024, periods = 3, missing = "missing", dimensions = { DBInstanceIdentifier = aws_db_instance.main[0].identifier },
        description = "RDS 여유 메모리가 15분 동안 512MiB 미만입니다."
      }
      # 자동 확장(여유 10%)보다 먼저 알리도록 할당량 20GiB의 20%로 둡니다.
      rds_storage = {
        namespace   = "AWS/RDS", metric = "FreeStorageSpace", statistic = "Minimum", comparison = "LessThanThreshold",
        threshold   = 4 * 1024 * 1024 * 1024, periods = 3, missing = "missing", dimensions = { DBInstanceIdentifier = aws_db_instance.main[0].identifier },
        description = "RDS 여유 저장 공간이 15분 동안 4GiB 미만입니다."
      }
      # max_connections=100, 앱 풀 상한 합계는 롤링 중에도 60입니다. 80을 넘으면 연결 누수나 설정 오류입니다.
      rds_connections = {
        namespace   = "AWS/RDS", metric = "DatabaseConnections", statistic = "Maximum", comparison = "GreaterThanThreshold",
        threshold   = 80, periods = 3, missing = "missing", dimensions = { DBInstanceIdentifier = aws_db_instance.main[0].identifier },
        description = "RDS 연결 수가 15분 동안 80을 넘었습니다."
      }
      data_memory = {
        namespace   = "Travelpay/Hosts", metric = "mem_used_percent", statistic = "Maximum", comparison = "GreaterThanThreshold",
        threshold   = 80, periods = 3, missing = "breaching", dimensions = { InstanceId = aws_instance.data[0].id },
        description = "Data 호스트 메모리 사용률이 15분 동안 80%를 넘었거나 지표가 끊겼습니다."
      }
    } : {},
    # 루트는 AL2023 기본 xfs, 데이터 볼륨은 mount-data-volumes.py가 만든 ext4입니다. 차원이 달라지면 지표가 끊겨 경보가 울립니다.
    var.enable_data_foundation ? {
      for name, path in merge({ root = "/" }, { for name, spec in local.data_volume_specs : name => spec.mount }) :
      "data_disk_${name}" => {
        namespace   = "Travelpay/Hosts", metric = "disk_used_percent", statistic = "Maximum", comparison = "GreaterThanThreshold",
        threshold   = 75, periods = 3, missing = "breaching",
        dimensions  = { InstanceId = aws_instance.data[0].id, path = path, fstype = name == "root" ? "xfs" : "ext4" },
        description = "Data 호스트 ${path} 사용률이 15분 동안 75%를 넘었거나 지표가 끊겼습니다."
      }
    } : {},
    # 앱 호스트는 ASG가 교체하므로 인스턴스 ID가 아니라 Agent가 그룹 단위로 합친 지표를 봅니다(host-observability.tf).
    var.enable_app_foundation && var.app_host_count > 0 ? {
      app_memory = {
        namespace   = "Travelpay/Hosts", metric = "mem_used_percent", statistic = "Maximum", comparison = "GreaterThanThreshold",
        threshold   = 80, periods = 3, missing = "breaching", dimensions = { AutoScalingGroupName = aws_autoscaling_group.app[0].name },
        description = "앱 호스트 중 하나의 메모리 사용률이 15분 동안 80%를 넘었거나 지표가 끊겼습니다."
      }
      app_disk = {
        namespace   = "Travelpay/Hosts", metric = "disk_used_percent", statistic = "Maximum", comparison = "GreaterThanThreshold",
        threshold   = 75, periods = 3, missing = "breaching", dimensions = { AutoScalingGroupName = aws_autoscaling_group.app[0].name },
        description = "앱 호스트 중 하나의 루트 디스크 사용률이 15분 동안 75%를 넘었거나 지표가 끊겼습니다."
      }
    } : {},
    # 요청이 적은 서비스라 5분에 5건이면 일시 오류가 아니라 장애로 봅니다. ELB 5xx는 정상 Target이 없거나 응답 시간 초과입니다.
    var.enable_app_runtime ? {
      alb_5xx = {
        namespace   = "AWS/ApplicationELB", metric = "HTTPCode_ELB_5XX_Count", statistic = "Sum", comparison = "GreaterThanOrEqualToThreshold",
        threshold   = 5, periods = 1, missing = "notBreaching", dimensions = { LoadBalancer = aws_lb.api[0].arn_suffix },
        description = "ALB가 5분 동안 5xx를 5건 이상 반환했습니다(정상 Gateway 없음·시간 초과)."
      }
      gateway_5xx = {
        namespace   = "AWS/ApplicationELB", metric = "HTTPCode_Target_5XX_Count", statistic = "Sum", comparison = "GreaterThanOrEqualToThreshold",
        threshold   = 5, periods = 1, missing = "notBreaching", dimensions = { LoadBalancer = aws_lb.api[0].arn_suffix },
        description = "Gateway가 5분 동안 5xx를 5건 이상 응답했습니다."
      }
    } : {},
  )
}

resource "aws_cloudwatch_metric_alarm" "service" {
  for_each = local.metric_alarms

  alarm_name          = "${local.runtime_name_prefix}-${replace(each.key, "_", "-")}"
  alarm_description   = each.value.description
  namespace           = each.value.namespace
  metric_name         = each.value.metric
  dimensions          = each.value.dimensions
  statistic           = each.value.statistic
  period              = 300
  evaluation_periods  = each.value.periods
  datapoints_to_alarm = each.value.periods
  comparison_operator = each.value.comparison
  threshold           = each.value.threshold
  treat_missing_data  = each.value.missing
  alarm_actions       = [aws_sns_topic.alerts[0].arn]
  ok_actions          = [aws_sns_topic.alerts[0].arn]
}
