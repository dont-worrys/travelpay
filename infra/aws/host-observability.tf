locals {
  host_log_group_names = {
    app_host = "/travelpay/production/hosts/app"
    data     = "/travelpay/production/hosts/data"
  }
  host_log_group_arns = {
    for name, group in local.host_log_group_names :
    name => "arn:aws:logs:${local.runtime_region}:${var.aws_account_id}:log-group:${group}:*"
  }
  host_cloudwatch_configs = {
    for role, group in local.host_log_group_names : role => jsonencode({
      agent = { metrics_collection_interval = 60 }
      metrics = {
        namespace         = "Travelpay/Hosts"
        append_dimensions = { InstanceId = "$${aws:InstanceId}" }
        metrics_collected = {
          mem = { measurement = ["mem_used_percent"] }
          disk = {
            measurement = ["used_percent"], drop_device = true,
            resources   = role == "data" ? ["/", "/srv/travelpay/kafka", "/srv/travelpay/monitoring"] : ["/"],
          }
        }
      }
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
