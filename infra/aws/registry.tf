# 앱 이미지 저장소와 실행 로그 그룹입니다. 이름은 runtime-contract.tf의 실행 역할·배포 역할 권한 ARN과 같아야 합니다.
resource "aws_ecr_repository" "application" {
  for_each = var.enable_app_foundation ? local.application_services : {}

  name = "travelpay/${each.value.name}"
  # 배포 기준은 Git SHA 태그와 digest입니다. 같은 태그를 다른 이미지로 덮어쓰지 못하게 합니다.
  image_tag_mutability = "IMMUTABLE"

  encryption_configuration {
    encryption_type = "AES256"
  }

  image_scanning_configuration {
    scan_on_push = true
  }
}

# 배포마다 이미지가 쌓이므로 서비스별 최근 10개만 남깁니다. 지워진 이미지는 Task 재시작·롤백에 쓸 수 없으므로
# 7일 운영 중 서비스별 배포 횟수보다 넉넉한 값으로 둡니다. 개수는 ECR 항목 기준이므로 푸시 1번이 항목 1개가 되도록
# 이미지를 증명(provenance) 없이 만듭니다. 증명을 붙인 index는 하위 항목 2개가 함께 생겨 보관 배포 수가 줄어듭니다.
resource "aws_ecr_lifecycle_policy" "application" {
  for_each = aws_ecr_repository.application

  repository = each.value.name
  policy = jsonencode({
    rules = [{
      rulePriority = 1
      description  = "Keep the latest 10 images"
      selection    = { tagStatus = "any", countType = "imageCountMoreThan", countNumber = 10 }
      action       = { type = "expire" }
    }]
  })
}

resource "aws_cloudwatch_log_group" "application" {
  for_each = var.enable_app_foundation ? local.application_services : {}

  name              = "/travelpay/production/${each.value.name}"
  retention_in_days = 7
}
