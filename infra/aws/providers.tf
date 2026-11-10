provider "aws" {
  region              = "ap-northeast-2"
  allowed_account_ids = [var.aws_account_id]

  default_tags {
    tags = {
      Project     = "travelpay"
      Environment = "production"
      ManagedBy   = "Terraform"
    }
  }
}
