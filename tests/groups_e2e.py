#!/usr/bin/env python3
"""
End-to-end test for registration, groups, invites and the add/remove QR
codes — API and web UI. Standard library only.

    php -S 127.0.0.1:8765 router.php &      # with a fresh data/ dir and one user:
    php -r 'require "includes/helpers.php"; require "includes/db.php"; require "includes/auth.php";
            register_user(get_db(), "tiago", "secret123");'
    python3 tests/groups_e2e.py              # BASE=http://... to point elsewhere

Exits non-zero if any check fails.
"""
import json, os, re, sys, urllib.request, urllib.parse, http.cookiejar

BASE = os.environ.get("BASE", "http://127.0.0.1:8765")
fails = 0
def check(desc, got, want):
    global fails
    ok = got == want
    print(("  ok   " if ok else "  FAIL ") + desc + ("" if ok else f": got {got!r}, want {want!r}"))
    if not ok: fails += 1

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *a, **k): return None

def api(method, path, token=None, body=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method, headers={"Content-Type": "application/json"})
    if token: req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req) as r: return r.status, json.loads(r.read() or b"{}")
    except urllib.error.HTTPError as e:
        raw = e.read()
        try: return e.code, json.loads(raw)
        except Exception: return e.code, raw.decode(errors="replace")[:300]

class Browser:
    def __init__(self):
        self.jar = http.cookiejar.CookieJar()
        self.op = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.jar), NoRedirect)
    def go(self, method, path, form=None):
        data = urllib.parse.urlencode(form).encode() if form is not None else None
        req = urllib.request.Request(BASE + path, data=data, method=method)
        try:
            with self.op.open(req) as r: return r.status, r.headers.get("Location"), r.read().decode(errors="replace")
        except urllib.error.HTTPError as e:
            return e.code, e.headers.get("Location"), e.read().decode(errors="replace")

print("registration (API)")
s, b = api("POST", "/api/auth/register", body={"username": "ab", "password": "secret123"})
check("short username is 400", s, 400)
s, b = api("POST", "/api/auth/register", body={"username": "anna", "password": "short"})
check("short password is 400", s, 400)
s, b = api("POST", "/api/auth/register", body={"username": "TIAGO", "password": "secret123"})
check("case-insensitive taken username is 409", s, 409)
s, anna = api("POST", "/api/auth/register", body={"username": "anna", "password": "secret123", "device_name": "Pixel"})
check("register is 201", s, 201)
A = anna["token"]
check("register returns personal group", anna["group"]["name"], "anna's things")
check("owner of personal group", anna["group"]["role"], "owner")
check("invite key formatted", bool(re.fullmatch(r"[A-Z2-9]{4}-[A-Z2-9]{4}", anna["group"]["invite"]["key"])), True)
s, me = api("GET", "/api/me", A)
check("me has default_group_id", me["default_group_id"], anna["group"]["id"])

print("groups (API)")
s, g = api("POST", "/api/groups", A, {"name": "  Family   home "})
check("create group 201", s, 201)
fam = g["group"]
check("group name cleaned", fam["name"], "Family home")
s, b = api("POST", "/api/auth/login", body={"username": "tiago", "password": "secret123"})
T = b["token"]
s, b = api("GET", f"/api/groups/{fam['id']}", T)
check("non-member gets 404", s, 404)
s, b = api("POST", "/api/groups/join", T, {"name": "family HOME", "key": "XXXX-XXXX"})
check("wrong key is 404", s, 404)
key = fam["invite"]["key"]
s, b = api("POST", "/api/groups/join", T, {"name": "family HOME", "key": key.lower().replace("-", " ")})
check("join by name+key (case/format-insensitive)", (s, b["group"]["id"]), (200, fam["id"]))
check("joiner can edit", b["group"]["permission"], "edit")
check("joiner is member", b["group"]["role"], "member")
s, b = api("POST", "/api/groups/join", T, {"name": "Family home", "key": key})
check("joining twice is fine", s, 200)
s, b = api("PUT", f"/api/groups/{fam['id']}", T, {"name": "Hijack"})
check("member cannot rename (403)", s, 403)
s, b = api("DELETE", f"/api/groups/{fam['id']}", T)
check("member cannot delete (403)", s, 403)
s, b = api("GET", "/api/groups", T)
check("tiago now in 2 groups", len(b["groups"]), 2)

print("sync per group")
place = {"uuid": "p-0000000001", "name": "Kitchen", "share_token": "aaaaaaaabbbbbbbbccccccccdddddddd", "updated_at": 1790000000000}
box = {"uuid": "b-0000000001", "place_uuid": "p-0000000001", "name": "Drawer", "share_token": "11111111222222223333333344444444", "updated_at": 1790000000000}
item = {"uuid": "i-0000000001", "box_uuid": "b-0000000001", "name": "Spoon", "quantity": 3, "updated_at": 1790000000000}
s, b = api("POST", "/api/sync", A, {"group_id": fam["id"], "since": 0, "places": [place], "boxes": [box], "items": [item]})
check("anna syncs into Family", s, 200)
check("response names group", b["group"]["name"], "Family home")
check("place token kept", b["changes"]["places"][0]["share_token"], place["share_token"])
s, b = api("POST", "/api/sync", T, {"group_id": fam["id"], "since": 0})
check("tiago pulls Family's place", [p["name"] for p in b["changes"]["places"]], ["Kitchen"])
check("tiago pulls item", [i["name"] for i in b["changes"]["items"]], ["Spoon"])
s, b = api("POST", "/api/sync", T, {"since": 0})
check("tiago default group has nothing of Family", [p["name"] for p in b["changes"]["places"]], [])
s, b = api("POST", "/api/sync", T, {"group_id": 99999, "since": 0})
check("sync into foreign group is 403", s, 403)
s, b = api("POST", "/api/sync", T, {"group_id": fam["id"], "since": 0, "places": [dict(place, uuid="p-0000000002", name="Shed")]})
check("member edit pushes", any(p["name"] == "Shed" for p in b["changes"]["places"]), True)
s, b = api("GET", f"/api/places?group_id={fam['id']}", T)
check("REST ?group_id scopes places", sorted(p["name"] for p in b["places"]), ["Kitchen", "Shed"])
check("place_out has add_url", b["places"][0]["add_url"].startswith("/add/"), True)

print("owner-only and leave")
s, b = api("PUT", f"/api/groups/{fam['id']}/members/{me['user']['id']}", A, {"permission": "view"})
check("owner can't change self", s, 400)
s, gl = api("GET", f"/api/groups/{fam['id']}", A)
tid = [m for m in gl["members"] if m["username"] == "tiago"][0]["id"]
s, b = api("PUT", f"/api/groups/{fam['id']}/members/{tid}", A, {"permission": "view"})
check("owner sets tiago view-only", s, 200)
s, b = api("POST", "/api/sync", T, {"group_id": fam["id"], "since": 0, "places": [dict(place, name="Renamed", updated_at=1795000000000)]})
check("view-only push skipped as read only", [x["reason"] for x in b["skipped"]], ["read only"])
check("view-only push did not rename", sorted(p["name"] for p in b["changes"]["places"]), ["Kitchen", "Shed"])
s, b = api("GET", f"/api/groups/{fam['id']}", T)
check("view-only member sees no invite", b["group"]["invite"], None)
old_token = fam["invite"]["token"]
s, b = api("POST", f"/api/groups/{fam['id']}/invite/reset", A)
check("reset invite changes token", b["group"]["invite"]["token"] != old_token, True)
s, b = api("POST", "/api/groups/join", A, {"token": old_token})
check("old invite token dead", s, 404)
s, b = api("POST", f"/api/groups/{fam['id']}/leave", A)
check("owner can't leave", s, 400)
s, b = api("POST", f"/api/groups/{fam['id']}/leave", T)
check("member leaves", s, 200)
s, b = api("POST", "/api/sync", T, {"group_id": fam["id"], "since": 0})
check("after leaving, sync is 403", s, 403)

print("web: register, invite link, groups, add/remove codes")
w = Browser()
s, loc, html = w.go("GET", "/login")
check("login page links to register", "/register" in html, True)
inv = api("GET", f"/api/groups/{fam['id']}", A)[1]["group"]["invite"]
s, loc, _ = w.go("GET", f"/join/{inv['token']}")
check("logged-out invite goes to register", loc, "/register?next=" + urllib.parse.quote(f"/join/{inv['token']}", safe=""))
s, loc, _ = w.go("POST", "/register", {"username": "webby", "password": "secret123", "password_confirm": "secret123", "next": f"/join/{inv['token']}"})
check("register redirects to invite", (s, loc), (303, f"/join/{inv['token']}"))
s, loc, html = w.go("GET", f"/join/{inv['token']}")
check("join page asks", "Join group" in html and "Family home" in html, True)
s, loc, _ = w.go("POST", f"/join/{inv['token']}")
check("join redirects home", loc, "/")
s, loc, html = w.go("GET", "/")
check("home now shows Family places", "Kitchen" in html and "Shed" in html, True)
check("group switcher shows other group", "webby&#039;s things" in html, True)
s, loc, html = w.go("GET", "/place/kitchen/drawer")
check("box page shows add/remove codes", "/add/11111111222222223333333344444444" in html and "/remove/11111111222222223333333344444444" in html, True)
s, loc, _ = w.go("GET", "/add/11111111222222223333333344444444")
check("add code redirects to box add mode", loc, "/place/kitchen/drawer?mode=add#add-item")
s, loc, html = w.go("GET", "/place/kitchen/drawer?mode=add")
check("add mode opens form with autofocus", 'id="add-item"' in html and "autofocus" in html and 'name="mode" value="add"' in html, True)
s, loc, _ = w.go("POST", "/place/kitchen/drawer", {"action": "create_item", "name": "Fork", "quantity": "1", "mode": "add"})
check("adding keeps add mode", loc, "/place/kitchen/drawer?mode=add#add-item")
s, loc, _ = w.go("GET", "/remove/aaaaaaaabbbbbbbbccccccccdddddddd")
check("place remove code redirects", loc, "/place/kitchen?mode=remove#items")
s, loc, html = w.go("GET", "/place/kitchen/drawer?mode=remove")
check("remove mode shows −1 buttons", "take_one" in html and "Done" in html, True)
ids = re.findall(r'value="take_one">\s*<input type="hidden" name="id" value="(\d+)"', html)
names = re.findall(r'aria-label="Take one (.*?) out"', html)
sid = dict(zip(names, ids))["Spoon"]
w.go("POST", "/place/kitchen/drawer", {"action": "take_one", "id": sid, "mode": "remove"})
s, loc, html = w.go("GET", "/place/kitchen/drawer")
check("take one: 3 -> 2", "×2" in html, True)
s, loc, _ = w.go("POST", "/place/kitchen", {"action": "delete_item", "id": sid})
s, loc, html = w.go("GET", "/place/kitchen/drawer")
check("delete scoped to container (spoon survives delete from place page)", "Spoon" in html, True)
s, loc, svg = w.go("GET", "/add/11111111222222223333333344444444/label.svg")
check("add label svg", s == 200 and "<svg" in svg and "Scan to add" in svg, True)
s, loc, png = w.go("GET", "/remove/aaaaaaaabbbbbbbbccccccccdddddddd/qr.png")
check("remove qr png", s, 200)
w2 = Browser()
w2.go("POST", "/login", {"username": "tiago", "password": "secret123"})
s, loc, html = w2.go("GET", "/add/11111111222222223333333344444444")
check("non-member gets 404 for add code", s, 404)
s, loc, html = w.go("GET", f"/groups/{fam['id']}")
check("group page shows members", "anna" in html and "webby" in html, True)
check("member sees invite key", inv["key"] in html, True)
check("member sees leave, not delete", "Leave group" in html and "delete_group" not in html, True)
s, loc, _ = w.go("POST", "/groups", {"action": "join_group", "name": "nope", "key": "AAAA-AAAA"})
check("bad name+key flashes error", loc, "/groups")
s, loc, _ = w.go("POST", "/groups", {"action": "create_group", "name": "Workshop"})
check("create group redirects to it", bool(re.fullmatch(r"/groups/\d+", loc or "")), True)
s, loc, html = w.go("GET", "/")
check("new group is active and empty", "No places yet" in html, True)
s, loc, svg = w.go("GET", f"/groups/{fam['id']}/invite.svg")
check("invite QR svg", s == 200 and "<svg" in svg, True)

print("FAILED: %d" % fails if fails else "ALL OK")
sys.exit(1 if fails else 0)
