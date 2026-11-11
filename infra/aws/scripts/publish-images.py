#!/usr/bin/env python3
"""커밋된 소스로 서비스 이미지를 만들어 ECR에 올리고, Task Definition 입력으로 쓸 digest를 기록합니다.

기본 실행은 미리보기입니다. --push를 주면 빌드·푸시하며, 같은 Git SHA 태그가 이미 있으면 다시 만들지 않고 그 digest를 씁니다.
"""

import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[3]
REGION = "ap-northeast-2"
OUTPUT = ROOT / "infra" / "aws" / "application-images.auto.tfvars.json"


class PublishError(Exception):
    pass


def failure_message(error):
    reason = str(error) if isinstance(error, PublishError) else type(error).__name__
    return f"Image publish failed: {reason}"


def source_revision(run):
    # 이미지 태그는 Git SHA입니다. 커밋되지 않은 변경이 섞이면 태그가 실제 소스를 가리키지 않습니다.
    if run(["git", "-C", str(ROOT), "status", "--porcelain"]).strip():
        raise PublishError("커밋되지 않은 변경이 있습니다. 먼저 커밋한 뒤 게시하세요.")
    revision = run(["git", "-C", str(ROOT), "rev-parse", "HEAD"]).strip()
    if not re.fullmatch(r"[0-9a-f]{40}", revision):
        raise PublishError("Git 커밋을 확인할 수 없습니다.")
    return revision


def plan_images(repositories, revision, selected):
    if not repositories:
        raise PublishError("ECR 저장소 출력이 비어 있습니다. 저장소를 먼저 적용하세요.")
    unknown = sorted(set(selected) - set(repositories))
    if unknown:
        raise PublishError("알 수 없는 서비스입니다: " + ", ".join(unknown))
    images = []
    for key in sorted(selected or repositories):
        url = repositories[key]
        module = url.rsplit("/", 1)[-1]
        if not (ROOT / module / "build.gradle").is_file():
            raise PublishError(f"저장소 이름에 해당하는 모듈이 없습니다: {module}")
        images.append({"key": key, "module": module, "url": url, "tag": revision[:12]})
    return images


def existing_digest(aws, image):
    result = aws(["ecr", "describe-images", "--repository-name", image["url"].split("/", 1)[1],
                  "--image-ids", f"imageTag={image['tag']}", "--query", "imageDetails[0].imageDigest", "--output", "text"],
                 check=False)
    digest = result.stdout.strip()
    if result.returncode == 0 and re.fullmatch(r"sha256:[0-9a-f]{64}", digest):
        return digest
    if result.returncode != 0 and "ImageNotFoundException" not in result.stderr:
        raise PublishError("ECR 이미지 조회에 실패했습니다. 권한과 저장소를 확인하세요.")
    return None


def publish(images, run, aws, login):
    login(images[0]["url"].split("/", 1)[0])
    published = {}
    for image in images:
        digest = existing_digest(aws, image)
        if digest is None:
            reference = f"{image['url']}:{image['tag']}"
            run(["docker", "build", "--platform", "linux/amd64", "-f", str(ROOT / "deploy" / "docker" / "Dockerfile"),
                 "--build-arg", f"SERVICE={image['module']}", "-t", reference, str(ROOT)], capture=False)
            run(["docker", "push", reference], capture=False)
            digest = existing_digest(aws, image)
            if digest is None:
                raise PublishError(f"푸시한 이미지의 digest를 확인할 수 없습니다: {image['module']}")
        published[image["key"]] = f"{image['url']}@{digest}"
        print(f"{image['module']}: {image['tag']} {digest}")
    return published


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--profile", required=True)
    parser.add_argument("--service", action="append", default=[], help="게시할 서비스 키(예: user). 생략하면 전체입니다.")
    parser.add_argument("--push", action="store_true", help="빌드·푸시합니다. 기본은 미리보기입니다.")
    args = parser.parse_args()
    env = dict(os.environ, AWS_PROFILE=args.profile)

    def run(command, capture=True):
        result = subprocess.run(command, env=env, cwd=ROOT, text=True, check=True,
                                stdout=subprocess.PIPE if capture else None)
        return result.stdout if capture else ""

    def aws(arguments, check=True):
        return subprocess.run(["aws", *arguments, "--region", REGION, "--no-cli-pager"], env=env,
                              capture_output=True, text=True, check=check)

    def login(registry):
        # 암호는 명령 인자나 출력이 아니라 표준 입력으로만 넘깁니다.
        password = aws(["ecr", "get-login-password"]).stdout
        subprocess.run(["docker", "login", "--username", "AWS", "--password-stdin", registry], input=password,
                       env=env, capture_output=True, text=True, check=True)

    revision = source_revision(run)
    repositories = json.loads(run(["terraform", f"-chdir={ROOT / 'infra' / 'aws'}", "output", "-json", "application_repositories"]))
    images = plan_images(repositories, revision, args.service)
    if not args.push:
        for image in images:
            print(f"would publish {image['url']}:{image['tag']}")
        return
    published = publish(images, run, aws, login)
    current = json.loads(OUTPUT.read_text())["application_images"] if OUTPUT.exists() else {}
    OUTPUT.write_text(json.dumps({"application_images": {**current, **published}}, indent=2, sort_keys=True) + "\n")
    print(f"recorded {len(published)} image digests in {OUTPUT.relative_to(ROOT)}")


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(failure_message(error), file=sys.stderr)
        sys.exit(1)
