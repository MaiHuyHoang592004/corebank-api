#!/usr/bin/env bash
# Fails when the canary's routing objects do not agree with the workloads they route to.
#
#   kubectl kustomize deploy/service-mesh > /tmp/rendered-mesh.yaml
#   deploy/service-mesh/check-mesh-routing.sh /tmp/rendered-mesh.yaml
#
# kubeconform cannot help here: no schema catalogue it ships with covers the Istio kinds,
# and a schema would not catch the mistakes this overlay can actually make. Those are
# agreement mistakes between objects that are each valid on their own:
#
#   - a VirtualService routing to a subset the DestinationRule does not define, which
#     Istio accepts and then answers with 503 for that share of the traffic;
#   - a DestinationRule subset whose label matches no pod, same outcome;
#   - the Service selecting only one version, so the canary never receives traffic;
#   - weights that do not add up to 100;
#   - the database losing its opt-out and being handed a sidecar.
#
# Two of these were real: an unanchored patch target renamed both Deployments to v1, and
# delete patches without a namespace matched nothing. Both rendered without complaint.
set -euo pipefail

file="${1:?usage: $0 <rendered-manifest.yaml>}"
# Pick an interpreter that actually runs: on Windows `python3` is often a Microsoft Store
# alias stub that exits with an advert instead of executing anything.
py=""
for candidate in python3 python py; do
  if command -v "$candidate" >/dev/null 2>&1 && "$candidate" -c "import sys" >/dev/null 2>&1; then
    py="$candidate"; break
  fi
done
[ -n "$py" ] || { echo "::error::this check needs python3 with PyYAML" >&2; exit 1; }

"$py" - "$file" <<'PY'
import sys, collections
try:
    import yaml
except ImportError:
    sys.exit("::error::this check needs PyYAML (pip install pyyaml)")

docs = [d for d in yaml.safe_load_all(open(sys.argv[1], encoding="utf-8")) if d]
by_kind = collections.defaultdict(list)
for d in docs:
    by_kind[d.get("kind")].append(d)

errors = []
def need(cond, msg):
    if not cond:
        errors.append(msg)

# -- the two versions ---------------------------------------------------------
deploys = [d for d in by_kind["Deployment"]
           if d["spec"]["template"]["metadata"]["labels"].get("app.kubernetes.io/name") == "corebank-api"]
need(len(deploys) == 2, f"expected 2 corebank-api Deployments (v1 and v2), found {len(deploys)}")

versions = {}
for d in deploys:
    name = d["metadata"]["name"]
    pod_labels = d["spec"]["template"]["metadata"]["labels"]
    selector = d["spec"]["selector"]["matchLabels"]
    v = pod_labels.get("version")
    need(v is not None, f"Deployment {name}: pod template has no `version` label, so no subset can select it")
    need(selector.get("version") == v,
         f"Deployment {name}: selector version {selector.get('version')!r} does not match pod label {v!r}")
    if v:
        need(v not in versions, f"two Deployments share version {v!r} ({versions.get(v)} and {name})")
        versions[v] = name
need(len(versions) == 2, f"expected two distinct versions, found {sorted(versions)}")

# -- one Service in front of both --------------------------------------------
svcs = [s for s in by_kind["Service"] if s["metadata"]["name"] == "corebank-api"]
need(len(svcs) == 1, f"expected exactly one corebank-api Service, found {len(svcs)}")
if svcs:
    sel = svcs[0]["spec"].get("selector", {})
    need("version" not in sel,
         "the corebank-api Service selects on `version`, so it fronts one revision only and there is nothing to split")
    for v, name in versions.items():
        pod_labels = next(d for d in deploys if d["metadata"]["name"] == name)["spec"]["template"]["metadata"]["labels"]
        need(all(pod_labels.get(k) == val for k, val in sel.items()),
             f"the Service selector {sel} does not match the pods of {name}")

# -- DestinationRule subsets --------------------------------------------------
drs = by_kind["DestinationRule"]
need(len(drs) == 1, f"expected exactly one DestinationRule, found {len(drs)}")
subsets = {}
if drs:
    dr = drs[0]
    need(dr["spec"]["host"].split(".")[0] == "corebank-api",
         f"DestinationRule host {dr['spec']['host']!r} is not the corebank-api Service")
    for s in dr["spec"].get("subsets", []):
        subsets[s["name"]] = s.get("labels", {})
    for name, labels in subsets.items():
        v = labels.get("version")
        need(v in versions,
             f"DestinationRule subset {name!r} selects version {v!r}, which no Deployment has "
             f"(Istio answers 503 for the share routed there)")

# -- VirtualService weights ---------------------------------------------------
vss = by_kind["VirtualService"]
need(len(vss) == 1, f"expected exactly one VirtualService, found {len(vss)}")
for vs in vss:
    for i, route in enumerate(vs["spec"].get("http", [])):
        dests = route.get("route", [])
        total = sum(d.get("weight", 0) for d in dests)
        need(len(dests) == 1 or total == 100,
             f"VirtualService http[{i}]: weights add up to {total}, not 100")
        for d in dests:
            sub = d["destination"].get("subset")
            need(sub is None or sub in subsets,
                 f"VirtualService http[{i}] routes to subset {sub!r}, which the DestinationRule does not define")

# -- mTLS ---------------------------------------------------------------------
pas = by_kind["PeerAuthentication"]
need(len(pas) == 1, f"expected exactly one PeerAuthentication, found {len(pas)}")
for pa in pas:
    mode = pa["spec"].get("mtls", {}).get("mode")
    need(mode in {"STRICT", "PERMISSIVE", "DISABLE", "UNSET"},
         f"PeerAuthentication mtls.mode {mode!r} is not a valid mode")

# -- the database stays out of the mesh ---------------------------------------
for sts in by_kind["StatefulSet"]:
    if sts["metadata"]["name"] == "corebank-postgres":
        ann = sts["spec"]["template"]["metadata"].get("annotations", {})
        need(ann.get("sidecar.istio.io/inject") == "false",
             "the PostgreSQL StatefulSet lost `sidecar.istio.io/inject: \"false\"`; a sidecar in front of the "
             "ledger's only database is a failure mode the overlay deliberately avoids")

if errors:
    for e in errors:
        print(f"::error::{e}", file=sys.stderr)
    sys.exit(1)

print(f"versions   = {', '.join(f'{v} ({n})' for v, n in sorted(versions.items()))}")
for vs in vss:
    for route in vs["spec"].get("http", []):
        split = ", ".join(f"{d['destination'].get('subset')} {d.get('weight', 100)}%" for d in route.get("route", []))
        print(f"routing    = {split}")
print(f"subsets    = {', '.join(f'{k} -> {v}' for k, v in sorted(subsets.items()))}")
print(f"mTLS       = {pas[0]['spec']['mtls']['mode']} (namespace-wide)" if pas else "")
print("OK: every route has a subset, every subset has pods, and the database is not injected")
PY
