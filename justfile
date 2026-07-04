# EuroTransit Marketplace: application repository task runner
# Usage: "just <recipe>". Run "just" or "just --list" to see all recipes

# Run each recipe under bash with safe flags:
# (e) exit on first failure, (u) unset vars are errors, (o pipefail) fail if any pipe stage fails
set shell := ["bash", "-eu", "-o", "pipefail", "-c"]

# Keep the service list in one place so every recipe stays aligned: 6 cooperating services (directory names under services/)
services := "catalog orders inventory payments notifications users"

# TODO: cambiare in base al cluster usato
# Container registry
registry := "lab02g03registry.azurecr.io"
image_prefix := "eurotransit"

# Health endpoint used for local readiness verification (Spring Boot Actuator)
health_path := "/actuator/health/readiness"

# Default: list recipes
default:
    @just --list


#################
# Build & test  #
#################

# Run unit/integration tests for one service, e.g. `just test orders`
test service:
    cd services/{{service}} && ./gradlew test --no-daemon --console=plain

# Run tests for all services (see -euo above)
test-all:
    #!/usr/bin/env bash
    set -euo pipefail
    for s in {{services}}; do
      echo "== testing $s =="
      (cd "services/$s" && ./gradlew test --no-daemon --console=plain)
    done

# Build a runnable jar for one service (runs tests as part of "gradlew build")
build service:
    cd services/{{service}} && ./gradlew build --no-daemon --console=plain

# Run one service locally (H2 + default profile) [e.g. "just run catalog"]; starts the service in the foreground using the local H2 config
run service:
    cd services/{{service}} && ./gradlew bootRun


###############################
# Container lifecycle (local) #
###############################

# Build a container image locally for one service (linux/amd64 as in the labs) Dockerfile performs a multi-stage build and packages the runnable jar
image service tag="dev":
    docker build -t {{registry}}/{{image_prefix}}/{{service}}:{{tag}} --platform linux/amd64 ./services/{{service}}

# Build images for all services with a given tag
images tag="dev":
    #!/usr/bin/env bash
    set -euo pipefail
    for s in {{services}}; do
      docker build -t {{registry}}/{{image_prefix}}/$s:{{tag}} --platform linux/amd64 "./services/$s"
    done

# Run one service's image as a local container on :8080 (default profile = H2, no external deps)
docker-run service tag="dev":
    docker rm -f eurotransit-{{service}} >/dev/null 2>&1 || true
    docker run -d --name eurotransit-{{service}} -p 8080:8080 {{registry}}/{{image_prefix}}/{{service}}:{{tag}}

# Stop and remove a service's local container
@docker-stop service:
    docker rm -f eurotransit-{{service}} >/dev/null 2>&1 || true

# Hit the readiness endpoint of a service running on :8080
@health-check:
    curl -fsS http://localhost:8080{{health_path}}


##########################
# Local DoD verification #
##########################

# Full local verification of ONE service (cleans up the container on exit, success or failure)
verify service:
    #!/usr/bin/env bash
    set -euo pipefail
    name="eurotransit-{{service}}-verify"
    img="{{registry}}/{{image_prefix}}/{{service}}:verify"
    trap 'docker rm -f "$name" >/dev/null 2>&1 || true' EXIT

    echo "== [{{service}}] 1+2. build + test =="
    just build {{service}}

    echo "== [{{service}}] 3. container build =="
    docker build -t "$img" --platform linux/amd64 ./services/{{service}}

    echo "== [{{service}}] 4. run container =="
    docker run -d --name "$name" -p 8080:8080 "$img" >/dev/null

    echo "== [{{service}}] 5. wait for readiness =="
    ready=false
    for i in $(seq 1 30); do
      if curl -fsS "http://localhost:8080{{health_path}}" >/dev/null 2>&1; then ready=true; break; fi
      sleep 2
    done
    [ "$ready" = true ] || { echo "service did not become ready"; exit 1; }

    echo "== [{{service}}] 6. health check =="
    curl -fsS "http://localhost:8080{{health_path}}"; echo
    echo "== [{{service}}] OK (container cleaned up on exit) =="

# Local verification of ALL services, one at a time (wrapper of verify)
verify-all:
    #!/usr/bin/env bash
    set -euo pipefail
    for s in {{services}}; do
      echo "###### verifying $s ######"
      just verify "$s"
    done


###############
# Maintenance #
###############

# Clean Gradle build artifacts for one service
clean service:
    cd services/{{service}} && ./gradlew clean

# Clean all services
clean-all:
    #!/usr/bin/env bash
    set -euo pipefail
    for s in {{services}}; do
        just clean "$s"
    done

# Simulate the CI workflow locally with act (requires nektos/act + Docker)
act-ci:
    act push --artifact-server-path /tmp/act-artifacts

# Wire up shared Git hooks (run once after cloning)
@install-hooks:
    git config core.hooksPath .githooks
    echo "Git hooks installed from .githooks/ (add hooks there, e.g. a pre-push verify)"
