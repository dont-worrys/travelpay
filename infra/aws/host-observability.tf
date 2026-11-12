locals {
  host_log_group_names = {
    app_host = "/travelpay/production/hosts/app"
    data     = "/travelpay/production/hosts/data"
  }
  host_log_group_arns = {
    for name, group in local.host_log_group_names :
    name => "arn:aws:logs:${local.runtime_region}:${var.aws_account_id}:log-group:${group}:*"
  }
  # 앱 호스트는 ASG가 교체해 인스턴스 ID가 바뀌므로 ASG 이름 차원을 붙이고 그룹 단위로도 합칩니다(alarms.tf가 사용).
  # ASG 이름은 Agent가 EC2 태그 조회(ec2:DescribeTags, ECS 호스트 관리형 정책에 포함)로 얻습니다.
  # Data는 ASG에 속하지 않으므로 기존 설정을 그대로 둡니다. 바꾸면 Data 설정 묶음이 다시 적용됩니다.
  host_metric_dimensions = {
    app_host = {
      append_dimensions      = { AutoScalingGroupName = "$${aws:AutoScalingGroupName}", InstanceId = "$${aws:InstanceId}" }
      aggregation_dimensions = [["AutoScalingGroupName"]]
    }
    data = { append_dimensions = { InstanceId = "$${aws:InstanceId}" } }
  }
  host_cloudwatch_configs = {
    for role, group in local.host_log_group_names : role => jsonencode({
      agent = { metrics_collection_interval = 60 }
      metrics = merge(local.host_metric_dimensions[role], {
        namespace = "Travelpay/Hosts"
        metrics_collected = {
          mem = { measurement = ["mem_used_percent"] }
          disk = {
            measurement = ["used_percent"], drop_device = true,
            resources   = role == "data" ? ["/", "/srv/travelpay/kafka", "/srv/travelpay/monitoring"] : ["/"],
          }
        }
      })
      logs = {
        logs_collected = {
          files = {
            collect_list = [{
              file_path       = "/var/log/cloud-init-output.log", log_group_name = group,
              log_stream_name = "{instance_id}/cloud-init",
            }]
          }
        }
      }
    })
  }
}

resource "aws_cloudwatch_log_group" "hosts" {
  for_each = {
    for role, group in local.host_log_group_names : role => group
    if role == "app_host" ? var.enable_app_foundation : var.enable_data_foundation
  }

  name              = each.value
  retention_in_days = 7
}

resource "aws_iam_role_policy" "host_observability" {
  for_each = local.host_roles

  name = "host-observability"
  role = each.value
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect    = "Allow"
        Action    = ["cloudwatch:PutMetricData"]
        Resource  = ["*"]
        Condition = { StringEquals = { "cloudwatch:namespace" = "Travelpay/Hosts" } }
      },
      {
        Effect   = "Allow"
        Action   = ["logs:CreateLogStream", "logs:DescribeLogStreams", "logs:PutLogEvents"]
        Resource = [local.host_log_group_arns[each.key]]
      },
    ]
  })
}

# Agent 관리형 정책에서 허용한 이미지·로그 접근도 이 프로젝트로 제한합니다.
resource "aws_iam_role_policy" "app_agent_boundary" {
  name = "restrict-agent-resources"
  role = aws_iam_role.app_host.name
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect      = "Deny"
        Action      = ["ecr:BatchCheckLayerAvailability", "ecr:GetDownloadUrlForLayer", "ecr:BatchGetImage"]
        NotResource = values(local.repository_arns)
      },
      {
        Effect      = "Deny"
        Action      = ["logs:CreateLogStream", "logs:PutLogEvents"]
        NotResource = [local.host_log_group_arns["app_host"]]
      },
    ]
  })
}
