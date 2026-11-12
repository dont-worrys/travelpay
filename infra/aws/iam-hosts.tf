locals {
  ec2_service_trust = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Action    = ["sts:AssumeRole"]
      Principal = { Service = "ec2.amazonaws.com" }
    }]
  })
  host_roles = { app_host = aws_iam_role.app_host.name, data = aws_iam_role.data.name }
}

resource "aws_iam_role" "app_host" {
  name               = "${local.runtime_name_prefix}-app-host"
  assume_role_policy = local.ec2_service_trust
}

resource "aws_iam_role" "data" {
  name               = "${local.runtime_name_prefix}-data"
  assume_role_policy = local.ec2_service_trust
}

resource "aws_iam_instance_profile" "app_host" {
  name = "${local.runtime_name_prefix}-app-host"
  role = aws_iam_role.app_host.name
}

resource "aws_iam_instance_profile" "data" {
  name = "${local.runtime_name_prefix}-data"
  role = aws_iam_role.data.name
}

resource "aws_iam_role_policy_attachment" "ssm_core" {
  for_each = local.host_roles

  role       = each.value
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

resource "aws_iam_role_policy_attachment" "ecs_agent" {
  role       = aws_iam_role.app_host.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonEC2ContainerServiceforEC2Role"
}

# 관리형 SSM 정책은 전체 GetParameter(s)를 허용하므로 명시적 Deny로 범위를 줄입니다.
resource "aws_iam_role_policy" "ssm_boundary" {
  for_each = local.host_roles

  name = "restrict-parameter-access"
  role = each.value
  policy = each.key == "app_host" ? jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect   = "Deny"
        Action   = ["ssm:GetParameter", "ssm:GetParameters", "ssm:GetParametersByPath"]
        Resource = ["*"]
      },
    ]
    }) : jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect   = "Deny"
        Action   = ["ssm:GetParametersByPath"]
        Resource = ["*"]
      },
      {
        Effect      = "Deny"
        Action      = ["ssm:GetParameter", "ssm:GetParameters"]
        NotResource = sort([for name in local.data_secret_names : local.secret_parameter_arns[name]])
      },
    ]
  })
}

resource "aws_iam_role_policy_attachments_exclusive" "hosts" {
  for_each = local.host_roles

  role_name = each.value
  policy_arns = concat(
    [aws_iam_role_policy_attachment.ssm_core[each.key].policy_arn],
    each.key == "app_host" ? [aws_iam_role_policy_attachment.ecs_agent.policy_arn] : [],
  )
}

resource "aws_iam_role_policies_exclusive" "hosts" {
  for_each = local.host_roles

  role_name = each.value
  policy_names = concat(
    [aws_iam_role_policy.ssm_boundary[each.key].name, aws_iam_role_policy.host_observability[each.key].name],
    each.key == "app_host" ? [aws_iam_role_policy.app_agent_boundary.name] : [],
    each.key == "app_host" ? [for policy in aws_iam_role_policy.app_config_read : policy.name] : [],
    each.key == "data" ? [for policy in aws_iam_role_policy.database_bootstrap : policy.name] : [],
    each.key == "data" ? [for policy in aws_iam_role_policy.data_config_read : policy.name] : [],
  )
}
