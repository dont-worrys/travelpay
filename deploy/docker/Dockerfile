# syntax=docker/dockerfile:1.10@sha256:865e5dd094beca432e8c0a1d5e1c465db5f998dca4e439981029b3b81fb39ed5
# 모든 서비스가 같은 절차로 이미지를 만듭니다. 빌드는 JDK 21, 실행은 JRE 21이며 기본 이미지는 digest로 고정합니다.
# 사용법: docker build --platform linux/amd64 -f deploy/docker/Dockerfile --build-arg SERVICE=<모듈 이름> .
FROM eclipse-temurin:21-jdk@sha256:ebfbb18d11ff163ee83b21258acf12248af48d22a0fdfdc68fa1a1516c05976a AS build
ARG SERVICE
WORKDIR /workspace
COPY . .
# 테스트는 CI에서 실행합니다. 여기서는 실행 가능한 jar 하나만 만들고 개수가 다르면 실패합니다.
RUN --mount=type=cache,target=/root/.gradle \
    test -n "${SERVICE}" \
    && ./gradlew --no-daemon --quiet ":${SERVICE}:bootJar" \
    && jars="$(find "${SERVICE}/build/libs" -name '*.jar' ! -name '*-plain.jar')" \
    && [ "$(printf '%s\n' "${jars}" | wc -l)" -eq 1 ] \
    && cp "${jars}" /app.jar

FROM eclipse-temurin:21-jre@sha256:0b99b3376fbb50dbf9c0af8535fdd5b8eb0d3fdfc8b1e4967f383b6919f6901a
# 폴더를 먼저 만들어 둡니다. ADD --chmod가 폴더를 만들면 파일 권한(0444)이 폴더에도 적용돼 앱 사용자가 들어갈 수 없습니다.
RUN groupadd --system --gid 10001 app \
    && useradd --system --uid 10001 --gid 10001 --home-dir /nonexistent --no-create-home --shell /usr/sbin/nologin app \
    && install -d -m 0755 /opt/travelpay
# JDBC sslmode=verify-full이 RDS 서버 인증서를 검증하는 CA입니다. AWS가 번들을 바꾸면 체크섬을 확인해 갱신합니다.
ADD --checksum=sha256:913fb5b814f17af79d4c1622584a8d0ceddf5b0d76fe353d0c7d1186cdd6b229 --chmod=0444 \
    https://truststore.pki.rds.amazonaws.com/ap-northeast-2/ap-northeast-2-bundle.pem /opt/travelpay/rds-ca.pem
COPY --from=build --chmod=0444 /app.jar /opt/travelpay/app.jar
COPY --chmod=0555 deploy/docker/entrypoint.sh /opt/travelpay/entrypoint.sh
USER 10001:10001
WORKDIR /opt/travelpay
ENTRYPOINT ["/opt/travelpay/entrypoint.sh"]
