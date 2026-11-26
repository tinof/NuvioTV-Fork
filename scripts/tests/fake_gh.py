"""A fake `gh` with one GitHub repository behind it, for the shield_cloud_setup.py tests.

The state is JSON at $FAKE_GH_STATE and persists between calls, so a second run sees the first run's
writes. Every call is appended to $FAKE_GH_LOG as {"argv": [...], "stdin": "...", "write": bool}.
Hooks in the state: repo.visibility (PUBLIC makes the repo public), repo.default_branch.

Commands: repo view/create; secret list/set (the value only from stdin); release list/create;
api GET/PUT on repos/<repo>/actions/permissions..., contents/<path> and releases/tags/<tag>.
Responses follow the GitHub REST and gh shapes the setup script reads; a missing resource is
`gh: Not Found (HTTP 404)` with exit 1, as gh prints it.
"""

import base64
import hashlib
import json
import os
import sys
from pathlib import Path

DEFAULT_SETTINGS = {
    "/actions/permissions": {"enabled": True, "allowed_actions": "all"},
    "/actions/permissions/selected-actions": {"github_owned_allowed": False, "verified_allowed": False,
                                              "patterns_allowed": []},
    "/actions/permissions/workflow": {"default_workflow_permissions": "write", "can_approve_pull_request_reviews": False},
    "/actions/permissions/artifact-and-log-retention": {"days": 90, "maximum_allowed_days": 90},
}
WRITES = {("repo", "create"), ("secret", "set"), ("release", "create"), ("release", "upload"), ("release", "delete")}


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
    elif args[:1] == ["api"]:
        path = args[3]
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
