"""Generates Printroom.postman_collection.json (Postman v2.1).

Edit this file rather than the JSON, then run:  python3 postman/generate_collection.py
"""
import json
import os

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "Printroom.postman_collection.json")


def script(lines):
    return [l for l in "\n".join(lines).strip("\n").split("\n")]


def req(name, method, path, body=None, auth="alice", tests=(), pre=(), query=None, raw_url=None,
        headers=None, file_body=None, description=None):
    header = []
    if body is not None:
        header.append({"key": "Content-Type", "value": "application/json"})
    for k, v in (headers or {}).items():
        header.append({"key": k, "value": v})
    item = {
        "name": name,
        "request": {
            "method": method,
            "header": header,
            "url": raw_url_obj(raw_url) if raw_url else url_obj(path, query),
        },
        "event": [],
    }
    if description:
        item["request"]["description"] = description
    if auth is None:
        item["request"]["auth"] = {"type": "noauth"}
    else:
        item["request"]["auth"] = {"type": "bearer", "bearer": [
            {"key": "token", "value": "{{" + auth + "Token}}", "type": "string"}]}
    if body is not None:
        item["request"]["body"] = {"mode": "raw", "raw": json.dumps(body, indent=2),
                                   "options": {"raw": {"language": "json"}}}
    if file_body:
        item["request"]["body"] = {"mode": "file", "file": {"src": file_body}}
    if pre:
        item["event"].append({"listen": "prerequest", "script": {"type": "text/javascript", "exec": script(pre)}})
    if tests:
        item["event"].append({"listen": "test", "script": {"type": "text/javascript", "exec": script(tests)}})
    return item


def url_obj(path, query=None):
    raw = "{{baseUrl}}" + path
    q = []
    if query:
        q = [{"key": k, "value": v} for k, v in query.items()]
        raw += "?" + "&".join(f"{k}={v}" for k, v in query.items())
    return {"raw": raw, "host": ["{{baseUrl}}"], "path": [p for p in path.strip("/").split("/")], "query": q}


def raw_url_obj(raw):
    # Newman needs the string form for URLs that are a single variable (e.g. a presigned URL).
    return raw


def status(code, extra=""):
    return f'pm.test("Status is {code}", () => pm.response.to.have.status({code}));{extra}'


def error_code(code):
    return f'pm.test("Error code is {code}", () => pm.expect(pm.response.json().code).to.eql("{code}"));'


def register(who, display):
    return req(f"Register {who}", "POST", "/api/auth/register", auth=None,
               pre=[f'pm.collectionVariables.set("{who}Username", "{who}_" + Date.now().toString(36) + Math.floor(Math.random()*1e4));'],
               body={"username": "{{" + who + "Username}}", "email": "{{" + who + "Username}}@example.com",
                     "password": "{{password}}", "displayName": display},
               tests=[status(201),
                      "const b = pm.response.json();",
                      'pm.test("Returns a JWT and the user", () => {',
                      '  pm.expect(b.token.split(".")).to.have.length(3);',
                      '  pm.expect(b.expiresIn).to.be.above(0);',
                      f'  pm.expect(b.user.username).to.eql(pm.collectionVariables.get("{who}Username"));',
                      '  pm.expect(b.user).to.not.have.property("passwordHash");',
                      "});",
                      f'pm.collectionVariables.set("{who}Token", b.token);',
                      f'pm.collectionVariables.set("{who}Id", b.user.userId);'])


# Polls the current request until `done(json)` is true (max attempts), for async pipeline stages.
def poll(var, cond, max_attempts=20):
    return [
        f'const attempts = Number(pm.collectionVariables.get("{var}") || 0);',
        f"const done = (function(json) {{ return {cond}; }})(pm.response.json());",
        f'if (!done && attempts < {max_attempts}) {{',
        f'  pm.collectionVariables.set("{var}", attempts + 1);',
        '  pm.execution.setNextRequest(pm.info.requestName);',
        '  return;',
        '}',
        f'pm.collectionVariables.set("{var}", 0);',
    ]


DELAY = ["// Give the async workers a moment between polls.", "setTimeout(() => {}, 750);"]

folders = []

folders.append({"name": "00 Health", "item": [
    req("API health", "GET", "/actuator/health", auth=None,
        tests=[status(200), 'pm.test("UP", () => pm.expect(pm.response.json().status).to.eql("UP"));']),
    req("OpenAPI spec is published", "GET", "/v3/api-docs", auth=None,
        tests=[status(200), 'pm.test("Documents the feed endpoint", () => pm.expect(pm.response.json().paths).to.have.property("/api/feed"));']),
]})

folders.append({"name": "01 Auth", "item": [
    register("alice", "Alice Liddell"),
    register("bob", "Bob Ross"),
    register("carol", "Carol Celeb"),
    register("dave", "Dave"),
    req("Register duplicate username -> 409", "POST", "/api/auth/register", auth=None,
        body={"username": "{{bobUsername}}", "email": "dup@example.com", "password": "{{password}}"},
        tests=[status(409), error_code("USERNAME_TAKEN")]),
    req("Register invalid -> 400 with field errors", "POST", "/api/auth/register", auth=None,
        body={"username": "Not Valid!", "email": "nope", "password": "short"},
        tests=[status(400), error_code("VALIDATION_FAILED"),
               'pm.test("Reports each bad field", () => pm.expect(pm.response.json().fieldErrors).to.have.all.keys("username", "email", "password"));']),
    req("Login (case-insensitive username)", "POST", "/api/auth/login", auth=None,
        body={"username": "{{aliceUpper}}", "password": "{{password}}"},
        pre=['pm.variables.set("aliceUpper", pm.collectionVariables.get("aliceUsername").toUpperCase());'],
        tests=[status(200),
               'pm.test("Same user id", () => pm.expect(pm.response.json().user.userId).to.eql(pm.collectionVariables.get("aliceId")));',
               'pm.collectionVariables.set("aliceToken", pm.response.json().token);']),
    req("Login wrong password -> 401", "POST", "/api/auth/login", auth=None,
        body={"username": "{{aliceUsername}}", "password": "wrong-password"},
        tests=[status(401), 'pm.test("Generic message (no user enumeration)", () => pm.expect(pm.response.json().message).to.eql("Invalid username or password"));']),
    req("Protected endpoint without token -> 401", "GET", "/api/feed", auth=None,
        tests=[status(401), error_code("UNAUTHORIZED")]),
    req("Protected endpoint with forged token -> 401", "GET", "/api/feed", auth=None,
        headers={"Authorization": "Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ4In0.bad"},
        tests=[status(401)]),
]})

folders.append({"name": "02 Users", "item": [
    req("Get me", "GET", "/api/users/me",
        tests=[status(200), "const b = pm.response.json();",
               'pm.test("Counters start at zero", () => { pm.expect(b.followerCount).to.eql(0); pm.expect(b.followingCount).to.eql(0); pm.expect(b.postCount).to.eql(0); });']),
    req("Update my profile", "PATCH", "/api/users/me", body={"displayName": "Alice L.", "bio": "Down the rabbit hole"},
        tests=[status(200), 'pm.test("Profile updated", () => { const b = pm.response.json(); pm.expect(b.displayName).to.eql("Alice L."); pm.expect(b.bio).to.eql("Down the rabbit hole"); });']),
    req("Get profile by username", "GET", "/api/users/by-username/{{bobUsername}}",
        tests=[status(200), 'pm.test("Not following yet", () => pm.expect(pm.response.json().isFollowing).to.eql(false));']),
    req("Get unknown profile -> 404", "GET", "/api/users/by-username/nobody_{{$timestamp}}",
        tests=[status(404), error_code("NOT_FOUND")]),
    req("Search by username prefix", "GET", "/api/users/search", query={"q": "{{bobUsername}}"},
        tests=[status(200), 'pm.test("Finds Bob", () => pm.expect(pm.response.json().map(u => u.userId)).to.include(pm.collectionVariables.get("bobId")));']),
]})


def follow(who, target, expect=201):
    return req(f"{who.title()} follows {target.title()}", "POST", "/api/follows", auth=who,
               body={"followeeId": "{{" + target + "Id}}"},
               tests=[status(expect), 'pm.test("Now following", () => pm.expect(pm.response.json().following).to.eql(true));'])


folders.append({"name": "03 Follows", "item": [
    follow("alice", "bob"),
    req("Follow again is idempotent -> 200", "POST", "/api/follows", body={"followeeId": "{{bobId}}"},
        tests=[status(200), 'pm.test("No change", () => pm.expect(pm.response.json().changed).to.eql(false));']),
    req("Follow self -> 400", "POST", "/api/follows", body={"followeeId": "{{aliceId}}"},
        tests=[status(400), error_code("CANNOT_FOLLOW_SELF")]),
    req("Follow unknown user -> 404", "POST", "/api/follows", body={"followeeId": "does-not-exist"},
        tests=[status(404)]),
    follow("alice", "carol"),
    follow("bob", "carol"),
    follow("dave", "carol"),
    req("Carol now has 3 followers (celebrity locally)", "GET", "/api/users/by-username/{{carolUsername}}",
        description="docker-compose sets FEED_CELEBRITY_THRESHOLD=3, so Carol's posts use fan-out-on-read.",
        tests=[status(200), "const b = pm.response.json();",
               'pm.test("Follower count maintained transactionally", () => pm.expect(b.followerCount).to.eql(3));',
               'pm.test("Alice follows Carol", () => pm.expect(b.isFollowing).to.eql(true));']),
    req("List Carol's followers", "GET", "/api/users/{{carolId}}/followers", query={"limit": "2"},
        tests=[status(200), "const b = pm.response.json();",
               'pm.test("First page has 2 + cursor", () => { pm.expect(b.items).to.have.length(2); pm.expect(b.nextCursor).to.be.a("string"); });',
               'pm.collectionVariables.set("followersCursor", b.nextCursor);']),
    req("List Carol's followers page 2", "GET", "/api/users/{{carolId}}/followers", query={"limit": "2", "cursor": "{{followersCursor}}"},
        tests=[status(200), 'pm.test("Remaining follower", () => pm.expect(pm.response.json().items.length).to.be.within(1, 2));']),
    req("List who Alice follows", "GET", "/api/users/{{aliceId}}/following",
        tests=[status(200), 'pm.test("Bob and Carol", () => pm.expect(pm.response.json().items.map(u => u.userId)).to.have.members([pm.collectionVariables.get("bobId"), pm.collectionVariables.get("carolId")]));']),
]})


def upload_flow(who, tag):
    return [
        req(f"{who.title()}: initiate photo upload", "POST", "/api/media/uploads", auth=who,
            body={"contentType": "image/jpeg", "sizeBytes": "{{sampleImageBytes}}"},
            tests=[status(201), "const b = pm.response.json();",
                   'pm.test("Single presigned PUT for photos", () => { pm.expect(b.uploadType).to.eql("SINGLE"); pm.expect(b.uploadUrl).to.include("X-Amz-Signature"); });',
                   f'pm.collectionVariables.set("{tag}MediaId", b.mediaId);',
                   f'pm.collectionVariables.set("{tag}UploadUrl", b.uploadUrl);']),
        req(f"{who.title()}: PUT bytes directly to S3", "PUT", None, auth=None,
            raw_url="{{" + tag + "UploadUrl}}", headers={"Content-Type": "image/jpeg"},
            file_body="fixtures/sample.jpg",
            description="Goes straight to S3/LocalStack with the presigned URL: the API never sees the bytes.",
            tests=[status(200)]),
        req(f"{who.title()}: wait until media READY", "GET", "/api/media/{{" + tag + "MediaId}}", auth=who,
            pre=DELAY,
            tests=poll(tag + "MediaPolls", 'json.status !== "PENDING_UPLOAD"') + [
                status(200), "const b = pm.response.json();",
                'pm.test("S3 event -> worker -> READY", () => pm.expect(b.status).to.eql("READY"));',
                'pm.test("Resized to feed width", () => pm.expect(b.width).to.eql(1080));',
                'pm.test("Served from CDN base URL", () => pm.expect(b.url).to.match(/display\\.jpg$/));']),
    ]


folders.append({"name": "04 Media", "item": upload_flow("bob", "bob") + [
    req("Unsupported content type -> 400", "POST", "/api/media/uploads", body={"contentType": "application/pdf", "sizeBytes": 100},
        tests=[status(400), error_code("UNSUPPORTED_MEDIA_TYPE")]),
    req("Photo over 8MB -> 400", "POST", "/api/media/uploads", body={"contentType": "image/png", "sizeBytes": 9000000},
        tests=[status(400), error_code("MEDIA_TOO_LARGE")]),
    req("Large video gets multipart URLs", "POST", "/api/media/uploads", body={"contentType": "video/mp4", "sizeBytes": 262144000},
        tests=[status(201), "const b = pm.response.json();",
               'pm.test("Multipart with 3 x 100MB parts", () => { pm.expect(b.uploadType).to.eql("MULTIPART"); pm.expect(b.parts).to.have.length(3); pm.expect(b.partSize).to.eql(104857600); });',
               'pm.test("Parts numbered from 1", () => pm.expect(b.parts.map(p => p.partNumber)).to.eql([1, 2, 3]));',
               'pm.collectionVariables.set("videoMediaId", b.mediaId);']),
    req("Other users can't see my media -> 404", "GET", "/api/media/{{videoMediaId}}", auth="bob", tests=[status(404)]),
    req("Abort the multipart upload", "DELETE", "/api/media/{{videoMediaId}}", tests=[status(204)]),
]})

folders.append({"name": "05 Posts", "item": [
    req("Bob creates a post", "POST", "/api/posts", auth="bob",
        body={"caption": "Happy little trees", "mediaIds": ["{{bobMediaId}}"]},
        tests=[status(201), "const b = pm.response.json();",
               'pm.test("Published immediately (media already READY)", () => pm.expect(b.status).to.eql("PUBLISHED"));',
               'pm.test("Media denormalized onto post", () => { pm.expect(b.media).to.have.length(1); pm.expect(b.media[0].mediaType).to.eql("IMAGE"); });',
               'pm.collectionVariables.set("bobPostId", b.postId);']),
    req("Reusing attached media -> 400", "POST", "/api/posts", auth="bob",
        body={"caption": "again", "mediaIds": ["{{bobMediaId}}"]},
        tests=[status(400), error_code("INVALID_MEDIA")]),
    req("Using someone else's media -> 400", "POST", "/api/posts",
        body={"caption": "not mine", "mediaIds": ["{{bobMediaId}}"]},
        tests=[status(400), error_code("INVALID_MEDIA")]),
    req("Post without media -> 400", "POST", "/api/posts", body={"caption": "text only", "mediaIds": []},
        tests=[status(400), error_code("VALIDATION_FAILED")]),
    *upload_flow("carol", "carol"),
    req("Carol (celebrity) creates a post", "POST", "/api/posts", auth="carol",
        body={"caption": "Hello fans", "mediaIds": ["{{carolMediaId}}"]},
        tests=[status(201), 'pm.test("Published", () => pm.expect(pm.response.json().status).to.eql("PUBLISHED"));',
               'pm.collectionVariables.set("carolPostId", pm.response.json().postId);']),
    req("Get post", "GET", "/api/posts/{{bobPostId}}",
        tests=[status(200), 'pm.test("Author hydrated", () => pm.expect(pm.response.json().author.username).to.eql(pm.collectionVariables.get("bobUsername")));']),
    req("Bob's profile timeline", "GET", "/api/users/{{bobId}}/posts",
        tests=[status(200), 'pm.test("Contains the post", () => pm.expect(pm.response.json().items.map(p => p.postId)).to.include(pm.collectionVariables.get("bobPostId")));']),
    req("Bob's post count incremented", "GET", "/api/users/me", auth="bob",
        tests=[status(200), 'pm.test("postCount = 1", () => pm.expect(pm.response.json().postCount).to.eql(1));']),
]})

folders.append({"name": "06 Feed", "item": [
    req("Alice's feed has fan-out AND celebrity posts", "GET", "/api/feed", pre=DELAY,
        description="Bob's post arrives via fan-out-on-write (async SQS worker -> Redis). Carol is a celebrity, so her post is merged in at read time.",
        tests=poll("feedPolls", 'json.items && json.items.some(p => p.postId === pm.collectionVariables.get("bobPostId"))') + [
            status(200), "const ids = pm.response.json().items.map(p => p.postId);",
            'pm.test("Bob\'s post (fan-out on write)", () => pm.expect(ids).to.include(pm.collectionVariables.get("bobPostId")));',
            'pm.test("Carol\'s post (fan-out on read)", () => pm.expect(ids).to.include(pm.collectionVariables.get("carolPostId")));',
            'pm.test("Newest first", () => { const ts = pm.response.json().items.map(p => p.publishedAt); pm.expect(ts).to.eql([...ts].sort((a, b) => b - a)); });',
            'pm.test("Latency under 500ms", () => pm.expect(pm.response.responseTime).to.be.below(500));']),
    req("Feed page 1 (limit=1)", "GET", "/api/feed", query={"limit": "1"},
        tests=[status(200), "const b = pm.response.json();",
               'pm.test("One item + opaque cursor", () => { pm.expect(b.items).to.have.length(1); pm.expect(b.nextCursor).to.be.a("string"); });',
               'pm.collectionVariables.set("feedCursor", b.nextCursor);',
               'pm.collectionVariables.set("feedFirstId", b.items[0].postId);']),
    req("Feed page 2 via cursor", "GET", "/api/feed", query={"limit": "1", "cursor": "{{feedCursor}}"},
        tests=[status(200), "const b = pm.response.json();",
               'pm.test("Next item, no overlap", () => { pm.expect(b.items).to.have.length(1); pm.expect(b.items[0].postId).to.not.eql(pm.collectionVariables.get("feedFirstId")); });',
               'pm.test("End of feed", () => pm.expect(b.nextCursor == null).to.be.true);']),
    req("Malformed cursor -> 400", "GET", "/api/feed", query={"cursor": "bm90LWEtY3Vyc29y"},
        tests=[status(400), error_code("INVALID_CURSOR")]),
    req("Dave's feed (celebrity only)", "GET", "/api/feed", auth="dave",
        tests=[status(200), 'pm.test("Only Carol\'s post", () => pm.expect(pm.response.json().items.map(p => p.postId)).to.eql([pm.collectionVariables.get("carolPostId")]));']),
    req("Alice unfollows Bob", "DELETE", "/api/follows/{{bobId}}",
        tests=[status(200), 'pm.test("Unfollowed", () => { pm.expect(pm.response.json().following).to.eql(false); pm.expect(pm.response.json().changed).to.eql(true); });']),
    req("Bob's post removed from Alice's feed", "GET", "/api/feed",
        tests=[status(200), "const ids = pm.response.json().items.map(p => p.postId);",
               'pm.test("Bob gone", () => pm.expect(ids).to.not.include(pm.collectionVariables.get("bobPostId")));',
               'pm.test("Carol still there", () => pm.expect(ids).to.include(pm.collectionVariables.get("carolPostId")));']),
]})

folders.append({"name": "07 Cleanup", "item": [
    req("Stranger cannot delete post -> 403", "DELETE", "/api/posts/{{bobPostId}}", tests=[status(403), error_code("FORBIDDEN")]),
    req("Bob deletes his post", "DELETE", "/api/posts/{{bobPostId}}", auth="bob", tests=[status(204)]),
    req("Deleted post -> 404", "GET", "/api/posts/{{bobPostId}}", auth="bob", tests=[status(404)]),
    req("Carol deletes her post", "DELETE", "/api/posts/{{carolPostId}}", auth="carol", tests=[status(204)]),
    req("Deleted posts drop out of feeds lazily", "GET", "/api/feed", auth="dave",
        tests=[status(200), 'pm.test("Empty", () => pm.expect(pm.response.json().items).to.have.length(0));']),
]})

variables = {
    "password": "correct-horse-42",
    "sampleImageBytes": str(os.path.getsize(os.path.join(HERE, "fixtures", "sample.jpg"))),
}
for who in ["alice", "bob", "carol", "dave"]:
    for suffix in ["Username", "Token", "Id"]:
        variables[who + suffix] = ""

collection = {
    "info": {
        "name": "Printroom API (Instagram system design)",
        "description": (
            "End-to-end contract tests for the Spring Boot API.\n\n"
            "Run the folders in order: each run registers fresh users (alice, bob, carol, dave), so it is re-runnable.\n"
            "Select the **Local (LocalStack)** or **Prod (AWS)** environment for `baseUrl`.\n\n"
            "The S3 upload step sends `fixtures/sample.jpg`; when running with newman pass `--working-dir postman`."
        ),
        "schema": "https://schema.getpostman.com/json/collection/v2.1.0/collection.json",
    },
    "item": folders,
    "variable": [{"key": k, "value": v} for k, v in variables.items()],
}

with open(OUT, "w") as f:
    json.dump(collection, f, indent=2)
print("wrote", OUT, sum(len(f["item"]) for f in folders), "requests")
