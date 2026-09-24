#!/usr/bin/env python3
"""Publishing helper for the Fishing Rod Fix mod (CurseForge + Modrinth).

Every command that writes is a dry run unless --send is given: it prints the exact metadata it
would send (never a token) and exits. Tokens come from the Windows user environment
(HKCU\\Environment) or the process environment: CURSEFORGE_TOKEN, MODRINTH_TOKEN.

  published                      list what is already on both platforms
  jar-info JAR                   the jar's fabric.mod.json: version, minecraft range, breaks
  game-versions RANGE            expand a fabric.mod.json minecraft range to release versions,
                                 with the CurseForge ids they map to
  upload-cf  --jar --changelog [--display-name] [--incompatible-slug S] [--send]
  upload-mr  --jar --changelog --name --version-number [--incompatible-id ID] [--send]
  mr-project get [--out FILE]    the Modrinth summary and body (markdown)
  mr-project set [--body FILE] [--summary TEXT] [--send]

Standard library only, so it runs on any Python 3.8+.
"""
import argparse
import json
import os
import re
import sys
import urllib.error
import urllib.request
import uuid
import zipfile

CF_PROJECT_ID = 1018847
MR_PROJECT_ID = "x9ISUf1U"
CF_API = "https://minecraft.curseforge.com/api"
CF_SITE_API = "https://www.curseforge.com/api/v1"
MR_API = "https://api.modrinth.com/v2"
USER_AGENT = "andrewchik0/fishing-rod-fix publish-skill (github.com/andrewchik0/fishing-rod-fix)"

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")


def token(name):
    value = None
    if sys.platform == "win32":
        try:
            import winreg
            with winreg.OpenKey(winreg.HKEY_CURRENT_USER, "Environment") as key:
                value = winreg.QueryValueEx(key, name)[0]
        except OSError:
            pass
    value = value or os.environ.get(name)
    if not value:
        sys.exit(f"{name} is not set (HKCU\\Environment or the process environment)")
    return value


def request(method, url, headers=None, body=None, content_type=None):
    req = urllib.request.Request(url, data=body, method=method)
    req.add_header("User-Agent", USER_AGENT)
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    if content_type:
        req.add_header("Content-Type", content_type)
    try:
        with urllib.request.urlopen(req, timeout=120) as resp:
            data = resp.read()
            return resp.status, (json.loads(data) if data.strip() else None)
    except urllib.error.HTTPError as e:
        text = e.read().decode("utf-8", "replace")
        sys.exit(f"HTTP {e.code} {method} {url}\n{text}")


def multipart(fields, file_field, file_path):
    boundary = uuid.uuid4().hex
    parts = []
    for name, value in fields.items():
        parts.append(
            f'--{boundary}\r\nContent-Disposition: form-data; name="{name}"\r\n'
            f"Content-Type: application/json\r\n\r\n{value}\r\n".encode("utf-8"))
    with open(file_path, "rb") as f:
        payload = f.read()
    filename = os.path.basename(file_path)
    parts.append(
        f'--{boundary}\r\nContent-Disposition: form-data; name="{file_field}"; '
        f'filename="{filename}"\r\nContent-Type: application/java-archive\r\n\r\n'.encode("utf-8")
        + payload + b"\r\n")
    parts.append(f"--{boundary}--\r\n".encode("utf-8"))
    return b"".join(parts), f"multipart/form-data; boundary={boundary}"


# ---- versions -------------------------------------------------------------------------------

def vtuple(v):
    return tuple(int(x) for x in v.split("."))


def mr_release_versions():
    _, tags = request("GET", f"{MR_API}/tag/game_version")
    return sorted((t["version"] for t in tags if t["version_type"] == "release"
                   and re.fullmatch(r"\d+(\.\d+)+", t["version"])), key=vtuple)


def expand_range(expr, versions):
    """fabric.mod.json 'minecraft' value (space-separated comparators, or one exact version)."""
    tests = []
    for term in expr.split():
        m = re.fullmatch(r"(>=|<=|>|<|=)?(\d+(?:\.\d+)*)", term)
        if not m:
            sys.exit(f"unsupported minecraft range term: {term!r}")
        op, ver = m.group(1) or "=", vtuple(m.group(2))
        tests.append((op, ver))

    def ok(v):
        t = vtuple(v)
        for op, ver in tests:
            if not {"=": t == ver, ">=": t >= ver, "<=": t <= ver, ">": t > ver, "<": t < ver}[op]:
                return False
        return True
    matched = [v for v in versions if ok(v)]
    if not matched:
        sys.exit(f"no release version matches {expr!r}")
    return matched


def cf_version_ids(mc_versions):
    headers = {"X-Api-Token": token("CURSEFORGE_TOKEN")}
    _, types = request("GET", f"{CF_API}/game/version-types", headers)
    _, versions = request("GET", f"{CF_API}/game/versions", headers)
    mc_types = {t["id"] for t in types if re.fullmatch(r"minecraft-\d+(-\d+)*", t["slug"])}
    by_type_slug = {t["slug"]: t["id"] for t in types}

    def find(name, type_ids):
        hits = [v["id"] for v in versions if v["name"] == name and v["gameVersionTypeID"] in type_ids]
        if len(hits) != 1:
            sys.exit(f"CurseForge game version {name!r}: {len(hits)} matches, expected 1")
        return hits[0]
    ids = {v: find(v, mc_types) for v in mc_versions}
    ids["Fabric"] = find("Fabric", {by_type_slug["modloader"]})
    ids["Client"] = find("Client", {by_type_slug["environment"]})
    return ids


def jar_info(jar):
    with zipfile.ZipFile(jar) as z:
        meta = json.loads(z.read("fabric.mod.json"))
    return {
        "version": meta.get("version"),
        "minecraft": meta.get("depends", {}).get("minecraft"),
        "fabricloader": meta.get("depends", {}).get("fabricloader"),
        "java": meta.get("depends", {}).get("java"),
        "breaks": meta.get("breaks", {}),
        "description": meta.get("description"),
    }


# ---- commands -------------------------------------------------------------------------------

def cmd_published(_):
    _, mr = request("GET", f"{MR_API}/project/{MR_PROJECT_ID}/version")
    print("Modrinth (newest first):")
    for v in mr:
        files = ", ".join(f["filename"] for f in v["files"])
        print(f"  {v['date_published'][:10]}  {v['version_number']:<22} {','.join(v['game_versions'])}  [{files}]")
    print("CurseForge (newest first):")
    try:
        _, cf = request("GET", f"{CF_SITE_API}/mods/{CF_PROJECT_ID}/files?pageIndex=0&pageSize=50")
        for f in cf["data"]:
            gv = ",".join(x for x in f["gameVersions"] if x not in ("Client", "Server", "Fabric"))
            print(f"  {f['dateCreated'][:10]}  {f['fileName']:<40} {gv}  (id {f['id']})")
    except SystemExit as e:
        print(f"  (the CurseForge site API refused: {e}; check the files page by hand)")


def cmd_jar_info(a):
    print(json.dumps(jar_info(a.jar), indent=2))


def cmd_game_versions(a):
    mc = expand_range(a.range, mr_release_versions())
    print("release versions:", ", ".join(mc))
    if not a.no_cf:
        print("CurseForge ids:", json.dumps(cf_version_ids(mc)))


def read_text(path):
    with open(path, encoding="utf-8") as f:
        return f.read().strip()


def cmd_upload_cf(a):
    info = jar_info(a.jar)
    mc = expand_range(info["minecraft"], mr_release_versions())
    ids = cf_version_ids(mc)
    metadata = {
        "changelog": read_text(a.changelog),
        "changelogType": "markdown",
        "displayName": a.display_name or os.path.basename(a.jar),
        "gameVersions": sorted(ids.values()),
        "releaseType": "release",
    }
    if a.incompatible_slug:
        metadata["relations"] = {"projects": [{"slug": a.incompatible_slug, "type": "incompatible"}]}
    print(f"CurseForge project {CF_PROJECT_ID} <- {os.path.basename(a.jar)} "
          f"({os.path.getsize(a.jar)} bytes); game versions {', '.join(mc)} + Fabric + Client")
    print(json.dumps(metadata, indent=2, ensure_ascii=False))
    if not a.send:
        print("DRY RUN: nothing sent (add --send)")
        return
    body, ctype = multipart({"metadata": json.dumps(metadata)}, "file", a.jar)
    _, resp = request("POST", f"{CF_API}/projects/{CF_PROJECT_ID}/upload-file",
                      {"X-Api-Token": token("CURSEFORGE_TOKEN")}, body, ctype)
    print(f"UPLOADED CurseForge file id {resp['id']}: "
          f"https://authors.curseforge.com/#/projects/{CF_PROJECT_ID}/files/{resp['id']}")


def cmd_upload_mr(a):
    info = jar_info(a.jar)
    mc = expand_range(info["minecraft"], mr_release_versions())
    data = {
        "project_id": MR_PROJECT_ID,
        "name": a.name,
        "version_number": a.version_number,
        "changelog": read_text(a.changelog),
        "game_versions": mc,
        "version_type": "release",
        "loaders": ["fabric"],
        "featured": False,
        "status": "listed",
        "dependencies": ([{"project_id": a.incompatible_id, "dependency_type": "incompatible"}]
                         if a.incompatible_id else []),
        "file_parts": ["file"],
        "primary_file": "file",
    }
    print(f"Modrinth project {MR_PROJECT_ID} <- {os.path.basename(a.jar)} ({os.path.getsize(a.jar)} bytes)")
    print(json.dumps(data, indent=2, ensure_ascii=False))
    if not a.send:
        print("DRY RUN: nothing sent (add --send)")
        return
    body, ctype = multipart({"data": json.dumps(data)}, "file", a.jar)
    _, resp = request("POST", f"{MR_API}/version",
                      {"Authorization": token("MODRINTH_TOKEN")}, body, ctype)
    print(f"UPLOADED Modrinth version {resp['id']}: "
          f"https://modrinth.com/mod/fishing-rod-fix/version/{resp['id']}")


def cmd_mr_project(a):
    if a.action == "get":
        _, p = request("GET", f"{MR_API}/project/{MR_PROJECT_ID}")
        text = f"SUMMARY: {p['description']}\n\n{p['body']}"
        if a.out:
            with open(a.out, "w", encoding="utf-8") as f:
                f.write(p["body"])
            print(f"SUMMARY: {p['description']}\nbody written to {a.out}")
        else:
            print(text)
        return
    patch = {}
    if a.body:
        patch["body"] = read_text(a.body)
    if a.summary:
        patch["description"] = a.summary
    if not patch:
        sys.exit("nothing to set: give --body and/or --summary")
    print(json.dumps(patch, indent=2, ensure_ascii=False))
    if not a.send:
        print("DRY RUN: nothing sent (add --send)")
        return
    request("PATCH", f"{MR_API}/project/{MR_PROJECT_ID}",
            {"Authorization": token("MODRINTH_TOKEN")}, json.dumps(patch).encode("utf-8"),
            "application/json")
    print("UPDATED Modrinth project page: https://modrinth.com/mod/fishing-rod-fix")


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="cmd", required=True)
    sub.add_parser("published").set_defaults(fn=cmd_published)
    s = sub.add_parser("jar-info"); s.add_argument("jar"); s.set_defaults(fn=cmd_jar_info)
    s = sub.add_parser("game-versions"); s.add_argument("range"); s.add_argument("--no-cf", action="store_true")
    s.set_defaults(fn=cmd_game_versions)
    s = sub.add_parser("upload-cf")
    s.add_argument("--jar", required=True); s.add_argument("--changelog", required=True)
    s.add_argument("--display-name"); s.add_argument("--incompatible-slug")
    s.add_argument("--send", action="store_true"); s.set_defaults(fn=cmd_upload_cf)
    s = sub.add_parser("upload-mr")
    s.add_argument("--jar", required=True); s.add_argument("--changelog", required=True)
    s.add_argument("--name", required=True); s.add_argument("--version-number", required=True)
    s.add_argument("--incompatible-id"); s.add_argument("--send", action="store_true")
    s.set_defaults(fn=cmd_upload_mr)
    s = sub.add_parser("mr-project"); s.add_argument("action", choices=["get", "set"])
    s.add_argument("--out"); s.add_argument("--body"); s.add_argument("--summary")
    s.add_argument("--send", action="store_true"); s.set_defaults(fn=cmd_mr_project)
    a = p.parse_args()
    a.fn(a)


if __name__ == "__main__":
    main()
