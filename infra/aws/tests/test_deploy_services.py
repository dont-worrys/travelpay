"""CD 배포 대상 선택·Task 정의 갱신·롤링 완료 판단을 가짜 AWS·Git으로 검사합니다. 실제 AWS·Docker는 호출하지 않습니다."""

import copy
import importlib.util
import json
from pathlib import Path
import re
import subprocess
import unittest

ROOT = Path(__file__).parents[3]
path = Path(__file__).parents[1] / "scripts" / "deploy-services.py"
spec = importlib.util.spec_from_file_location("deploy_services", path)
deploy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(deploy)

HEAD = "f" * 40
OLD = "a" * 40
OLDER = "b" * 40
NEWER = "e" * 40
ARN = "arn:aws:ecs:ap-northeast-2:123456789012:task-definition/travelpay-production-user-service:7"
IMAGE = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/travelpay/user-service@sha256:" + "c" * 64
DIGEST = "sha256:" + "d" * 64


def task_definition(name="user-service", revision=6):
    return {
        "taskDefinitionArn": f"arn:aws:ecs:ap-northeast-2:123456789012:task-definition/travelpay-production-{name}:{revision}",
        "family": f"travelpay-production-{name}", "revision": revision, "status": "ACTIVE",
        "taskRoleArn": "arn:aws:iam::123456789012:role/task", "executionRoleArn": "arn:aws:iam::123456789012:role/exec",
        "networkMode": "awsvpc", "requiresCompatibilities": ["EC2"], "memory": "1024", "volumes": [{"name": "tmp"}],
        "placementConstraints": [], "runtimePlatform": {"operatingSystemFamily": "LINUX", "cpuArchitecture": "X86_64"},
        "compatibilities": ["EC2"], "requiresAttributes": [{"name": "ecs.capability.secrets.ssm.environment-variables"}],
        "registeredAt": "2026-11-12T00:00:00Z", "registeredBy": "arn:aws:iam::123456789012:role/admin",
        "containerDefinitions": [{"name": name, "image": "old@sha256:" + "0" * 64, "cpu": 256,
                                  "environment": [{"name": "SPRING_PROFILES_ACTIVE", "value": "prod"}]}],
    }


def service(deployments, name="user-service", events=("(service user-service) has started 1 tasks",)):
    return {"serviceName": name, "status": "ACTIVE", "taskDefinition": deployments[0]["taskDefinition"],
            "deployments": deployments, "events": [{"message": message} for message in events]}


def deployment(arn=ARN, status="PRIMARY", rollout="IN_PROGRESS", running=1, desired=1, reason="", id="ecs-svc/new"):
    return {"id": id, "taskDefinition": arn, "status": status, "rolloutState": rollout, "runningCount": running,
            "desiredCount": desired, "rolloutStateReason": reason}


OLD_DEPLOYMENT = {"arn": "old-arn", "id": "ecs-svc/old"}


def completed(code, stdout="", stderr=""):
    return subprocess.CompletedProcess([], code, stdout, stderr)


class FakeCluster:
    """등록·갱신 호출을 기록합니다. Service 조회는 준 응답을 차례로 돌려주고(마지막 응답은 반복), 응답을 주지 않으면
    갱신 전에는 이전 리비전의 배포, 갱신 뒤에는 새 배포(다른 ID)의 완료로 답합니다."""

    def __init__(self, services=(), definition=None, running=None, fail_update=(), current=None, unfinished=()):
        self.services = list(services)
        self.unfinished = set(unfinished)
        self.definition = definition
        # 갱신 전 PRIMARY 배포(리비전·ID)입니다. 주지 않으면 이전 리비전입니다.
        self.current = current or OLD_DEPLOYMENT
        self.running = running or {}
        self.fail_update = set(fail_update)
        self.updated = {}
        self.calls = []

    def service(self, name):
        self.calls.append(("service", name))
        if self.services:
            return self.services.pop(0) if len(self.services) > 1 else self.services[0]
        if name in self.updated:
            return service([deployment(self.updated[name], rollout="COMPLETED")], name=name)
        return service([deployment(rollout="COMPLETED", **self.current)], name=name)

    def task_definition(self, reference):
        self.calls.append(("task_definition", reference))
        return copy.deepcopy(self.definition or task_definition(reference.replace(deploy.FAMILY_PREFIX, "")))

    def register(self, registration):
        self.calls.append(("register", registration))
        return ARN

    def update(self, name, arn, restart=False):
        self.calls.append(("update", name, arn, restart))
        if name in self.fail_update:
            raise deploy.DeployError(f"{name} 갱신 실패")
        self.updated[name] = arn

    def running_image(self, name):
        return (DIGEST if name in self.running else None), name not in self.unfinished

    def image_tags(self, repository, digest):
        return self.running[repository.split("/", 1)[1]]


class FakeRepository:
    """커밋 사이의 조상 관계를 {커밋: 그 커밋의 조상 집합}으로 흉내 냅니다."""

    def __init__(self, ancestors, head=HEAD, changes=None):
        self.ancestors = ancestors
        self.head = head
        self.changes = changes or {}

    def commit(self, tag):
        return next((commit for commit in [HEAD, OLD, OLDER, NEWER] if commit.startswith(tag)), None)

    def is_ancestor(self, older, newer):
        return older == newer or older in self.ancestors.get(newer, set())

    def changed_paths(self, commit):
        return self.changes.get(commit, [])


class FakeClock:
    def __init__(self):
        self.now = 0

    def __call__(self):
        return self.now

    def sleep(self, seconds):
        self.now += seconds


class ImageChangeRulesTest(unittest.TestCase):
    def test_service_main_code_shared_build_files_and_runtime_files_change_the_image(self):
        for changed, key in [("user-service/src/main/java/A.java", "user"), ("user-service/build.gradle", "user"),
                             ("common/src/main/java/C.java", "wallet"), ("gradle/wrapper/gradle-wrapper.properties", "eureka"),
                             ("build.gradle", "gateway"), ("deploy/docker/Dockerfile", "mock_bank"),
                             ("deploy/docker/entrypoint.sh", "ledger"), (".dockerignore", "payment"),
                             ("user-service/src/main/resources/메시지.properties", "user")]:
            with self.subTest(path=changed, service=key):
                self.assertTrue(deploy.affects_image(changed, key))

    def test_tests_load_tests_docs_infra_and_other_services_do_not_change_the_image(self):
        for changed, key in [("user-service/src/test/java/ATest.java", "user"), ("wallet-service/load-test/README.md", "wallet"),
                             ("common/src/test/java/CTest.java", "user"), ("common/src/main/java/C.java", "eureka"),
                             ("docs/guide/ci-cd.md", "user"), ("infra/aws/alarms.tf", "gateway"),
                             (".github/workflows/deploy.yml", "user"), ("payment-service/src/main/java/P.java", "user"),
                             ("user-service/build/libs/app.jar", "user"), ("user-service/app.log", "user"),
                             ("user-service/.env.local", "user")]:
            with self.subTest(path=changed, service=key):
                self.assertFalse(deploy.affects_image(changed, key))

    def test_services_match_the_terraform_runtime_contract(self):
        contract = (ROOT / "infra" / "aws" / "runtime-contract.tf").read_text()
        names = dict(re.findall(r'^\s+(\w+)\s+= \{ name = "([^"]+)"', contract, re.M))
        self.assertEqual(names, deploy.SERVICES)
        self.assertIn(f'runtime_cluster_name     = "{deploy.CLUSTER}"', contract)
        self.assertIn(f'runtime_name_prefix      = "{deploy.FAMILY_PREFIX[:-1]}"', contract)

    def test_common_users_match_gradle_dependencies(self):
        users = {key for key, name in deploy.SERVICES.items()
                 if "project(':common')" in (ROOT / name / "build.gradle").read_text()}
        self.assertEqual(users, set(deploy.COMMON_USERS))

    def test_every_file_sent_to_the_image_build_is_covered(self):
        # .dockerignore가 이미지 빌드에 보내는 경로를 바꾸면 배포 대상 판단도 함께 바꿔야 합니다.
        included = [line[1:].rstrip("/") for line in (ROOT / ".dockerignore").read_text().splitlines() if line.startswith("!")]
        modules = set(deploy.SERVICES.values()) | {"common"}
        uncovered = [entry for entry in included
                     if entry not in deploy.SHARED_FILES and entry + "/" not in deploy.SHARED_DIRECTORIES and entry not in modules]
        self.assertEqual(uncovered, [])

    def test_exclusions_match_the_dockerignore(self):
        # .dockerignore가 빼는 경로가 바뀌면 EXCLUDED도 함께 바꿔야 합니다. 각 줄의 예시 경로가 이미지 변경에서 빠지는지 봅니다.
        lines = [line for line in (ROOT / ".dockerignore").read_text().splitlines()
                 if line and not line.startswith(("#", "!")) and line != "*"]
        self.assertEqual(lines, ["**/build/", "**/.gradle/", "**/out/", "**/src/test/", "**/load-test/", "**/*.log", "**/.env*"])
        for line in lines:
            sample = line.replace("**/", "user-service/").replace("*", "a")
            sample = sample + "x" if sample.endswith("/") else sample
            with self.subTest(pattern=line):
                self.assertFalse(deploy.affects_image(sample, "user"))

    def test_terraform_leaves_the_running_revision_to_cd(self):
        # Terraform 테스트는 lifecycle을 읽지 못하므로 파일에서 확인합니다. 빠지면 다음 apply가 CD 배포를 되돌립니다.
        services = (ROOT / "infra" / "aws" / "app-services.tf").read_text()
        self.assertRegex(services, r"lifecycle \{\s+ignore_changes = \[task_definition\]\s+\}")


class SelectServicesTest(unittest.TestCase):
    def test_services_are_selected_in_dependency_order(self):
        sources = {key: [OLD] for key in deploy.SERVICES}
        changes = {OLD: ["gateway/src/main/java/G.java", "ledger-service/src/main/java/L.java", "payment-service/src/main/java/P.java"]}
        selected, reasons = deploy.select_services(sources, changes, set())
        self.assertEqual(selected, ["ledger", "payment", "gateway"])
        self.assertEqual(reasons["user"], f"{OLD[:12]} 이후 이미지 변경 없음")

    def test_any_tag_without_later_changes_means_the_image_is_current(self):
        # 같은 digest에 붙은 태그는 이미지 내용이 같은 빌드입니다. 더 최근 태그 이후로 변경이 없으면 배포하지 않습니다.
        changes = {OLD: [], OLDER: ["user-service/src/main/java/A.java"]}
        selected, _ = deploy.select_services({"user": [OLDER, OLD]}, changes, set())
        self.assertNotIn("user", selected)

    def test_unknown_source_and_forced_services_are_deployed(self):
        sources = {key: [OLD] for key in deploy.SERVICES}
        sources["eureka"] = []
        selected, reasons = deploy.select_services(sources, {OLD: []}, {"gateway"})
        self.assertEqual(selected, ["eureka", "gateway"])
        self.assertEqual(reasons["gateway"], "요청에 따라 다시 배포")

    def test_service_keys_are_validated_and_ordered(self):
        self.assertEqual(deploy.parse_services(" user, gateway ,"), {"user", "gateway"})
        self.assertEqual(deploy.parse_services(""), set())
        with self.assertRaises(deploy.DeployError):
            deploy.parse_services("user,user-service")
        self.assertEqual(deploy.in_deploy_order(["gateway", "user", "eureka"]), ["eureka", "user", "gateway"])


class SourceCommitTest(unittest.TestCase):
    def test_tags_included_in_head_are_sources(self):
        cluster = FakeCluster(running={"user-service": ["latest", OLD[:12], OLDER[:12], "123456789abc"]})
        repository = FakeRepository({HEAD: {OLD, OLDER}})
        self.assertEqual(deploy.source_commits(cluster, repository, "user-service", DIGEST), [OLD, OLDER])

    def test_running_head_itself_is_a_source(self):
        cluster = FakeCluster(running={"user-service": [HEAD[:12]]})
        self.assertEqual(deploy.source_commits(cluster, FakeRepository({}), "user-service", DIGEST), [HEAD])

    def test_image_built_only_from_a_newer_commit_stops_a_rerun(self):
        # 이전 실행을 다시 실행하면 HEAD가 원래 커밋이라, 이미 배포된 새 커밋의 이미지를 되돌리게 됩니다.
        cluster = FakeCluster(running={"user-service": [NEWER[:12]]})
        with self.assertRaises(deploy.DeployError) as raised:
            deploy.source_commits(cluster, FakeRepository({NEWER: {HEAD}}), "user-service", DIGEST)
        self.assertIn("다시 실행", str(raised.exception))

    def test_unrelated_or_missing_images_have_no_source(self):
        cluster = FakeCluster(running={"user-service": [NEWER[:12]]})
        self.assertEqual(deploy.source_commits(cluster, FakeRepository({}), "user-service", DIGEST), [])
        self.assertEqual(deploy.source_commits(FakeCluster(), FakeRepository({}), "user-service", None), [])

    def test_plan_compares_each_source_with_head(self):
        running = {name: [OLD[:12]] for name in deploy.SERVICES.values()}
        del running["eureka-server"]
        repository = FakeRepository({HEAD: {OLD}}, changes={OLD: ["wallet-service/src/main/java/W.java"]})
        selected, reasons = deploy.plan(FakeCluster(running=running), repository, set())
        self.assertEqual(selected, ["eureka", "wallet"])
        self.assertIn("알 수 없어", reasons["eureka"])

    def test_service_whose_last_rollout_has_not_finished_is_selected(self):
        # 시간 초과로 멈춘 배포는 Service의 taskDefinition이 이미 새 리비전이라 바뀌지 않은 것처럼 보입니다. 다시 대상으로 잡습니다.
        running = {name: [HEAD[:12]] for name in deploy.SERVICES.values()}
        selected, reasons = deploy.plan(FakeCluster(running=running, unfinished={"ledger-service"}), FakeRepository({}), set())
        self.assertEqual(selected, ["ledger"])
        self.assertIn("끝나지 않았거나", reasons["ledger"])


class RepositoryTest(unittest.TestCase):
    def test_tags_resolve_to_full_commits_and_ancestry_is_checked_in_order(self):
        calls = []

        def git(arguments):
            calls.append(arguments)
            if arguments[0] == "rev-parse":
                return completed(0, OLD + "\n") if arguments[3].startswith(OLD[:12]) else completed(1)
            return completed(0 if arguments[2:] == [OLD, HEAD] else 1)

        repository = deploy.Repository(git, HEAD)
        self.assertEqual(repository.commit(OLD[:12]), OLD)
        self.assertIsNone(repository.commit("123456789abc"))
        self.assertTrue(repository.is_ancestor(OLD, HEAD))
        self.assertFalse(repository.is_ancestor(HEAD, OLD))
        self.assertEqual(calls[-1], ["merge-base", "--is-ancestor", HEAD, OLD])

    def test_changed_paths_are_read_verbatim_without_rename_pairing(self):
        calls = []

        def git(arguments):
            calls.append(arguments)
            return completed(0, "user-service/src/main/resources/메시지.properties\0build.gradle\0")

        paths = deploy.Repository(git, HEAD).changed_paths(OLD)
        self.assertEqual(paths, ["user-service/src/main/resources/메시지.properties", "build.gradle"])
        self.assertIn("-z", calls[0])
        self.assertIn("--no-renames", calls[0])

    def test_changed_paths_failure_is_reported(self):
        with self.assertRaises(deploy.DeployError):
            deploy.Repository(lambda arguments: completed(128, "", "bad object"), HEAD).changed_paths(OLD)


class TaskDefinitionTest(unittest.TestCase):
    def test_only_registration_fields_are_kept_and_only_the_image_changes(self):
        original = task_definition()
        registration = deploy.task_definition_input(original, "user-service", IMAGE)
        self.assertEqual(set(registration) - set(deploy.REGISTER_KEYS), set())
        for key in ("taskDefinitionArn", "revision", "status", "compatibilities", "requiresAttributes", "registeredAt", "registeredBy"):
            self.assertNotIn(key, registration)
        container = registration["containerDefinitions"][0]
        self.assertEqual(container["image"], IMAGE)
        self.assertEqual({k: v for k, v in container.items() if k != "image"},
                         {k: v for k, v in original["containerDefinitions"][0].items() if k != "image"})
        self.assertEqual(original["containerDefinitions"][0]["image"], "old@sha256:" + "0" * 64)

    def test_unexpected_containers_are_rejected(self):
        definition = task_definition()
        definition["containerDefinitions"].append({"name": "sidecar", "image": "x"})
        for name, value in [("user-service", definition), ("gateway", task_definition())]:
            with self.subTest(name=name), self.assertRaises(deploy.DeployError):
                deploy.task_definition_input(value, name, IMAGE)


class RolloutTest(unittest.TestCase):
    def test_rollout_states(self):
        cases = [
            (deployment(rollout="COMPLETED"), "COMPLETED"),
            (deployment(rollout="COMPLETED", running=0), "IN_PROGRESS"),
            (deployment(rollout="COMPLETED", status="ACTIVE"), "IN_PROGRESS"),
            (deployment(), "IN_PROGRESS"),
            (deployment(status="ACTIVE", rollout="FAILED"), "FAILED"),
            (None, "MISSING"),
            ({k: v for k, v in deployment(rollout="COMPLETED").items() if k != "rolloutState"}, "IN_PROGRESS"),
        ]
        for value, expected in cases:
            with self.subTest(expected=expected, deployment=value):
                self.assertEqual(deploy.rollout_state(value), expected)

    def test_settled_means_no_rollout_in_progress(self):
        old = deployment(status="ACTIVE", rollout="COMPLETED", **OLD_DEPLOYMENT)
        self.assertTrue(deploy.settled(service([deployment(rollout="COMPLETED")])))
        self.assertTrue(deploy.settled(service([deployment(rollout="FAILED"), old])))
        # 서킷 브레이커는 되돌릴 이전 완료 배포를 다시 IN_PROGRESS로 바꿉니다. PRIMARY가 어느 쪽이든 진행 중으로 봅니다.
        self.assertFalse(deploy.settled(service([deployment(rollout="FAILED"), dict(old, rolloutState="IN_PROGRESS")])))
        self.assertFalse(deploy.settled(service([deployment()])))

    def test_started_deployment_is_the_new_primary_with_this_revision(self):
        old = deployment(rollout="COMPLETED", **OLD_DEPLOYMENT)
        new = deployment()
        self.assertEqual(deploy.started_deployment(service([new, dict(old, status="ACTIVE")]), ARN, "ecs-svc/old"), new)
        # 갱신이 아직 조회에 보이지 않으면 이전 PRIMARY만 있습니다.
        self.assertIsNone(deploy.started_deployment(service([old]), ARN, "ecs-svc/old"))
        # 같은 리비전으로 다시 띄우면 리비전은 같아도 갱신 전 PRIMARY와 ID가 다릅니다.
        self.assertIsNone(deploy.started_deployment(service([deployment("other-arn", id="ecs-svc/other")]), ARN, "ecs-svc/old"))
        same = deployment(rollout="COMPLETED", id="ecs-svc/old")
        self.assertIsNone(deploy.started_deployment(service([same]), ARN, "ecs-svc/old"))
        self.assertEqual(deploy.started_deployment(service([same]), ARN, None), same)
        with self.assertRaises(deploy.DeployError):
            deploy.started_deployment(service([dict(new, status="ACTIVE")]), ARN, None)

    def test_deploy_registers_from_the_latest_revision_updates_and_waits(self):
        cluster = FakeCluster([service([deployment(rollout="COMPLETED", **OLD_DEPLOYMENT)]),
                               service([deployment(), deployment(status="ACTIVE", **OLD_DEPLOYMENT)]),
                               service([deployment(rollout="COMPLETED")])])
        clock, logs = FakeClock(), []
        deploy.deploy(cluster, "user", IMAGE, log=logs.append, clock=clock, sleep=clock.sleep)
        # Service가 쓰는 리비전이 아니라 패밀리의 최신 리비전에서 만듭니다. Terraform이 바꾼 설정도 다음 배포에 반영됩니다.
        self.assertEqual(cluster.calls[0], ("task_definition", "travelpay-production-user-service"))
        registrations = [call[1] for call in cluster.calls if call[0] == "register"]
        self.assertEqual([item["containerDefinitions"][0]["image"] for item in registrations], [IMAGE])
        self.assertEqual([call for call in cluster.calls if call[0] == "update"], [("update", "user-service", ARN, False)])
        self.assertEqual(clock.now, deploy.POLL_SECONDS)
        self.assertEqual(logs[-1], "user-service: 배포 완료")

    def current_revision(self):
        current = dict(task_definition(), taskDefinitionArn=ARN)
        current["containerDefinitions"] = [dict(current["containerDefinitions"][0], image=IMAGE)]
        return current

    def test_rerun_reuses_a_revision_that_already_has_the_image(self):
        # 실패한 작업만 다시 실행하면 이미 끝난 서비스도 다시 옵니다. 같은 이미지의 리비전을 또 등록하거나 다시 배포하지 않습니다.
        cases = [
            ("이미 배포 완료", [service([deployment(rollout="COMPLETED", id="ecs-svc/done")])], []),
            ("등록 뒤 갱신 전 중단", [service([deployment(rollout="COMPLETED", **OLD_DEPLOYMENT)]),
                                 service([deployment(rollout="COMPLETED")])], [("update", "user-service", ARN, False)]),
        ]
        for case, services, updates in cases:
            with self.subTest(case=case):
                cluster, clock, logs = FakeCluster(services, definition=self.current_revision()), FakeClock(), []
                deploy.deploy(cluster, "user", IMAGE, log=logs.append, clock=clock, sleep=clock.sleep)
                self.assertFalse([call for call in cluster.calls if call[0] == "register"])
                self.assertEqual([call for call in cluster.calls if call[0] == "update"], updates)
                self.assertEqual(logs[-1], "user-service: 배포 완료")

    def test_forced_service_on_the_same_revision_is_restarted_and_waits_for_the_new_deployment(self):
        # 바꾼 SSM 비밀값은 새 Task부터 반영되므로, 지정한 서비스는 같은 리비전이어도 새 배포로 다시 띄웁니다.
        done = deployment(rollout="COMPLETED", id="ecs-svc/done")
        restarted = deployment(id="ecs-svc/restart")
        cluster = FakeCluster([service([done]), service([done]), service([restarted, dict(done, status="ACTIVE")]),
                               service([dict(restarted, rolloutState="COMPLETED")])], definition=self.current_revision())
        clock = FakeClock()
        deploy.deploy(cluster, "user", IMAGE, restart=True, log=lambda line: None, clock=clock, sleep=clock.sleep)
        self.assertFalse([call for call in cluster.calls if call[0] == "register"])
        self.assertEqual([call for call in cluster.calls if call[0] == "update"], [("update", "user-service", ARN, True)])
        # 이전 배포(완료 상태)를 새 배포로 착각하지 않고, 새 배포가 끝날 때까지 기다립니다.
        self.assertEqual(clock.now, 2 * deploy.POLL_SECONDS)

    def test_forced_service_on_another_revision_is_updated_normally(self):
        cluster = FakeCluster(definition=self.current_revision())
        deploy.deploy(cluster, "user", IMAGE, restart=True, log=lambda line: None, clock=FakeClock(), sleep=lambda seconds: None)
        self.assertEqual([call for call in cluster.calls if call[0] == "update"], [("update", "user-service", ARN, False)])

    def test_reused_revision_is_checked_like_a_new_one(self):
        current = self.current_revision()
        current["containerDefinitions"].append({"name": "sidecar", "image": "x"})
        cluster = FakeCluster(definition=current)
        with self.assertRaises(deploy.DeployError):
            deploy.deploy(cluster, "user", IMAGE, log=lambda line: None)
        self.assertFalse([call for call in cluster.calls if call[0] in ("register", "update")])

    def test_failed_rollout_reports_reason_and_events(self):
        failed = service([deployment(**OLD_DEPLOYMENT), deployment(status="ACTIVE", rollout="FAILED", reason="circuit breaker")],
                         events=("(service user-service) deployment failed: tasks failed to start.",))
        cluster = FakeCluster([service([deployment()]), failed])
        clock = FakeClock()
        with self.assertRaises(deploy.DeployError) as raised:
            deploy.wait_for_rollout(cluster, "user-service", ARN, "ecs-svc/old", clock=clock, sleep=clock.sleep)
        self.assertIn("circuit breaker", str(raised.exception))
        self.assertIn("tasks failed to start", str(raised.exception))
        # 되돌릴 완료 배포가 없으면 ECS는 되돌리지 않고 멈추므로, 되돌린다고 단정하지 않습니다.
        self.assertIn("완료된 이전 배포가 있으면", str(raised.exception))

    def test_new_deployment_missing_briefly_is_tolerated(self):
        cluster = FakeCluster([service([deployment(rollout="COMPLETED", **OLD_DEPLOYMENT)]), service([deployment(rollout="COMPLETED")])])
        clock = FakeClock()
        deploy.wait_for_rollout(cluster, "user-service", ARN, "ecs-svc/old", clock=clock, sleep=clock.sleep)
        self.assertEqual(clock.now, deploy.POLL_SECONDS)

    def test_new_deployment_missing_after_the_grace_period_is_reported_with_events(self):
        rolled_back = service([deployment(**OLD_DEPLOYMENT)], events=("(service user-service) rolling back to deployment X",))
        clock = FakeClock()
        with self.assertRaises(deploy.DeployError) as raised:
            deploy.wait_for_rollout(FakeCluster([rolled_back]), "user-service", ARN, "ecs-svc/old", clock=clock,
                                    sleep=clock.sleep)
        self.assertIn("되돌려졌거나", str(raised.exception))
        self.assertIn("rolling back", str(raised.exception))
        self.assertEqual(clock.now, deploy.MISSING_GRACE_SECONDS)

    def test_found_deployment_that_disappears_is_reported(self):
        cluster = FakeCluster([service([deployment()]), service([deployment(**OLD_DEPLOYMENT)])])
        clock = FakeClock()
        with self.assertRaises(deploy.DeployError) as raised:
            deploy.wait_for_rollout(cluster, "user-service", ARN, "ecs-svc/old", clock=clock, sleep=clock.sleep)
        self.assertIn("되돌려졌거나", str(raised.exception))

    def test_rollout_times_out(self):
        clock = FakeClock()
        with self.assertRaises(deploy.DeployError) as raised:
            deploy.wait_for_rollout(FakeCluster([service([deployment()])]), "user-service", ARN, "ecs-svc/old", clock=clock,
                                    sleep=clock.sleep, timeout=60)
        self.assertIn("1분 안에", str(raised.exception))
        self.assertIn("다음 실행이", str(raised.exception))
        self.assertEqual(clock.now, 60)

    def test_another_rollout_in_progress_is_waited_for_before_updating(self):
        # 이전 실행이 남긴 배포나 서킷 브레이커의 되돌리기가 진행 중이면, 끝난 뒤에 새 배포를 시작합니다.
        rolling_back = service([deployment("failed-arn", rollout="FAILED", id="ecs-svc/failed"),
                                deployment(status="ACTIVE", **OLD_DEPLOYMENT)])
        rolled_back = service([deployment(rollout="COMPLETED", **OLD_DEPLOYMENT)])
        cluster = FakeCluster([rolling_back, rolling_back, rolled_back, service([deployment(rollout="COMPLETED")])])
        clock = FakeClock()
        deploy.deploy(cluster, "user", IMAGE, log=lambda line: None, clock=clock, sleep=clock.sleep)
        service_calls_before_update = [call for call in cluster.calls[:cluster.calls.index(("update", "user-service", ARN, False))]
                                       if call[0] == "service"]
        self.assertEqual(len(service_calls_before_update), 3)
        self.assertEqual(clock.now, deploy.POLL_SECONDS)

    def test_same_revision_in_progress_is_followed_without_waiting_twice(self):
        current = dict(task_definition(), taskDefinitionArn=ARN)
        current["containerDefinitions"] = [dict(current["containerDefinitions"][0], image=IMAGE)]
        in_progress = service([deployment(id="ecs-svc/earlier"), deployment(status="ACTIVE", **OLD_DEPLOYMENT)])
        cluster = FakeCluster([in_progress, in_progress, service([deployment(rollout="COMPLETED", id="ecs-svc/earlier")])],
                              definition=current)
        clock = FakeClock()
        deploy.deploy(cluster, "user", IMAGE, log=lambda line: None, clock=clock, sleep=clock.sleep)
        self.assertFalse([call for call in cluster.calls if call[0] in ("register", "update")])
        self.assertEqual(clock.now, deploy.POLL_SECONDS)

    def test_rollout_that_never_settles_stops_before_updating(self):
        stuck = service([deployment("other-arn", id="ecs-svc/other")])
        cluster, clock = FakeCluster([stuck]), FakeClock()
        with self.assertRaises(deploy.DeployError) as raised:
            deploy.deploy(cluster, "user", IMAGE, log=lambda line: None, clock=clock, sleep=clock.sleep, timeout=60)
        self.assertIn("진행 중인 배포", str(raised.exception))
        self.assertEqual(clock.now, 60)
        self.assertFalse([call for call in cluster.calls if call[0] == "update"])


class DeployAllTest(unittest.TestCase):
    def options(self):
        clock = FakeClock()
        return {"log": lambda line: None, "clock": clock, "sleep": clock.sleep}

    def test_services_are_deployed_in_order_and_stop_at_the_first_failure(self):
        cluster = FakeCluster(fail_update={"ledger-service"})
        images = []
        with self.assertRaises(deploy.DeployError):
            deploy.deploy_all(cluster, FakeRepository({}), ["payment", "ledger", "eureka"],
                              lambda key: images.append(key) or IMAGE, **self.options())
        self.assertEqual(images, ["eureka", "ledger"])
        self.assertEqual([call[1] for call in cluster.calls if call[0] == "update"], ["eureka-server", "ledger-service"])

    def test_only_forced_services_are_restarted(self):
        # 이미 이 이미지·리비전으로 실행 중인 서비스: 지정하지 않았으면 그대로 두고, 지정했으면 다시 띄웁니다.
        definition = dict(task_definition(), taskDefinitionArn=ARN)
        definition["containerDefinitions"] = [dict(definition["containerDefinitions"][0], image=IMAGE)]
        for forced, updates in [(set(), []), ({"user"}, [("update", "user-service", ARN, True)])]:
            with self.subTest(forced=forced):
                cluster = FakeCluster(definition=definition, current={"arn": ARN, "id": "ecs-svc/done"})
                deploy.deploy_all(cluster, FakeRepository({}), ["user"], lambda key: IMAGE, forced=forced, **self.options())
                self.assertEqual([call for call in cluster.calls if call[0] == "update"], updates)

    def test_forcing_one_service_does_not_restart_the_others(self):
        definition = dict(task_definition(), taskDefinitionArn=ARN)
        definition["containerDefinitions"] = [dict(definition["containerDefinitions"][0], image=IMAGE)]

        class PerServiceDefinitions(FakeCluster):
            def task_definition(self, reference):
                name = reference.replace(deploy.FAMILY_PREFIX, "")
                value = dict(definition, family=reference)
                value["containerDefinitions"] = [dict(definition["containerDefinitions"][0], name=name)]
                return value

        cluster = PerServiceDefinitions(current={"arn": ARN, "id": "ecs-svc/done"})
        deploy.deploy_all(cluster, FakeRepository({}), ["ledger", "user"], lambda key: IMAGE, forced={"user"}, **self.options())
        self.assertEqual([call for call in cluster.calls if call[0] == "update"], [("update", "user-service", ARN, True)])

    def test_rerun_of_an_older_commit_stops_before_changing_anything(self):
        cluster = FakeCluster(running={"user-service": [NEWER[:12]]})
        with self.assertRaises(deploy.DeployError):
            deploy.deploy_all(cluster, FakeRepository({NEWER: {HEAD}}), ["user"], lambda key: IMAGE, **self.options())
        self.assertFalse([call for call in cluster.calls if call[0] in ("register", "update")])

    def test_missing_image_stops_before_registering(self):
        def find_image(key):
            raise deploy.DeployError("이미지가 ECR에 없습니다")

        cluster = FakeCluster()
        with self.assertRaises(deploy.DeployError):
            deploy.deploy_all(cluster, FakeRepository({}), ["user"], find_image, **self.options())
        self.assertFalse([call for call in cluster.calls if call[0] in ("register", "update")])


class ClusterTest(unittest.TestCase):
    def test_aws_errors_name_the_operation(self):
        cluster = deploy.Cluster(lambda arguments, check=True: completed(255, "", "AccessDeniedException: not allowed"))
        with self.assertRaises(deploy.DeployError) as raised:
            cluster.task_definition("travelpay-production-user-service")
        self.assertIn("ecs describe-task-definition", str(raised.exception))
        self.assertIn("AccessDeniedException", str(raised.exception))

    def test_missing_service_is_not_created(self):
        cluster = deploy.Cluster(lambda arguments, check=True: completed(0, '{"services": [], "failures": [{"reason": "MISSING"}]}'))
        with self.assertRaises(deploy.DeployError):
            cluster.service("user-service")

    def test_running_image_is_read_from_the_primary_deployment(self):
        # Service의 taskDefinition은 갱신 즉시 새 리비전을 가리키므로, 이미지는 PRIMARY 배포의 리비전에서 읽고 끝났는지를 함께 봅니다.
        cases = [
            (IMAGE, [deployment("primary-td", rollout="COMPLETED")], ("sha256:" + "c" * 64, True)),
            ("repo:latest", [deployment("primary-td", rollout="COMPLETED")], (None, True)),
            (IMAGE, [deployment("primary-td"), deployment("old-td", status="ACTIVE", rollout="COMPLETED", id="ecs-svc/old")],
             ("sha256:" + "c" * 64, False)),
            (IMAGE, [deployment("primary-td", rollout="FAILED")], ("sha256:" + "c" * 64, False)),
            # PRIMARY는 끝났어도 서킷 브레이커의 되돌리기처럼 다른 배포가 진행 중이면 끝난 것으로 보지 않습니다.
            (IMAGE, [deployment("primary-td", rollout="COMPLETED"), deployment("old-td", status="ACTIVE", id="ecs-svc/old")],
             ("sha256:" + "c" * 64, False)),
        ]
        for image, deployments, expected in cases:
            calls = []
            responses = iter([
                completed(0, json.dumps({"services": [dict(service(deployments), taskDefinition="desired-td")]})),
                completed(0, json.dumps({"taskDefinition": {"containerDefinitions": [{"image": image}]}})),
            ])

            def aws(arguments, check=True):
                calls.append(arguments)
                return next(responses)

            with self.subTest(image=image, deployments=deployments):
                self.assertEqual(deploy.Cluster(aws).running_image("user-service"), expected)
                self.assertEqual(calls[1][:4], ["ecs", "describe-task-definition", "--task-definition", "primary-td"])

    def test_expired_running_image_has_no_tags_but_other_errors_stop(self):
        expired = deploy.Cluster(lambda arguments, check=True: completed(254, "", "An error occurred (ImageNotFoundException)"))
        self.assertEqual(expired.image_tags("travelpay/user-service", DIGEST), [])
        denied = deploy.Cluster(lambda arguments, check=True: completed(254, "", "An error occurred (AccessDeniedException)"))
        with self.assertRaises(deploy.DeployError):
            denied.image_tags("travelpay/user-service", DIGEST)

    def test_register_and_update_arguments(self):
        calls = []

        def aws(arguments, check=True):
            calls.append(arguments)
            return completed(0, '{"taskDefinition": {"taskDefinitionArn": "%s"}, "service": {}}' % ARN)

        cluster = deploy.Cluster(aws)
        self.assertEqual(cluster.register({"family": "travelpay-production-user-service"}), ARN)
        cluster.update("user-service", ARN)
        cluster.update("user-service", ARN, restart=True)
        self.assertEqual(calls[0][:3], ["ecs", "register-task-definition", "--cli-input-json"])
        self.assertEqual(calls[0][3], '{"family": "travelpay-production-user-service"}')
        update = ["ecs", "update-service", "--cluster", "travelpay-production", "--service", "user-service", "--task-definition", ARN]
        self.assertEqual(calls[1], update + ["--output", "json"])
        self.assertEqual(calls[2], update + ["--force-new-deployment", "--output", "json"])


if __name__ == "__main__":
    unittest.main()
