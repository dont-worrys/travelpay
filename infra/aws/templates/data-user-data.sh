#!/bin/bash
set -euo pipefail

# 패키지 설치만 수행합니다. 설정·스크립트·컨테이너 기동은 SSM association이 S3 묶음을 확인한 뒤 적용합니다.
timeout 300 dnf install -y docker amazon-ssm-agent amazon-cloudwatch-agent awscli-2 postgresql18 python3 e2fsprogs util-linux
systemctl enable --now docker amazon-ssm-agent
