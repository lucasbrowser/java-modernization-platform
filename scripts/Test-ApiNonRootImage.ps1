param(
    [string]$Image = "java-modernization-platform:t04"
)

$ErrorActionPreference = "Stop"

docker build --tag $Image .
if ($LASTEXITCODE -ne 0) {
    throw "Docker image build failed with exit code $LASTEXITCODE"
}

$declaredUser = docker image inspect --format '{{.Config.User}}' $Image
if ($LASTEXITCODE -ne 0) {
    throw "Could not inspect the built image"
}
if ($declaredUser.Trim() -ne "10001:10001") {
    throw "Expected image user 10001:10001, found '$($declaredUser.Trim())'"
}

$checks = @'
set -eu
test "$(id -u)" = "10001"
test "$(id -g)" = "10001"
test -r /app/app.jar
test ! -w /app
test ! -w /
test ! -w /opt/java/openjdk
test ! -w /etc/ssl/certs
test -w /tmp
test -r "$JAVA_HOME/lib/security/cacerts"
echo "Effective UID=$(id -u) GID=$(id -g)"
echo "JAR readable; /app not writable; /tmp writable; JVM truststore readable"
exec java -version
'@

docker run --rm --entrypoint /bin/sh $Image -ec $checks
if ($LASTEXITCODE -ne 0) {
    throw "Nonroot image checks failed with exit code $LASTEXITCODE"
}
