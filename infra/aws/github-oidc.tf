resource "aws_iam_openid_connect_provider" "github" {
  url            = "https://token.actions.githubusercontent.com"
  client_id_list = ["sts.amazonaws.com"]
}

resource "aws_iam_role" "github_deploy" {
  name = "${local.runtime_name_prefix}-github-deploy"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Action    = ["sts:AssumeRoleWithWebIdentity"]
      Principal = { Federated = aws_iam_openid_connect_provider.github.arn }
      Condition = {
        StringEquals = {
          "token.actions.githubusercontent.com:aud" = "sts.amazonaws.com"
          "token.actions.githubusercontent.com:sub" = "repo:${var.github_repository}:environment:${var.github_environment}"
        }
      }
    }]
  })
}

# production 환경의 브랜치·승인 보호와 배포 워크플로를 확인한 뒤에만 활성화합니다.
resource "aws_iam_role_policy" "github_deploy" {
  count = var.enable_github_deployment ? 1 : 0

  name = "deploy-application-services"
  role = aws_iam_role.github_deploy.name
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      { Effect = "Allow", Action = ["ecr:GetAuthorizationToken"], Resource = ["*"] },
      {
        Effect = "Allow"
        Action = [
          "ecr:BatchCheckLayerAvailability", "ecr:InitiateLayerUpload", "ecr:UploadLayerPart",
          "ecr:CompleteLayerUpload", "ecr:PutImage", "ecr:BatchGetImage", "ecr:GetDownloadUrlForLayer",
        ]
        Resource = values(local.repository_arns)
      },
      {
        Effect   = "Allow"
        Action   = ["ecs:RegisterTaskDefinition"]
        Resource = ["*"]
      },
      {
        Effect   = "Allow"
        Action   = ["ecs:DescribeTaskDefinition"]
        Resource = [for service in local.application_services : "arn:aws:ecs:${local.runtime_region}:${var.aws_account_id}:task-definition/travelpay-${service.name}:*"]
      },
      {
        Effect   = "Allow"
        Action   = ["ecs:DescribeServices", "ecs:UpdateService"]
        Resource = [for service in local.application_services : "arn:aws:ecs:${local.runtime_region}:${var.aws_account_id}:service/${local.runtime_cluster_name}/${service.name}"]
      },
      {
        Effect = "Allow"
        Action = ["iam:PassRole"]
        Resource = concat(
          [for role in aws_iam_role.task : role.arn],
          [for role in aws_iam_role.task_execution : role.arn],
        )
        Condition = { StringEquals = { "iam:PassedToService" = "ecs-tasks.amazonaws.com" } }
      },
    ]
  })
}

resource "aws_iam_role_policies_exclusive" "github_deploy" {
  role_name    = aws_iam_role.github_deploy.name
  policy_names = [for policy in aws_iam_role_policy.github_deploy : policy.name]
}

resource "aws_iam_role_policy_attachments_exclusive" "github_deploy" {
  role_name   = aws_iam_role.github_deploy.name
  policy_arns = []
}
