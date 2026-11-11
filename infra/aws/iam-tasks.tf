resource "aws_iam_role" "task_execution" {
  for_each = local.application_services

  name               = "${local.runtime_name_prefix}-${each.value.name}-execution"
  assume_role_policy = local.task_service_trust
}

resource "aws_iam_role" "task" {
  for_each = local.application_services

  name               = "${local.runtime_name_prefix}-${each.value.name}-task"
  assume_role_policy = local.task_service_trust
}

# 실행 역할은 Agent의 이미지·로그·Secret 주입용입니다. 앱이 쓰는 Task 역할과 분리합니다.
resource "aws_iam_role_policy" "task_execution" {
  for_each = local.application_services

  name = "service-execution"
  role = aws_iam_role.task_execution[each.key].name
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = concat([
      { Effect = "Allow", Action = ["ecr:GetAuthorizationToken"], Resource = ["*"] },
      {
        Effect   = "Allow"
        Action   = ["ecr:BatchCheckLayerAvailability", "ecr:GetDownloadUrlForLayer", "ecr:BatchGetImage"]
        Resource = [local.repository_arns[each.key]]
      },
      {
        Effect   = "Allow"
        Action   = ["logs:CreateLogStream", "logs:PutLogEvents"]
        Resource = [local.application_log_arns[each.key]]
      },
      ], length(each.value.secret_names) == 0 ? [] : [{
        Effect   = "Allow"
        Action   = ["ssm:GetParameters"]
        Resource = [for name in each.value.secret_names : local.secret_parameter_arns[name]]
    }])
  })
}

# 이 앱들은 AWS API를 직접 사용하지 않습니다. 선언하지 않은 정책도 남기지 않습니다.
resource "aws_iam_role_policies_exclusive" "task" {
  for_each = local.application_services

  role_name    = aws_iam_role.task[each.key].name
  policy_names = []
}

resource "aws_iam_role_policy_attachments_exclusive" "task" {
  for_each = local.application_services

  role_name   = aws_iam_role.task[each.key].name
  policy_arns = []
}

resource "aws_iam_role_policies_exclusive" "task_execution" {
  for_each = local.application_services

  role_name    = aws_iam_role.task_execution[each.key].name
  policy_names = [aws_iam_role_policy.task_execution[each.key].name]
}

resource "aws_iam_role_policy_attachments_exclusive" "task_execution" {
  for_each = local.application_services

  role_name   = aws_iam_role.task_execution[each.key].name
  policy_arns = []
}
