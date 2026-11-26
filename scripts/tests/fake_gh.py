"""A fake `gh` with one GitHub repository behind it, for the shield_cloud_setup.py and
shield_cloud_build.py tests.

The state is JSON at $FAKE_GH_STATE and persists between calls, so a second run sees the first run's
writes. Every call is appended to $FAKE_GH_LOG as {"argv": [...], "stdin": "...", "write": bool}.
Hooks in the state: repo.visibility (PUBLIC makes the repo public), repo.default_branch.

Commands: repo view/create; secret list/set (the value only from stdin); release list/create/download;
workflow run; run view/list/download; api GET/PUT/DELETE on repos/<repo>/actions/permissions...,
contents/<path>, releases/tags/<tag>, actions/runs/<id>/artifacts and actions/artifacts/<id>.
Responses follow the GitHub REST and gh shapes the scripts read (checked against real runs of the build
repo); a missing resource is `gh: Not Found (HTTP 404)` with exit 1, as gh prints it.

Cloud build hooks in the state:
- run_views (default 2): `run view` calls until a dispatched run completes; run_conclusion (default
  success); no_artifact; dispatch_url ("printed", "none" or "other"); dispatch_fails;
  artifact_delete_fails; move_head (a checkout that gets an empty commit when a run completes).
- artifact: what a successful run uploads, see artifact_files().
- release_files: {tag: {asset: path}} for `release download`.
- state_dir: the scripts' .local/cloud-builds; `workflow run` records whether the request's state
  file existed when it was called.
"""

import base64
import hashlib
import json
import os
import shutil
import subprocess
import sys
from pathlib import Path

DEFAULT_SETTINGS = {
    "/actions/permissions": {"enabled": True, "allowed_actions": "all"},
    "/actions/permissions/selected-actions": {"github_owned_allowed": False, "verified_allowed": False,
                                              "patterns_allowed": []},
    "/actions/permissions/workflow": {"default_workflow_permissions": "write", "can_approve_pull_request_reviews": False},
    "/actions/permissions/artifact-and-log-retention": {"days": 90, "maximum_allowed_days": 90},
}
WRITES = {("repo", "create"), ("secret", "set"), ("release", "create"), ("release", "upload"), ("release", "delete"),
          ("workflow", "run"), ("run", "cancel"), ("run", "rerun"), ("run", "delete")}


def load():
    path = Path(os.environ["FAKE_GH_STATE"])
    return json.loads(path.read_text()) if path.exists() else {}


def save(state):
    path = Path(os.environ["FAKE_GH_STATE"])
    tmp = path.with_suffix(".tmp")
    tmp.write_text(json.dumps(state, indent=2, sort_keys=True))
    tmp.replace(path)


def tick(state):
    state["clock"] = state.get("clock", 0) + 1
    return f"2026-11-26T10:{state['clock'] // 60:02d}:{state['clock'] % 60:02d}Z"


def option(args, *names):
    for name in names:
        if name in args:
            return args[args.index(name) + 1]
    return None


def fail(message, code=1):
    print(message, file=sys.stderr)
    sys.exit(code)


def not_found():
    fail("gh: Not Found (HTTP 404)")


def blob_sha(data):
    return hashlib.sha1(b"blob %d\0" % len(data) + data).hexdigest()


def artifact_files(run, template):
    """{name: bytes} of the artifact ci_build.sh stages for this run, from the test's template:
    apk (path of the APK bytes), stamp, cert, ffmpeg_asset, property_keys, package (optional); then
    info_overrides ({"a.b": value} into build-info.json), stamp_file (another stamp than the recorded
    one), apk_extra (bytes appended to the APK after its hash) and omit (file names)."""
    inputs, name = run["inputs"], f"app-full-arm64-v8a-{run['inputs']['variant'].lower()}.apk"
    apk = Path(template["apk"]).read_bytes()
    info = {
        "schema": 1, "commit": inputs["commit"], "request_id": inputs["request_id"],
        "run": {"id": str(run["databaseId"]), "attempt": "1", "repository": "tinof/NuvioTV-Builds"},
        "inputs": {"variant": inputs["variant"], "suffix": inputs["suffix"], "debuggable": inputs["debuggable"] == "true",
                   "version_code": inputs["version_code"], "version_name": inputs["version_name"]},
        "apk": {"name": name, "sha256": hashlib.sha256(apk).hexdigest(), "size": len(apk)},
        "package": template.get("package") or f"com.nuvio.tv.test{inputs['suffix']}",
        "cert_sha256": template["cert"], "ffmpeg_asset": template["ffmpeg_asset"], "rpu_stamp": template["stamp"],
        "native_libraries": None, "property_keys": template.get("property_keys", []),
        "toolchain": {"ndk": "29.0.14206865", "cmake": "3.22.1", "rustc": "rustc 1.93.0", "cargo_ndk": "4.1.2",
                      "java": "openjdk version \"17.0.21\""},
        "seconds": {"rpu": 93, "gradle": 1691, "total": 1801},
    }
    info = json.loads(json.dumps(info))  # the overrides never reach the template's objects
    for dotted, value in template.get("info_overrides", {}).items():
        *parents, last = dotted.split(".")
        node = info
        for key in parents:
            node = node[key]
        node[last] = value
    files = {name: apk + template.get("apk_extra", "").encode(),
             "libnuvio_rpu.so.stamp": json.dumps(template.get("stamp_file", template["stamp"])).encode(),
             "build-info.json": json.dumps(info, indent=2, sort_keys=True).encode()}
    for omitted in template.get("omit", []):
        files.pop(omitted, None)
    return files


def field_values(record, fields):
    return {f: record.get(f) for f in fields.split(",")}


def advance(state, run):
    """One `run view`: a dispatched run completes after run_views of them."""
    run["views"] = run.get("views", 0) + 1
    if run["status"] != "completed" and run["views"] >= run.get("done_after", 2):
        run["status"], run["conclusion"] = "completed", run.get("final_conclusion", "success")
        if run["conclusion"] == "success" and not state.get("no_artifact"):
            run["artifacts"] = [{"id": 15110000000 + run["databaseId"] % 1000000, "name": f"shield-apk-{run['inputs']['request_id']}",
                                 "expired": False, "size_in_bytes": 1000, "workflow_run": {"id": run["databaseId"]}}]
        if state.get("move_head"):
            subprocess.run(["git", "-C", state["move_head"], "commit", "-q", "--allow-empty", "-m", "moved"], check=True)
    elif run["status"] == "queued":
        run["status"] = "in_progress"


def main():
    args = sys.argv[1:]
    stdin = "" if sys.stdin.isatty() else sys.stdin.read()
    method = (option(args, "-X", "--method") or "GET") if args[:1] == ["api"] else None
    is_write = tuple(args[:2]) in WRITES or (method not in (None, "GET"))
    with open(os.environ["FAKE_GH_LOG"], "a") as log:
        log.write(json.dumps({"argv": args, "stdin": stdin, "write": is_write}) + "\n")
    state = load()
    repo_name = state.setdefault("name", "tinof/NuvioTV-Builds")
    repo = state.get("repo")

    if args[:2] == ["repo", "view"]:
        if args[2] != repo_name or repo is None:
            fail(f"GraphQL: Could not resolve to a Repository with the name '{args[2]}'. (repository)")
        view = {"isPrivate": repo["visibility"] == "PRIVATE", "visibility": repo["visibility"], "isEmpty": repo["empty"],
                "defaultBranchRef": {"name": repo["default_branch"]} if not repo["empty"] else None}
        fields = option(args, "--json").split(",")
        print(json.dumps({f: view[f] for f in fields}))
    elif args[:2] == ["repo", "create"]:
        if repo is not None:
            fail("GraphQL: Name already exists on this account (createRepository)")
        if "--private" not in args:
            fail("fake gh: this test creates private repos only")
        state["repo"] = {"visibility": "PRIVATE", "empty": "--add-readme" not in args, "default_branch": "main"}
        state["settings"] = json.loads(json.dumps(DEFAULT_SETTINGS))
        state["contents"] = {"README.md": {"sha": blob_sha(b"# readme\n")}} if "--add-readme" in args else {}
        state.setdefault("secrets", {})
        state.setdefault("releases", {})
        print(f"https://github.com/{args[2]}")
    elif args[:2] == ["secret", "list"]:
        assert option(args, "-R", "--repo") == repo_name, args
        print(json.dumps([{"name": n, "updatedAt": s["updatedAt"]} for n, s in sorted(state["secrets"].items())]))
    elif args[:2] == ["secret", "set"]:
        assert option(args, "-R", "--repo") == repo_name, args
        if "--body" in args or "-b" in args:
            fail("fake gh: a secret value in argv")
        state["secrets"][args[2]] = {"value": stdin, "updatedAt": tick(state)}
    elif args[:2] == ["release", "list"]:
        assert option(args, "-R", "--repo") == repo_name, args
        print(json.dumps([{"tagName": t} for t in sorted(state["releases"], reverse=True)]))
    elif args[:2] == ["release", "create"]:
        assert option(args, "-R", "--repo") == repo_name, args
        tag = args[2]
        if tag in state["releases"]:
            fail("a release with the same tag name already exists")
        if state["repo"]["empty"]:
            fail("HTTP 422: Repository is empty.")
        assets = []
        for path in args[3:]:
            if path.startswith("-"):
                break
            data = Path(path.split("#")[0]).read_bytes()
            assets.append({"name": Path(path).name, "size": len(data), "state": "uploaded",
                           "digest": state.get("digest_override") or f"sha256:{hashlib.sha256(data).hexdigest()}"})
        state["releases"][tag] = {"assets": assets}
    elif args[:2] == ["release", "download"]:
        assert option(args, "-R", "--repo") == repo_name, args
        tag, asset = args[2], option(args, "-p", "--pattern")
        source = state.get("release_files", {}).get(tag, {}).get(asset)
        if not source:
            fail(f"release not found: {tag}")
        target = Path(option(args, "-D", "--dir"))
        target.mkdir(parents=True, exist_ok=True)
        shutil.copy(source, target / asset)
    elif args[:2] == ["workflow", "run"]:
        assert option(args, "-R", "--repo") == repo_name, args
        assert args[2] == "shield-build.yml" and option(args, "--ref", "-r") == "main", args
        if state.get("dispatch_fails"):
            fail("could not create workflow dispatch event: HTTP 422: Unexpected inputs provided")
        inputs = dict(args[i + 1].split("=", 1) for i, a in enumerate(args) if a in ("-f", "--raw-field"))
        runs = state.setdefault("runs", {})
        run_id = 45800000000 + len(runs) + 1
        state_dir = state.get("state_dir")
        runs[str(run_id)] = {
            "databaseId": run_id, "event": "workflow_dispatch", "status": "queued", "conclusion": "",
            "displayTitle": f"Shield {inputs['variant']} {inputs['commit']} {inputs['request_id']}",
            "url": f"https://github.com/{repo_name}/actions/runs/{run_id}", "createdAt": tick(state),
            "inputs": inputs, "done_after": state.get("run_views", 2),
            "final_conclusion": state.get("run_conclusion", "success"), "artifacts": [],
            "state_file_existed": bool(state_dir) and (Path(state_dir) / f"{inputs['request_id']}.json").exists(),
        }
        print("\u2713 Created workflow_dispatch event for shield-build.yml at main", file=sys.stderr)
        shown = {"printed": run_id, "other": 45700000000 + 1}.get(state.get("dispatch_url", "printed"))
        if shown:
            print(f"https://github.com/{repo_name}/actions/runs/{shown}")
    elif args[:2] == ["run", "view"]:
        assert option(args, "-R", "--repo") == repo_name, args
        run = state.get("runs", {}).get(args[2])
        if run is None:
            fail(f"HTTP 404: Not Found (https://api.github.com/repos/{repo_name}/actions/runs/{args[2]})")
        advance(state, run)
        print(json.dumps(field_values(run, option(args, "--json"))))
    elif args[:2] == ["run", "list"]:
        assert option(args, "-R", "--repo") == repo_name, args
        runs = sorted(state.get("runs", {}).values(), key=lambda r: r["databaseId"], reverse=True)
        print(json.dumps([field_values(r, option(args, "--json")) for r in runs][:int(option(args, "-L") or 20)]))
    elif args[:2] == ["run", "download"]:
        assert option(args, "-R", "--repo") == repo_name, args
        run, name = state.get("runs", {}).get(args[2]), option(args, "-n", "--name")
        live = [a for a in (run or {}).get("artifacts", []) if a["name"] == name and not a.get("deleted") and not a["expired"]]
        if not live:
            fail("no valid artifacts found to download")
        target = Path(option(args, "-D", "--dir"))
        target.mkdir(parents=True, exist_ok=True)
        for file_name, data in artifact_files(run, state["artifact"]).items():
            (target / file_name).write_bytes(data)
    elif args[:1] == ["api"]:
        path = args[3].split("?")[0]
        prefix = f"repos/{repo_name}"
        assert path.startswith(prefix), path
        path = path[len(prefix):]
        body = json.loads(stdin) if stdin else None
        if repo is None:
            not_found()
        if path in state["settings"]:
            if method == "GET":
                if path.endswith("/selected-actions") and state["settings"]["/actions/permissions"]["allowed_actions"] != "selected":
                    fail("gh: allowed_actions is not selected (HTTP 409)")
                print(json.dumps(state["settings"][path]))
            elif method == "PUT":
                state["settings"][path].update(body)
        elif path.startswith("/contents/"):
            name = path[len("/contents/"):]
            current = state["contents"].get(name)
            if method == "GET":
                if current is None:
                    not_found()
                print(json.dumps({"path": name, "sha": current["sha"], "type": "file"}))
            elif method == "PUT":
                if current is not None and body.get("sha") != current["sha"]:
                    fail("gh: sha does not match (HTTP 409)")
                if current is None and "sha" in body:
                    fail("gh: Invalid request. sha wasn't supplied (HTTP 422)")
                data = base64.b64decode(body["content"])
                state["contents"][name] = {"sha": blob_sha(data), "content": body["content"],
                                           "branch": body.get("branch"), "message": body["message"]}
                state["repo"]["empty"] = False
                print(json.dumps({"content": {"sha": blob_sha(data)}}))
        elif path.startswith("/actions/runs/") and path.endswith("/artifacts"):
            run = state.get("runs", {}).get(path.split("/")[3])
            if run is None:
                not_found()
            live = [a for a in run.get("artifacts", []) if not a.get("deleted")]
            print(json.dumps({"total_count": len(live), "artifacts": live}))
        elif path.startswith("/actions/artifacts/") and method == "DELETE":
            if state.get("artifact_delete_fails"):
                fail("gh: Must have admin rights to Repository. (HTTP 403)")
            hits = [a for r in state.get("runs", {}).values() for a in r.get("artifacts", [])
                    if str(a["id"]) == path.split("/")[3] and not a.get("deleted")]
            if not hits:
                not_found()
            hits[0]["deleted"] = True
        elif path.startswith("/releases/tags/"):
            release = state["releases"].get(path[len("/releases/tags/"):])
            if release is None:
                not_found()
            print(json.dumps({"tag_name": path[len("/releases/tags/"):], "assets": release["assets"]}))
        else:
            fail(f"fake gh: no endpoint {method} {path}")
    else:
        fail(f"fake gh: no command {' '.join(args[:2])}")
    save(state)


if __name__ == "__main__":
    main()
