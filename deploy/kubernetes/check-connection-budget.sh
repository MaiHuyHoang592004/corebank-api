#!/usr/bin/env bash
# Fails when the pods this Deployment can reach would need more PostgreSQL connections
# than PostgreSQL will accept.
#
#   kubectl kustomize deploy/kubernetes > /tmp/rendered.yaml
#   deploy/kubernetes/check-connection-budget.sh /tmp/rendered.yaml
#
# Every pod holds a full Hikari pool, and Hikari's minimum-idle defaults to its maximum,
# so the connections are taken at idle, not only under load. The number that has to fit is
# therefore the largest pod count the cluster can reach, not the replica count in a steady
# state:
#
#   pods     = HPA maxReplicas + Deployment maxSurge + 1 terminating pod still draining
#   required = pods * COREBANK_DB_POOL_SIZE + reserve for an operator and monitoring
#
# The extra terminating pod is not theoretical: a 3-replica rolling update was observed with
# five pods at once (three serving, one surged, one terminating). A pod that is terminating
# does not count against maxSurge, so the next surge pod can start while it still holds its
# connections.
#
# When the render contains no PostgreSQL (the OpenShift overlay expects an external
# database) there is nothing to compare against, so the script prints the figure that
# database must satisfy and exits 0.
set -euo pipefail

file="${1:?usage: $0 <rendered-manifest.yaml>}"
reserve="${DB_CONNECTION_RESERVE:-10}"
default_max_connections=100 # PostgreSQL's own default when nothing overrides it

first() { awk -v re="$1" '$0 ~ re { print $2; exit }' "$file"; }

max_replicas=$(first '^[[:space:]]+maxReplicas:')
max_surge=$(first '^[[:space:]]+maxSurge:')
pool=$(first 'COREBANK_DB_POOL_SIZE:' | tr -d '"')

for pair in "HPA maxReplicas=$max_replicas" "Deployment maxSurge=$max_surge" "COREBANK_DB_POOL_SIZE=$pool"; do
  value="${pair#*=}"
  if ! [[ "$value" =~ ^[0-9]+$ ]]; then
    echo "::error::cannot read a whole number for ${pair%%=*} from $file (got '$value'); a percentage maxSurge is not supported by this check" >&2
    exit 2
  fi
done

pods=$((max_replicas + max_surge + 1))
required=$((pods * pool + reserve))
echo "worst-case pods = $max_replicas maxReplicas + $max_surge maxSurge + 1 terminating = $pods"
echo "required        = $pods pods x $pool pool + $reserve reserve = $required connections"

declared=$(grep -oE 'max_connections=[0-9]+' "$file" | head -1 | cut -d= -f2 || true)
if [[ -n "$declared" ]]; then
  available=$declared
  source_of="declared in the PostgreSQL StatefulSet"
elif grep -qE 'image: postgres:' "$file"; then
  available=$default_max_connections
  source_of="PostgreSQL default (the StatefulSet sets no max_connections)"
else
  echo "no PostgreSQL in this render: the external database needs max_connections >= $required"
  exit 0
fi

echo "available       = $available ($source_of)"
if (( required > available )); then
  echo "::error::connection budget exceeded: $required needed, $available available. Scaling the Deployment to its own maximum would leave pods unable to open a pool. Raise max_connections in deploy/kubernetes/postgres.yaml, or lower maxReplicas or COREBANK_DB_POOL_SIZE." >&2
  exit 1
fi
echo "OK: $((available - required)) connections of headroom"
