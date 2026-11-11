# 실제 AWS를 호출하지 않고 역할의 신뢰·비밀값·배포 권한 경계를 검사합니다.
mock_provider "aws" {
  mock_data "aws_availability_zones" {
    defaults = { names = ["ap-northeast-2a", "ap-northeast-2c"] }
  }
}

variables {
  enable_app_foundation            = false
  enable_data_foundation           = false
  app_host_count                   = 0
  enable_database_bootstrap_access = false
  enable_github_deployment         = false
  aws_account_id                   = "123456789012"
  availability_zones               = { a = "ap-northeast-2a", b = "ap-northeast-2c" }
}

run "hosts_and_tasks_have_separate_service_trust" {
  command = plan

  assert {
    condition = alltrue([
      for role in [aws_iam_role.app_host, aws_iam_role.data] :
      jsondecode(role.assume_role_policy).Statement[0].Principal.Service == "ec2.amazonaws.com"
      ]) && alltrue([
      for role in concat(values(aws_iam_role.task_execution), values(aws_iam_role.task)) :
      jsondecode(role.assume_role_policy).Statement[0].Principal.Service == "ecs-tasks.amazonaws.com"
    ])
    error_message = "호스트와 Task는 서로 다른 서비스 신뢰를 사용해야 합니다."
  }
}

run "each_service_reads_only_its_own_secret_parameters" {
  command = plan

  assert {
    condition = alltrue([
      for name, expected in {
        gateway   = []
        eureka    = []
        user      = ["USER_DB_PASSWORD", "REDIS_SESSION_PASSWORD"]
        wallet    = ["WALLET_DB_PASSWORD", "WALLET_ACCOUNT_NUMBER_KEY"]
        payment   = ["PAYMENT_DB_PASSWORD"]
        exchange  = ["EXCHANGE_DB_PASSWORD", "REDIS_CACHE_PASSWORD", "KOREAEXIM_API_KEY"]
        ledger    = ["LEDGER_DB_PASSWORD"]
        mock_bank = ["MOCK_BANK_DB_PASSWORD"]
        } : sort(flatten([
          for statement in jsondecode(aws_iam_role_policy.task_execution[name].policy).Statement :
          statement.Resource if contains(statement.Action, "ssm:GetParameters")
          ])) == sort([
          for parameter in expected : "arn:aws:ssm:ap-northeast-2:123456789012:parameter/travelpay/production/${parameter}"
      ])
    ])
    error_message = "앱은 자기 DB·Redis·API 비밀값만 조회할 수 있어야 합니다."
  }
}

run "task_trust_is_limited_to_this_account" {
  command = plan

  assert {
    condition = length(aws_iam_role.task) == 8 && alltrue([
      for role in aws_iam_role.task :
      jsondecode(role.assume_role_policy).Statement[0].Condition.StringEquals["aws:SourceAccount"] == "123456789012" &&
      jsondecode(role.assume_role_policy).Statement[0].Condition.ArnLike["aws:SourceArn"] == "arn:aws:ecs:ap-northeast-2:123456789012:*"
    ])
    error_message = "Task 역할을 다른 계정의 ECS 작업에 넘길 수 없어야 합니다."
  }
}

run "host_ssm_policy_cannot_read_application_secrets" {
  command = plan

  assert {
    condition = (
      jsondecode(aws_iam_role_policy.ssm_boundary["app_host"].policy).Statement[0].Effect == "Deny" &&
      sort(jsondecode(aws_iam_role_policy.ssm_boundary["app_host"].policy).Statement[0].Action) == sort([
        "ssm:GetParameter", "ssm:GetParameters", "ssm:GetParametersByPath",
      ]) &&
      jsondecode(aws_iam_role_policy.ssm_boundary["app_host"].policy).Statement[0].Resource == ["*"]
    )
    error_message = "SSM 관리형 정책의 전체 비밀값 조회 권한을 App 호스트가 상속하면 안 됩니다."
  }

  assert {
    condition = (
      jsondecode(aws_iam_role_policy.ssm_boundary["data"].policy).Statement[0].Effect == "Deny" &&
      jsondecode(aws_iam_role_policy.ssm_boundary["data"].policy).Statement[0].Action == ["ssm:GetParametersByPath"] &&
      jsondecode(aws_iam_role_policy.ssm_boundary["data"].policy).Statement[0].Resource == ["*"] &&
      jsondecode(aws_iam_role_policy.ssm_boundary["data"].policy).Statement[1].Effect == "Deny" &&
      sort(jsondecode(aws_iam_role_policy.ssm_boundary["data"].policy).Statement[1].Action) == sort(["ssm:GetParameter", "ssm:GetParameters"]) &&
      sort(jsondecode(aws_iam_role_policy.ssm_boundary["data"].policy).Statement[1].NotResource) == sort([
        "arn:aws:ssm:ap-northeast-2:123456789012:parameter/travelpay/production/REDIS_SESSION_PASSWORD",
        "arn:aws:ssm:ap-northeast-2:123456789012:parameter/travelpay/production/REDIS_CACHE_PASSWORD",
      ])
    )
    error_message = "Data 호스트는 기본적으로 두 Redis 비밀값만 읽고 재귀 경로 조회는 차단해야 합니다."
  }
}

run "github_trust_requires_project_environment_and_sts_audience" {
  # OIDC ARN을 포함하는 정책은 모의 생성 후 검사합니다. 실제 AWS 생성은 없습니다.
  command = apply

  assert {
    condition = (
      jsondecode(aws_iam_role.github_deploy.assume_role_policy).Statement[0].Action == ["sts:AssumeRoleWithWebIdentity"] &&
      jsondecode(aws_iam_role.github_deploy.assume_role_policy).Statement[0].Condition.StringEquals == {
        "token.actions.githubusercontent.com:aud" = "sts.amazonaws.com"
        "token.actions.githubusercontent.com:sub" = "repo:dont-worrys/travelpay:environment:production"
      }
    )
    error_message = "GitHub 신뢰는 해당 저장소의 production 환경과 STS audience로 제한해야 합니다."
  }

  assert {
    condition     = length(aws_iam_role_policy.github_deploy) == 0
    error_message = "배포 환경 보호를 확인하기 전에는 GitHub 배포 권한을 붙이지 않아야 합니다."
  }
}

run "enabled_deployment_cannot_pass_host_or_data_roles" {
  command = apply

  variables {
    enable_github_deployment = true
  }

  assert {
    condition = alltrue([
      for statement in jsondecode(aws_iam_role_policy.github_deploy[0].policy).Statement :
      (
        length(statement.Resource) == 16 &&
        !contains(statement.Resource, aws_iam_role.app_host.arn) &&
        !contains(statement.Resource, aws_iam_role.data.arn) &&
        statement.Condition.StringEquals["iam:PassedToService"] == "ecs-tasks.amazonaws.com"
      ) if contains(statement.Action, "iam:PassRole")
      ]) && length([
      for statement in jsondecode(aws_iam_role_policy.github_deploy[0].policy).Statement :
      statement if contains(statement.Action, "iam:PassRole")
    ]) == 1
    error_message = "배포 역할은 지정한 Task 역할만 전달하며 호스트·Data 역할을 넘길 수 없어야 합니다."
  }
}

run "reject_wildcard_repository" {
  command = plan

  variables {
    github_repository = "dont-worrys/*"
  }

  expect_failures = [var.github_repository]
}

run "reject_wildcard_environment" {
  command = plan

  variables {
    github_environment = "*"
  }

  expect_failures = [var.github_environment]
}

run "enabled_deployment_can_read_task_definitions" {
  command = apply

  variables {
    enable_github_deployment = true
  }

  # DescribeTaskDefinition은 리소스 단위 권한을 지원하지 않아 "*" 외의 Resource로는 허용되지 않습니다.
  assert {
    condition = alltrue([
      for statement in jsondecode(aws_iam_role_policy.github_deploy[0].policy).Statement :
      statement.Resource == ["*"] if contains(statement.Action, "ecs:DescribeTaskDefinition")
      ]) && length([
      for statement in jsondecode(aws_iam_role_policy.github_deploy[0].policy).Statement :
      statement if contains(statement.Action, "ecs:DescribeTaskDefinition")
    ]) == 1
    error_message = "배포 역할이 기준 Task Definition을 조회할 수 있어야 합니다."
  }
}

run "enabled_deployment_cannot_register_privileged_tasks" {
  command = apply

  variables {
    enable_github_deployment = true
  }

  # 값이 true인 요청만 거부해야 privileged 키가 없는 일반 등록이 막히지 않습니다.
  assert {
    condition = length([
      for statement in jsondecode(aws_iam_role_policy.github_deploy[0].policy).Statement :
      statement if statement.Effect == "Deny" && statement.Action == ["ecs:RegisterTaskDefinition"] &&
      try(statement.Condition.Bool["ecs:privileged"], "") == "true"
    ]) == 1
    error_message = "배포 역할은 privileged 컨테이너가 있는 Task Definition을 등록할 수 없어야 합니다."
  }
}
