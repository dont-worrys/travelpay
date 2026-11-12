#!/usr/bin/env python3
"""main 커밋을 운영 ECS Service에 배포합니다. GitHub Actions CD(.github/workflows/deploy.yml)가 실행합니다.

plan    실행 중인 이미지의 소스 커밋과 비교해 이미지 내용이 바뀐 서비스와 이전 배포가 끝나지 않은 서비스를 배포 순서대로
        고릅니다.
build   서비스 하나의 이미지를 커밋 태그로 만들어 ECR에 올립니다. 같은 태그가 있으면 다시 만들지 않습니다.
deploy  서비스마다 최신 Task 정의에서 이미지만 바꾼 리비전을 등록하고 롤링 배포가 끝날 때까지 기다립니다.
"""

import argparse
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[3]
REGION = "ap-northeast-2"
CLUSTER = "travelpay-production"
FAMILY_PREFIX = "travelpay-production-"
# runtime-contract.tf의 서비스 키와 이름(Gradle 모듈·ECR 저장소·Service 이름)입니다. 순서가 배포 순서이며, 다른 서비스가
# 호출하는 쪽(Eureka·Mock Bank·Ledger·User)을 먼저, 그것을 호출하는 Wallet·Payment·Exchange를 다음, 진입점 Gateway를 마지막에 둡니다.
SERVICES = {
    "eureka": "eureka-server",
    "mock_bank": "mock-bank",
    "ledger": "ledger-service",
    "user": "user-service",
    "wallet": "wallet-service",
    "payment": "payment-service",
    "exchange": "exchange-service",
    "gateway": "gateway",
}
# build.gradle에서 project(':common')을 쓰는 서비스입니다.
COMMON_USERS = frozenset(SERVICES) - {"eureka"}
# 모든 이미지의 빌드에 쓰이는 파일입니다(Dockerfile과 .dockerignore가 이미지에 보내는 공용 파일).
SHARED_FILES = frozenset(["build.gradle", "settings.gradle", "gradlew", ".dockerignore",
                          "deploy/docker/Dockerfile", "deploy/docker/entrypoint.sh"])
SHARED_DIRECTORIES = ("gradle/",)
# .dockerignore가 이미지 빌드에서 빼는 경로입니다. 테스트·부하 시험 파일만 바뀐 커밋은 이미지를 바꾸지 않습니다.
EXCLUDED = re.compile(r"(^|/)(build|\.gradle|out|src/test|load-test)/|\.log$|(^|/)\.env")
TAG = re.compile(r"[0-9a-f]{12}")
POLL_SECONDS = 15
# 서비스 하나의 대기 상한입니다. 정상 배포는 새 Task 기동과 이전 Task 종료까지 약 3분이고(2026-11-12 Service 첫 기동
# 약 2분, 종료 전 대기 20초), 서킷 브레이커는 Task 1개 Service에서 연속 실패 3번에 배포를 실패로 판정하며 실패 1번이
# 기동 유예(180초)와 헬스 체크 재시도까지 약 4분이라 실패 판정에 약 13분이 걸립니다. 전체 시간은 배포 작업의 제한
# 시간(deploy.yml, AWS 자격 증명 1시간보다 짧게)이 막습니다.
ROLLOUT_TIMEOUT_SECONDS = 20 * 60
# 갱신 직후 조회에 새 배포가 바로 보이지 않는 경우(ECS API 결과 반영 지연)에 대비해 이 시간 동안은 기다립니다.
MISSING_GRACE_SECONDS = 60
# RegisterTaskDefinition이 받는 항목입니다(aws ecs register-task-definition --generate-cli-skeleton). 태그는 복사하지 않습니다.
REGISTER_KEYS = ("family", "taskRoleArn", "executionRoleArn", "networkMode", "containerDefinitions", "volumes",
                 "placementConstraints", "requiresCompatibilities", "cpu", "memory", "pidMode", "ipcMode",
                 "proxyConfiguration", "inferenceAccelerators", "ephemeralStorage", "runtimePlatform",
                 "enableFaultInjection")


class DeployError(Exception):
    pass


def affects_image(path, key):
    """경로가 이 서비스의 이미지 내용을 바꿀 수 있는지 판단합니다."""
    if EXCLUDED.search(path):
        return False
    if path in SHARED_FILES or path.startswith(SHARED_DIRECTORIES):
        return True
    if path.startswith(SERVICES[key] + "/"):
        return True
    return key in COMMON_USERS and path.startswith("common/")


def parse_services(text):
    keys = [item.strip() for item in text.split(",") if item.strip()]
    unknown = sorted(set(keys) - set(SERVICES))
    if unknown:
        raise DeployError("알 수 없는 서비스 키입니다: " + ", ".join(unknown) + " (가능한 키: " + ", ".join(SERVICES) + ")")
    return set(keys)


def in_deploy_order(keys):
    return [key for key in SERVICES if key in keys]


def select_services(sources, changes, forced):
    """sources는 서비스별 실행 중 이미지의 소스 커밋(배포가 끝나지 않았으면 None), changes는 커밋별로 HEAD까지 바뀐
    경로입니다.

    같은 이미지에 태그가 여러 개면(내용이 같은 빌드) 그중 하나라도 이후 변경이 없으면 이미지 내용이 같다고 봅니다.
    소스 커밋을 알 수 없거나 이전 배포가 끝나지 않았으면 배포합니다.
    """
    selected, reasons = [], {}
    for key in SERVICES:
        commits = sources.get(key, [])
        if key in forced:
            reasons[key] = "요청에 따라 다시 배포"
        elif commits is None:
            reasons[key] = "이전 배포가 끝나지 않았거나 실패한 상태라 배포"
        elif not commits:
            reasons[key] = "실행 중 이미지의 소스 커밋을 알 수 없어 배포"
        else:
            unchanged = [commit for commit in commits if not any(affects_image(path, key) for path in changes[commit])]
            if unchanged:
                reasons[key] = f"{unchanged[0][:12]} 이후 이미지 변경 없음"
                continue
            reasons[key] = f"{commits[0][:12]} 이후 이미지 변경 있음"
        selected.append(key)
    return selected, reasons


def task_definition_input(task_definition, name, image):
    """최신 Task 정의에서 등록에 쓰는 항목만 남기고 컨테이너 이미지만 바꿉니다."""
    containers = task_definition["containerDefinitions"]
    if [container["name"] for container in containers] != [name]:
        raise DeployError(f"Task 정의 {task_definition['family']}에는 컨테이너 {name} 하나만 있어야 합니다.")
    registration = {key: task_definition[key] for key in REGISTER_KEYS if key in task_definition}
    registration["containerDefinitions"] = [dict(containers[0], image=image)]
    return registration


def primary_deployment(service):
    deployment = next((item for item in service["deployments"] if item["status"] == "PRIMARY"), None)
    if deployment is None:
        raise DeployError(f"{service['serviceName']}에 PRIMARY 배포가 없습니다.")
    return deployment


def started_deployment(service, task_definition_arn, previous_id):
    """갱신으로 시작된 배포를 찾습니다. 갱신 뒤에는 그 배포가 PRIMARY가 되며, 같은 리비전으로 다시 배포해도
    갱신 전 PRIMARY와 ID가 달라 구분됩니다. 아직 조회에 보이지 않으면 None입니다."""
    deployment = primary_deployment(service)
    if deployment["taskDefinition"] == task_definition_arn and deployment["id"] != previous_id:
        return deployment
    return None


def rollout_state(deployment):
    """배포가 끝났으면 COMPLETED, 실패했으면 FAILED, 진행 중이면 IN_PROGRESS, 목록에 없으면 MISSING입니다."""
    if deployment is None:
        return "MISSING"
    state = deployment.get("rolloutState", "IN_PROGRESS")
    if state == "COMPLETED" and deployment["status"] == "PRIMARY" and deployment["runningCount"] == deployment["desiredCount"]:
        return "COMPLETED"
    if state == "FAILED":
        return "FAILED"
    return "IN_PROGRESS"


def settled(service):
    """진행 중인 배포(롤링·서킷 브레이커의 되돌리기)가 없으면 True입니다."""
    return all(item.get("rolloutState", "IN_PROGRESS") != "IN_PROGRESS" for item in service["deployments"])


def recent_events(service, count=5):
    return "\n".join("  " + event["message"] for event in service.get("events", [])[:count])


class Cluster:
    """AWS CLI로 ECS·ECR을 조회하고 바꿉니다."""

    def __init__(self, aws):
        self.aws = aws

    def call(self, arguments, missing=None):
        """missing에 오류 이름을 주면 그 오류일 때 실패 대신 None을 돌려줍니다."""
        result = self.aws([*arguments, "--output", "json"], check=False)
        if result.returncode != 0:
            if missing and missing in result.stderr:
                return None
            raise DeployError(f"aws {' '.join(arguments[:2])} 실패: {result.stderr.strip()[-500:]}")
        return json.loads(result.stdout)

    def service(self, name):
        response = self.call(["ecs", "describe-services", "--cluster", CLUSTER, "--services", name])
        services = [item for item in response["services"] if item["status"] == "ACTIVE"]
        if len(services) != 1:
            raise DeployError(f"클러스터 {CLUSTER}에 실행 중인 Service {name}이 없습니다. CD는 Service를 만들지 않습니다.")
        return services[0]

    def task_definition(self, reference):
        return self.call(["ecs", "describe-task-definition", "--task-definition", reference])["taskDefinition"]

    def image_tags(self, repository, digest):
        # 수명 주기 규칙(최근 10개)이 실행 중 이미지를 지웠으면 태그를 알 수 없으므로 소스를 모르는 것으로 봅니다.
        response = self.call(["ecr", "describe-images", "--repository-name", repository, "--image-ids", f"imageDigest={digest}"],
                             missing="ImageNotFoundException")
        return [] if response is None else [tag for detail in response["imageDetails"] for tag in detail.get("imageTags", [])]

    def running_image(self, name):
        """PRIMARY 배포의 이미지 digest와, 그 배포가 끝나 진행 중인 배포가 없는지를 돌려줍니다.

        Service의 taskDefinition은 갱신하는 즉시 새 리비전을 가리키므로(롤링이 끝나지 않아도) 배포 상태를 함께 봅니다.
        """
        service = self.service(name)
        primary = primary_deployment(service)
        image = self.task_definition(primary["taskDefinition"])["containerDefinitions"][0]["image"]
        digest = image.split("@", 1)[1] if "@sha256:" in image else None
        return digest, settled(service) and rollout_state(primary) == "COMPLETED"

    def register(self, registration):
        response = self.call(["ecs", "register-task-definition", "--cli-input-json", json.dumps(registration)])
        return response["taskDefinition"]["taskDefinitionArn"]

    def update(self, name, task_definition_arn, restart=False):
        # 같은 리비전으로는 새 배포가 시작되지 않으므로, 다시 띄울 때(비밀값 교체 반영 등)는 새 배포를 강제합니다.
        self.call(["ecs", "update-service", "--cluster", CLUSTER, "--service", name, "--task-definition", task_definition_arn,
                   *(["--force-new-deployment"] if restart else [])])


class Repository:
    """Git 기록으로 커밋을 확인하고 변경 경로를 구합니다."""

    def __init__(self, git, head):
        self.git = git
        self.head = head

    def commit(self, tag):
        """커밋 SHA 앞 12자리 태그를 전체 커밋으로 바꿉니다. 기록에 없으면 None입니다."""
        result = self.git(["rev-parse", "--verify", "--quiet", f"{tag}^{{commit}}"])
        commit = result.stdout.strip()
        return commit if result.returncode == 0 and re.fullmatch(r"[0-9a-f]{40}", commit) else None

    def is_ancestor(self, older, newer):
        return self.git(["merge-base", "--is-ancestor", older, newer]).returncode == 0

    def changed_paths(self, commit):
        # -z는 한글 등이 든 경로를 따옴표·8진수로 바꾸지 않고 그대로 냅니다(core.quotePath). 바뀌면 서비스 경로와 맞지 않습니다.
        result = self.git(["diff", "--name-only", "-z", "--no-renames", commit, self.head])
        if result.returncode != 0:
            raise DeployError(f"{commit[:12]}와 {self.head[:12]} 사이의 변경을 읽지 못했습니다.")
        return [path for path in result.stdout.split("\0") if path]


def source_commits(cluster, repository, name, digest):
    """실행 중 이미지(digest) 태그의 커밋 중 이 커밋(HEAD)에 포함된 것을 돌려줍니다.

    다시 실행(Re-run)은 원래 실행의 커밋을 쓰므로, 이후 배포된 더 새 커밋의 이미지를 이전 이미지로 되돌릴 수 있습니다.
    실행 중 이미지가 HEAD보다 새 커밋에서만 나왔으면 멈춥니다.
    """
    tags = [tag for tag in cluster.image_tags(f"travelpay/{name}", digest) if TAG.fullmatch(tag)] if digest else []
    commits = [commit for commit in (repository.commit(tag) for tag in tags) if commit]
    included = [commit for commit in commits if repository.is_ancestor(commit, repository.head)]
    newer = [commit for commit in commits if commit != repository.head and repository.is_ancestor(repository.head, commit)]
    if newer and not included:
        raise DeployError(f"{name}은 이 커밋({repository.head[:12]})보다 새 커밋 {newer[0][:12]}의 이미지로 실행 중입니다. "
                          "이전 실행을 다시 실행하면 운영이 되돌아가므로 멈춥니다. main에서 Run workflow로 실행하세요.")
    return included


def plan(cluster, repository, forced):
    sources, changes = {}, {}
    for key, name in SERVICES.items():
        digest, finished = cluster.running_image(name)
        commits = source_commits(cluster, repository, name, digest)
        sources[key] = commits if finished else None
        for commit in commits if finished else []:
            if commit not in changes:
                changes[commit] = repository.changed_paths(commit)
    return select_services(sources, changes, forced)


def wait_for_rollout(cluster, name, task_definition_arn, previous_id=None, clock=time.monotonic, sleep=time.sleep,
                     timeout=ROLLOUT_TIMEOUT_SECONDS):
    """previous_id는 갱신 전 PRIMARY 배포의 ID입니다(갱신하지 않았으면 None). 새 배포를 찾은 뒤에는 그 ID로만 봅니다."""
    started = clock()
    deadline = started + timeout
    deployment_id = None
    while True:
        service = cluster.service(name)
        if deployment_id is None:
            deployment = started_deployment(service, task_definition_arn, previous_id)
            deployment_id = deployment["id"] if deployment else None
        else:
            deployment = next((item for item in service["deployments"] if item["id"] == deployment_id), None)
        state = rollout_state(deployment)
        if state == "COMPLETED":
            return
        if state == "MISSING" and clock() - started >= MISSING_GRACE_SECONDS:
            raise DeployError(f"{name}의 새 배포가 목록에 없습니다. 실패해 되돌려졌거나 다른 배포로 바뀌었습니다.\n"
                              f"최근 이벤트:\n{recent_events(service)}")
        if state == "FAILED":
            raise DeployError(f"{name} 배포가 실패했습니다(서킷 브레이커): {deployment.get('rolloutStateReason', '')}\n"
                              f"완료된 이전 배포가 있으면 ECS가 그것으로 되돌립니다.\n최근 이벤트:\n{recent_events(service)}")
        if clock() >= deadline:
            raise DeployError(f"{name} 배포가 {timeout // 60}분 안에 끝나지 않았습니다. ECS의 배포는 계속될 수 있고, 다음 실행이 "
                              f"이 서비스를 다시 대상으로 잡습니다.\n최근 이벤트:\n{recent_events(service)}")
        sleep(POLL_SECONDS)


def wait_until_settled(cluster, name, clock=time.monotonic, sleep=time.sleep, timeout=ROLLOUT_TIMEOUT_SECONDS):
    """진행 중인 배포(이전 실행이 남긴 배포·서킷 브레이커의 되돌리기)가 끝날 때까지 기다리고 그때의 Service를 돌려줍니다."""
    deadline = clock() + timeout
    while True:
        service = cluster.service(name)
        if settled(service):
            return service
        if clock() >= deadline:
            raise DeployError(f"{name}에서 진행 중인 배포가 {timeout // 60}분 안에 끝나지 않았습니다.\n"
                              f"최근 이벤트:\n{recent_events(service)}")
        sleep(POLL_SECONDS)


def deploy(cluster, key, image, restart=False, log=print, **wait_options):
    """패밀리의 최신 리비전에 이미지를 넣어 배포합니다.

    다시 실행하면 이미 끝난 서비스도 다시 오므로, 최신 리비전이 이미 이 이미지면 새로 등록하지 않고, Service가 이미 그
    리비전이면 갱신하지 않고 그 배포가 끝났는지만 확인합니다. restart(서비스를 지정한 수동 실행)면 같은 리비전이어도
    새 배포로 Task를 다시 띄웁니다. 다른 배포가 진행 중이면 끝난 뒤 바꿉니다.
    """
    name = SERVICES[key]
    latest = cluster.task_definition(FAMILY_PREFIX + name)
    registration = task_definition_input(latest, name, image)
    if latest["containerDefinitions"][0]["image"] == image:
        arn = latest["taskDefinitionArn"]
    else:
        arn = cluster.register(registration)
        log(f"{name}: {arn.rsplit('/', 1)[-1]} 등록")
    service = cluster.service(name)
    current = primary_deployment(service)
    if (current["taskDefinition"] != arn or restart) and not settled(service):
        # 되돌리기가 진행 중일 때 새 배포를 시작하면, 그 배포가 실패해도 돌아갈 완료 배포가 없어 멈춥니다(ECS 서킷 브레이커 문서).
        log(f"{name}: 진행 중인 배포가 끝나기를 기다린 뒤 바꿉니다")
        current = primary_deployment(wait_until_settled(cluster, name, **wait_options))
    if current["taskDefinition"] == arn and not restart:
        log(f"{name}: 이미 {arn.rsplit('/', 1)[-1]}로 배포했거나 배포 중이라 완료만 확인")
        previous_id = None
    else:
        cluster.update(name, arn, restart=current["taskDefinition"] == arn)
        log(f"{name}: {arn.rsplit('/', 1)[-1]} 롤링 배포 시작")
        previous_id = current["id"]
    wait_for_rollout(cluster, name, arn, previous_id, **wait_options)
    log(f"{name}: 배포 완료")


def deploy_all(cluster, repository, keys, find_image, forced=frozenset(), log=print, **wait_options):
    """배포 순서대로 하나씩 배포하고, 실패하면 뒤의 서비스는 배포하지 않습니다."""
    for key in in_deploy_order(keys):
        source_commits(cluster, repository, SERVICES[key], cluster.running_image(SERVICES[key])[0])
        deploy(cluster, key, find_image(key), restart=key in forced, log=log, **wait_options)


def load_publisher():
    # 이미지 빌드·푸시·digest 확인은 인프라 담당자가 쓰는 publish-images.py와 같은 절차를 씁니다.
    spec = importlib.util.spec_from_file_location("publish_images", Path(__file__).with_name("publish-images.py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


PUBLISHER = load_publisher()


def write_summary(lines):
    path = os.environ.get("GITHUB_STEP_SUMMARY")
    if path:
        with open(path, "a", encoding="utf-8") as summary:
            summary.write("\n".join(lines) + "\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    commands = parser.add_subparsers(dest="command", required=True)
    plan_parser = commands.add_parser("plan")
    plan_parser.add_argument("--force", default="", help="바뀌지 않았어도 배포할 서비스 키(쉼표로 구분)")
    build_parser = commands.add_parser("build")
    build_parser.add_argument("--service", required=True, choices=list(SERVICES))
    deploy_parser = commands.add_parser("deploy")
    deploy_parser.add_argument("--services", required=True, help="plan이 출력한 JSON 배열")
    deploy_parser.add_argument("--force", default="", help="plan에 준 것과 같은 서비스 키. 같은 리비전이어도 다시 띄웁니다")
    args = parser.parse_args()

    def aws(arguments, check=True):
        return subprocess.run(["aws", *arguments, "--region", REGION, "--no-cli-pager"], capture_output=True, text=True,
                              check=check)

    def git(arguments):
        return subprocess.run(["git", "-C", str(ROOT), *arguments], capture_output=True, text=True)

    def run(command, capture=True):
        result = subprocess.run(command, cwd=ROOT, text=True, check=True, stdout=subprocess.PIPE if capture else None)
        return result.stdout if capture else ""

    def login(registry):
        # 암호는 명령 인자나 출력이 아니라 표준 입력으로만 넘깁니다.
        password = aws(["ecr", "get-login-password"]).stdout
        subprocess.run(["docker", "login", "--username", "AWS", "--password-stdin", registry], input=password,
                       capture_output=True, text=True, check=True)

    head = PUBLISHER.source_revision(run)
    cluster = Cluster(aws)

    if args.command == "plan":
        selected, reasons = plan(cluster, Repository(git, head), parse_services(args.force))
        lines = [f"- `{key}`: {reasons[key]}" for key in SERVICES]
        print("\n".join(lines), file=sys.stderr)
        write_summary([f"### 배포 대상 ({head[:12]})", *lines])
        print(json.dumps(selected))
        return

    account = aws(["sts", "get-caller-identity", "--query", "Account", "--output", "text"]).stdout.strip()
    registry = f"{account}.dkr.ecr.{REGION}.amazonaws.com"
    repositories = {key: f"{registry}/travelpay/{name}" for key, name in SERVICES.items()}

    if args.command == "build":
        PUBLISHER.publish(PUBLISHER.plan_images(repositories, head, [args.service]), run, aws, login)
        return

    keys = json.loads(args.services)
    if not isinstance(keys, list) or not keys:
        raise DeployError("배포할 서비스 목록이 비어 있습니다.")
    images = {item["key"]: item for item in PUBLISHER.plan_images(repositories, head, parse_services(",".join(keys)))}

    def find_image(key):
        image = images[key]
        digest = PUBLISHER.existing_digest(aws, image)
        if digest is None:
            raise DeployError(f"{image['module']}:{image['tag']} 이미지가 ECR에 없습니다. build 단계를 확인하세요.")
        return f"{image['url']}@{digest}"

    def log(line):
        print(line)
        write_summary([f"- {line}"])

    deploy_all(cluster, Repository(git, head), keys, find_image, forced=parse_services(args.force), log=log)


if __name__ == "__main__":
    try:
        main()
    except (DeployError, PUBLISHER.PublishError, subprocess.CalledProcessError) as error:
        detail = (getattr(error, "stderr", None) or "").strip()[-500:]
        print(f"Deploy failed: {error}" + (f"\n{detail}" if detail else ""), file=sys.stderr)
        sys.exit(1)
