resource "aws_iam_openid_connect_provider" "github" {
  url            = "https://token.actions.githubusercontent.com"
  client_id_list = ["sts.amazonaws.com"]
}

# 저장소가 2026-07-15 이후 만들어져 GitHub가 소유자·저장소 이름 뒤에 숫자 ID를 붙인 subject를 씁니다(GitHub OIDC 문서의
# immutable subject claims). 이름만 쓰는 이전 형식은 이 저장소의 토큰과 맞지 않습니다.
locals {
  github_repository_parts = split("/", var.github_repository)
  github_subject          = "repo:${local.github_repository_parts[0]}@${var.github_owner_id}/${local.github_repository_parts[1]}@${var.github_repository_id}:environment:${var.github_environment}"
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
          "token.actions.githubusercontent.com:sub" = local.github_subject
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
        # DescribeImages는 실행 중 이미지의 소스 커밋(태그)과 이미 올린 커밋 태그를 확인하는 데 씁니다.
        Action = [
          "ecr:BatchCheckLayerAvailability", "ecr:InitiateLayerUpload", "ecr:UploadLayerPart",
          "ecr:CompleteLayerUpload", "ecr:PutImage", "ecr:BatchGetImage", "ecr:GetDownloadUrlForLayer",
          "ecr:DescribeImages",
        ]
        Resource = values(local.repository_arns)
      },
      {
        Effect   = "Allow"
        Action   = ["ecs:RegisterTaskDefinition"]
        Resource = ["*"]
      },
      # privileged 컨테이너는 호스트 권한을 얻을 수 있습니다. 값이 true인 요청만 거부해 일반 등록은 그대로 둡니다.
      {
        Effect    = "Deny"
        Action    = ["ecs:RegisterTaskDefinition"]
        Resource  = ["*"]
        Condition = { Bool = { "ecs:privileged" = "true" } }
      },
      # DescribeTaskDefinition은 리소스 단위 권한을 지원하지 않아 ARN을 지정하면 항상 거부됩니다.
      {
        Effect   = "Allow"
        Action   = ["ecs:DescribeTaskDefinition"]
        Resource = ["*"]
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
