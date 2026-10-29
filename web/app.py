import os, re, json, sqlite3, secrets, hashlib, hmac, time
from contextlib import contextmanager
from datetime import datetime, timedelta, timezone
from functools import wraps
from urllib.parse import urlparse, parse_qs, unquote
from flask import Flask, render_template, request, redirect, url_for, session, flash, jsonify, abort, Response
from werkzeug.security import generate_password_hash, check_password_hash
from werkzeug.middleware.proxy_fix import ProxyFix
from flask_wtf.csrf import CSRFProtect, CSRFError
from flask_limiter import Limiter
from flask_limiter.util import get_remote_address

APP_DIR = os.path.abspath(os.path.dirname(__file__))
DB_PATH = os.environ.get("Q7VPN_DB_PATH", os.path.join(APP_DIR, "q7vpn.sqlite3"))
TZ_OFFSET = float(os.environ.get("Q7VPN_TZ_OFFSET", "8"))  # 默认北京时间


def _ensure_parent_dir(path):
    """A missing directory (e.g. an unmounted /var/data disk) makes sqlite3 fail hard at import time."""
    parent = os.path.dirname(os.path.abspath(path))
    if parent and not os.path.isdir(parent):
        try:
            os.makedirs(parent, exist_ok=True)
        except OSError:
            pass


def _resolve_secret_key():
    """Keep sessions alive across restarts when SECRET_KEY is not provided via env."""
    env_secret = os.environ.get("SECRET_KEY", "").strip()
    if env_secret:
        return env_secret
    key_file = os.path.join(APP_DIR, "instance", "secret_key")
    try:
        if os.path.isfile(key_file):
            with open(key_file, "r", encoding="utf-8") as fh:
                stored = fh.read().strip()
            if len(stored) >= 32:
                return stored
        generated = secrets.token_hex(32)
        os.makedirs(os.path.dirname(key_file), exist_ok=True)
        with open(key_file, "w", encoding="utf-8") as fh:
            fh.write(generated)
        os.chmod(key_file, 0o600)
        return generated
    except OSError:
        return secrets.token_hex(32)


_ensure_parent_dir(DB_PATH)
app = Flask(__name__)
app.secret_key = _resolve_secret_key()
app.wsgi_app = ProxyFix(app.wsgi_app, x_for=1, x_proto=1, x_host=1)
app.config.update(
    SESSION_COOKIE_HTTPONLY=True,
    SESSION_COOKIE_SAMESITE="Lax",
    SESSION_COOKIE_SECURE=os.environ.get("COOKIE_SECURE", "1") != "0",
    PERMANENT_SESSION_LIFETIME=timedelta(days=30),
    MAX_CONTENT_LENGTH=1 * 1024 * 1024,
)
csrf = CSRFProtect(app)
limiter = Limiter(get_remote_address, app=app, default_limits=["300 per hour"], storage_uri=os.environ.get("RATELIMIT_STORAGE_URI", "memory://"))


def _connect():
    con = sqlite3.connect(DB_PATH, timeout=15)
    con.row_factory = sqlite3.Row
    con.execute("PRAGMA busy_timeout=15000")
    con.execute("PRAGMA foreign_keys=ON")
    return con


@contextmanager
def db():
    """Every caller used to leak its connection: sqlite3's own context manager only commits, never closes.
    Leaked handles plus nested writes are what produced `database is locked` under load."""
    con = _connect()
    try:
        yield con
        con.commit()
    except Exception:
        con.rollback()
        raise
    finally:
        con.close()

@app.template_filter("timestamp_datetime")
def timestamp_datetime(value):
    try:
        return datetime.fromtimestamp(int(value), timezone(timedelta(hours=TZ_OFFSET))).strftime("%Y-%m-%d %H:%M")
    except (TypeError, ValueError, OSError, OverflowError):
        return "—"

def init_db(retries=5):
    """Retried because gunicorn boots several workers that race on CREATE TABLE."""
    for attempt in range(retries):
        try:
            with db() as con:
                try:
                    con.execute("PRAGMA journal_mode=WAL")
                except sqlite3.DatabaseError:
                    pass  # network filesystems refuse WAL; the busy_timeout still covers us
                con.execute("""CREATE TABLE IF NOT EXISTS users(
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    username TEXT UNIQUE NOT NULL,
                    password_hash TEXT NOT NULL,
                    phone TEXT NOT NULL,
                    security_question TEXT NOT NULL,
                    security_answer_hash TEXT NOT NULL,
                    created_at INTEGER NOT NULL
                )""")
                con.execute("""CREATE TABLE IF NOT EXISTS security_events(
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    created_at INTEGER NOT NULL,
                    category TEXT NOT NULL,
                    detail TEXT NOT NULL,
                    ip_hash TEXT NOT NULL,
                    user_id INTEGER
                )""")
                # Safe migration for databases created by earlier Q7vpn versions.
                event_columns = {row[1] for row in con.execute("PRAGMA table_info(security_events)").fetchall()}
                if "user_id" not in event_columns:
                    con.execute("ALTER TABLE security_events ADD COLUMN user_id INTEGER")
                con.execute("""CREATE TABLE IF NOT EXISTS nodes(
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    user_id INTEGER NOT NULL,
                    uri TEXT NOT NULL,
                    name TEXT NOT NULL,
                    UNIQUE(user_id, uri),
                    FOREIGN KEY(user_id) REFERENCES users(id)
                )""")
                con.execute("""CREATE TABLE IF NOT EXISTS auth_ip_guard(
                    ip_hash TEXT PRIMARY KEY,
                    fail_count INTEGER NOT NULL DEFAULT 0,
                    window_start INTEGER NOT NULL,
                    locked_until INTEGER NOT NULL DEFAULT 0
                )""")
                con.execute("CREATE INDEX IF NOT EXISTS idx_events_user ON security_events(user_id, id DESC)")
                con.execute("CREATE INDEX IF NOT EXISTS idx_nodes_user ON nodes(user_id, id DESC)")
            return
        except sqlite3.OperationalError as exc:
            if "locked" not in str(exc).lower() or attempt == retries - 1:
                raise
            time.sleep(0.3 * (attempt + 1))

def log_event(category, detail, user_id=None):
    ip_hash = client_ip_hash()
    owner_id = user_id if user_id is not None else session.get("user_id")
    with db() as con:
        con.execute("INSERT INTO security_events(created_at,category,detail,ip_hash,user_id) VALUES(?,?,?,?,?)",
                    (int(time.time()), category, detail[:180], ip_hash, owner_id))

def logged_in(f):
    @wraps(f)
    def inner(*args, **kwargs):
        if not session.get("user_id"):
            return redirect(url_for("auth"))
        return f(*args, **kwargs)
    return inner

def parse_node(uri):
    uri = uri.strip()
    if uri.startswith("vless://"):
        try:
            u = urlparse(uri)
            if not u.hostname or not (u.port or 443): return None
            name = unquote(u.fragment) if u.fragment else u.hostname
            return {"uri": uri, "name": name[:120], "type": "VLESS", "host": u.hostname, "port": u.port or 443}
        except Exception:
            return None
    if uri.startswith("ss://"):
        try:
            body = uri[5:].split("#", 1)[0]
            name = unquote(uri.split("#", 1)[1]) if "#" in uri else "Shadowsocks 节点"
            # SS formats vary; preserve URI and validate basic shape without decoding secrets into logs.
            if "@" not in body and len(body) < 12: return None
            return {"uri": uri, "name": name[:120], "type": "SS", "host": "", "port": ""}
        except Exception:
            return None
    return None

@app.route("/")
def index():
    if session.get("user_id"):
        return redirect(url_for("dashboard"))
    return redirect(url_for("auth"))

def client_ip_hash():
    ip = get_remote_address() or "unknown"
    salt = os.environ.get("IP_HASH_SALT", "change-this-salt")
    return hashlib.sha256((salt + ip).encode()).hexdigest()[:24]


def auth_guard_status():
    now = int(time.time())
    with db() as con:
        row = con.execute("SELECT fail_count,window_start,locked_until FROM auth_ip_guard WHERE ip_hash=?", (client_ip_hash(),)).fetchone()
    if not row:
        return {"locked": False, "remaining": 0, "fail_count": 0}
    remaining = max(0, int(row["locked_until"]) - now)
    return {"locked": remaining > 0, "remaining": remaining, "fail_count": int(row["fail_count"])}


def record_auth_failure():
    """Five failed login/registration submissions from one IP trigger a five-minute lock."""
    now = int(time.time())
    ip_hash = client_ip_hash()
    with db() as con:
        row = con.execute("SELECT fail_count,window_start,locked_until FROM auth_ip_guard WHERE ip_hash=?", (ip_hash,)).fetchone()
        if row and int(row["locked_until"]) > now:
            return int(row["locked_until"]) - now
        if not row or now - int(row["window_start"]) > 900:
            count, window_start = 1, now
        else:
            count, window_start = int(row["fail_count"]) + 1, int(row["window_start"])
        locked_until = now + 300 if count >= 5 else 0
        con.execute("""INSERT INTO auth_ip_guard(ip_hash,fail_count,window_start,locked_until) VALUES(?,?,?,?)
            ON CONFLICT(ip_hash) DO UPDATE SET fail_count=excluded.fail_count,window_start=excluded.window_start,locked_until=excluded.locked_until""",
            (ip_hash, count, window_start, locked_until))
    return max(0, locked_until - now)


def clear_auth_failures():
    with db() as con:
        con.execute("DELETE FROM auth_ip_guard WHERE ip_hash=?", (client_ip_hash(),))


def new_captcha():
    session["q7_captcha"] = "".join(secrets.choice("23456789ABCDEFGHJKLMNPQRSTUVWXYZ") for _ in range(4))
    session["q7_captcha_at"] = int(time.time())


def render_auth_page(mode="login"):
    if not session.get("q7_captcha") or int(time.time()) - int(session.get("q7_captcha_at", 0)) > 600:
        new_captcha()
    guard = auth_guard_status()
    return render_template("auth.html", mode=mode, lock_remaining=guard["remaining"], captcha_locked=guard["locked"])


@app.get("/captcha.svg")
def captcha_image():
    """Session-bound image captcha; no SMS or external captcha service required."""
    if request.args.get("refresh") == "1" or not session.get("q7_captcha") or int(time.time()) - int(session.get("q7_captcha_at", 0)) > 600:
        new_captcha()
    chars = session["q7_captcha"]
    lines = []
    for i in range(4):
        x1, y1 = secrets.randbelow(210), secrets.randbelow(54)
        x2, y2 = secrets.randbelow(210), secrets.randbelow(54)
        hue = secrets.choice([178, 205, 250, 280])
        lines.append(f'<path d="M{x1} {y1} L{x2} {y2}" stroke="hsl({hue},75%,70%)" stroke-opacity=".38" stroke-width="1.2"/>')
    text_nodes = []
    for i, ch in enumerate(chars):
        y = 35 + secrets.randbelow(9) - 4
        rotate = secrets.randbelow(25) - 12
        x = 42 + i * 45
        fill = secrets.choice(["#e8fbff", "#a9f3f0", "#c9baff"])
        text_nodes.append(f'<text x="{x}" y="{y}" transform="rotate({rotate} {x} {y})" fill="{fill}" font-size="25" font-weight="800">{ch}</text>')
    svg = ('<svg xmlns="http://www.w3.org/2000/svg" width="220" height="54" viewBox="0 0 220 54">'
           '<defs><linearGradient id="g"><stop stop-color="#142a3b"/><stop offset=".55" stop-color="#252143"/><stop offset="1" stop-color="#13333c"/></linearGradient></defs>'
           '<rect width="220" height="54" rx="10" fill="url(#g)"/>' + ''.join(lines) + ''.join(text_nodes) + '</svg>')
    return Response(svg, mimetype="image/svg+xml", headers={"Cache-Control": "no-store, no-cache, must-revalidate, max-age=0", "Pragma": "no-cache"})


@app.route("/auth", methods=["GET", "POST"])
@limiter.limit("20 per minute", methods=["POST"])
def auth():
    mode = request.form.get("mode", request.args.get("mode", "login"))
    if mode not in ("login", "register"):
        mode = "login"
    guard = auth_guard_status()
    if guard["locked"]:
        flash("该 IP 的登录/注册失败次数过多，请倒计时结束后再试。", "error")
        return render_auth_page(mode)

    if request.method == "POST":
        username = request.form.get("username", "").strip()
        password = request.form.get("password", "")
        captcha_input = request.form.get("captcha", "").strip().upper()
        captcha_expected = str(session.get("q7_captcha", "")).upper()
        captcha_fresh = int(time.time()) - int(session.get("q7_captcha_at", 0)) <= 600
        session.pop("q7_captcha", None)
        session.pop("q7_captcha_at", None)
        if not captcha_fresh or not captcha_expected or not hmac.compare_digest(captcha_input, captcha_expected):
            # A mistyped captcha must not burn one of the five password attempts: users who
            # fumble the image three times were getting locked out of their own account.
            # Brute force is still bounded by the 20/minute limiter on this route.
            new_captcha()
            flash("图片验证码不正确或已过期，请重新识别。", "error")
            return render_auth_page(mode)

        if mode == "register":
            if (not re.fullmatch(r"[A-Za-z0-9_.-]{3,32}", username) or re.fullmatch(r"Q7-[0-9]{1,12}", username, re.I)):
                record_auth_failure()
                flash("用户名需为 3–32 位字母、数字、点、下划线或短横线，且不能占用 Q7-数字专属 ID 格式。", "error")
                new_captcha()
                return render_auth_page(mode)
            phone = request.form.get("phone", "").strip()
            question = request.form.get("question", "").strip()
            answer = request.form.get("answer", "").strip()
            if len(password) < 10:
                flash("密码至少 10 位，建议使用独立且随机的密码。", "error")
            elif not re.fullmatch(r"[+0-9 ()-]{7,24}", phone):
                flash("请输入有效格式的手机号。当前版本不会发送短信。", "error")
            elif not question or len(answer) < 2:
                flash("请选择安全问题并填写答案。", "error")
            else:
                try:
                    with db() as con:
                        cur = con.execute("INSERT INTO users(username,password_hash,phone,security_question,security_answer_hash,created_at) VALUES(?,?,?,?,?,?)",
                                          (username, generate_password_hash(password), phone, question[:160], generate_password_hash(answer.casefold()), int(time.time())))
                        new_user_id = cur.lastrowid
                    clear_auth_failures()
                    log_event("account", "新账户注册", user_id=new_user_id)
                    account_id = f"Q7-{new_user_id:07d}"
                    flash(f"注册成功！你的专属 ID 是 {account_id}，请保存好。", "success")
                    return render_auth_page("login")
                except sqlite3.IntegrityError:
                    record_auth_failure()
                    flash("注册未完成：该用户名可能已被使用。", "error")
        else:
            if not re.fullmatch(r"(?:[A-Za-z0-9_.-]{3,32}|Q7-[0-9]{1,12})", username, re.I):
                record_auth_failure()
                flash("请输入注册用户名或 Q7-数字专属 ID。", "error")
                new_captcha()
                return render_auth_page(mode)
            with db() as con:
                if re.fullmatch(r"Q7-[0-9]{1,12}", username, re.I):
                    account_num = int(username.split("-", 1)[1])
                    user = con.execute("SELECT * FROM users WHERE id=?", (account_num,)).fetchone()
                else:
                    user = con.execute("SELECT * FROM users WHERE username=?", (username,)).fetchone()
            if user and check_password_hash(user["password_hash"], password):
                clear_auth_failures()
                session.clear()
                session["user_id"] = user["id"]
                session["username"] = user["username"]
                session.permanent = True
                log_event("auth", "登录成功", user_id=user["id"])
                return redirect(url_for("dashboard"))
            remaining = record_auth_failure()
            log_event("auth", "登录失败", user_id=user["id"] if user else None)
            if remaining:
                flash("失败次数已达上限，当前 IP 已锁定 5 分钟。", "error")
            else:
                left = max(0, 5 - auth_guard_status()["fail_count"])
                flash(f"账号 ID / 用户名或密码错误。再失败 {left} 次将锁定 5 分钟。", "error")
        new_captcha()
    return render_auth_page(mode)

@app.route("/recover", methods=["GET", "POST"])
@limiter.limit("5 per minute", methods=["POST"])
def recover():
    question = None
    if request.method == "POST":
        step = request.form.get("step", "lookup")
        username = request.form.get("username", "").strip()
        phone = request.form.get("phone", "").strip()
        if step == "lookup":
            with db() as con:
                user = con.execute("SELECT username,phone,security_question FROM users WHERE username=? AND phone=?", (username, phone)).fetchone()
            if user:
                question = user["security_question"]
                session["recover_user"] = username
                flash("请回答注册时设置的安全问题。", "success")
            else:
                flash("信息不匹配或账户不存在。", "error")
        elif step == "reset":
            answer = request.form.get("answer", "").strip().casefold()
            new_password = request.form.get("new_password", "")
            uname = session.get("recover_user")
            reset_user_id = None
            with db() as con:
                user = con.execute("SELECT * FROM users WHERE username=?", (uname,)).fetchone() if uname else None
                if user and check_password_hash(user["security_answer_hash"], answer) and len(new_password) >= 10:
                    con.execute("UPDATE users SET password_hash=? WHERE id=?", (generate_password_hash(new_password), user["id"]))
                    reset_user_id = user["id"]
                else:
                    question = user["security_question"] if user else None
            if reset_user_id:
                # Written after the transaction closes: logging inside the block opened a second
                # connection and deadlocked on the first one's write lock.
                session.pop("recover_user", None)
                log_event("account", "通过安全问题重置密码", user_id=reset_user_id)
                flash("密码已重置，请重新登录。", "success")
                return redirect(url_for("auth"))
            flash("验证失败，或新密码不足 10 位。", "error")
    return render_template("recover.html", question=question)

@app.route("/logout", methods=["POST"])
@logged_in
def logout():
    session.clear()
    return redirect(url_for("auth"))

@app.route("/dashboard")
@logged_in
def dashboard():
    with db() as con:
        nodes = con.execute("SELECT id,uri,name FROM nodes WHERE user_id=? ORDER BY id DESC", (session["user_id"],)).fetchall()
    return render_template("dashboard.html", nodes=nodes, username=session.get("username"), account_id=f"Q7-{int(session['user_id']):07d}")

@app.post("/api/nodes/import")
@logged_in
@limiter.limit("20 per minute")
def import_nodes():
    text = request.form.get("text", "")
    added = duplicate = invalid = 0
    with db() as con:
        for line in text.splitlines()[:3000]:
            line = line.strip()
            if not line or line.startswith("#"): continue
            item = parse_node(line)
            if not item:
                invalid += 1
                continue
            try:
                con.execute("INSERT INTO nodes(user_id,uri,name) VALUES(?,?,?)", (session["user_id"], item["uri"], item["name"]))
                added += 1
            except sqlite3.IntegrityError:
                duplicate += 1
    return jsonify(added=added, duplicate=duplicate, invalid=invalid)

@app.post("/api/nodes/<int:node_id>/delete")
@logged_in
@limiter.limit("60 per minute")
def delete_node(node_id):
    with db() as con:
        con.execute("DELETE FROM nodes WHERE id=? AND user_id=?", (node_id, session["user_id"]))
    return jsonify(ok=True)

@app.get("/api/nodes/export")
@logged_in
@limiter.limit("60 per minute")
def export_nodes():
    from flask import Response
    with db() as con:
        rows = con.execute("SELECT uri FROM nodes WHERE user_id=? ORDER BY id", (session["user_id"],)).fetchall()
    return Response("\n".join(r["uri"] for r in rows) + ("\n" if rows else ""),
                    mimetype="text/plain; charset=utf-8",
                    headers={"Content-Disposition": "attachment; filename=q7vpn-nodes.txt"})

@app.route("/security", methods=["GET"])
@logged_in
def security():
    with db() as con:
        user = con.execute("SELECT id,username,phone,created_at,security_question FROM users WHERE id=?", (session["user_id"],)).fetchone()
        events = con.execute("SELECT created_at,category,detail,ip_hash FROM security_events WHERE user_id=? ORDER BY id DESC LIMIT 30", (session["user_id"],)).fetchall()
        node_count = con.execute("SELECT COUNT(*) AS c FROM nodes WHERE user_id=?", (session["user_id"],)).fetchone()["c"]
    return render_template("security.html", events=events, node_count=node_count, user=user,
                           account_id=f"Q7-{int(session['user_id']):07d}")

@app.post("/security/password")
@logged_in
@limiter.limit("5 per minute")
def change_password():
    current_password = request.form.get("current_password", "")
    new_password = request.form.get("new_password", "")
    confirm_password = request.form.get("confirm_password", "")
    changed = False
    with db() as con:
        user = con.execute("SELECT password_hash FROM users WHERE id=?", (session["user_id"],)).fetchone()
        if not user or not check_password_hash(user["password_hash"], current_password):
            flash("当前密码不正确，密码没有修改。", "error")
        elif len(new_password) < 10:
            flash("新密码至少需要 10 位。", "error")
        elif new_password != confirm_password:
            flash("两次输入的新密码不一致。", "error")
        elif check_password_hash(user["password_hash"], new_password):
            flash("新密码不能与当前密码相同。", "error")
        else:
            con.execute("UPDATE users SET password_hash=? WHERE id=?",
                        (generate_password_hash(new_password), session["user_id"]))
            changed = True
    if changed:
        log_event("account", "账户密码已修改", user_id=session["user_id"])
        flash("密码修改成功。请妥善保管新密码。", "success")
    return redirect(url_for("security") + "#password")

@app.get("/health")
@limiter.exempt
def health():
    """Exempt from the 300/hour default: platform health checks alone would trip it and take the site down."""
    return jsonify(status="ok", app="Q7VPN", version="5.2.1")


@app.after_request
def security_headers(response):
    response.headers.setdefault("X-Content-Type-Options", "nosniff")
    response.headers.setdefault("X-Frame-Options", "SAMEORIGIN")
    response.headers.setdefault("Referrer-Policy", "same-origin")
    response.headers.setdefault("Permissions-Policy", "geolocation=(), microphone=(), camera=()")
    return response


@app.errorhandler(CSRFError)
def csrf_failed(_):
    flash("安全令牌已失效（页面可能停留过久），请重新提交。", "error")
    return redirect(url_for("dashboard") if session.get("user_id") else url_for("auth"))


@app.errorhandler(413)
def too_large(_):
    return "上传内容过大（上限 1 MB）。", 413


@app.errorhandler(429)
def rate_limited(_):
    # Must stay a 429: returning a bare string body from a handler silently downgrades to 200
    # and turns "you are being rate limited" into what looks like a successful request.
    page = os.path.join(APP_DIR, "templates", "rate_limited.html")
    if os.path.exists(page):
        return render_template("rate_limited.html"), 429
    return "请求过于频繁，请稍后再试。", 429


if os.environ.get("Q7VPN_RATELIMIT_OFF") == "1":
    # Local load-testing only. Never set this on a public deployment.
    limiter.enabled = False

init_db()
if __name__ == "__main__":
    if app.config["SESSION_COOKIE_SECURE"] and os.environ.get("COOKIE_SECURE") is None:
        app.logger.warning("SESSION_COOKIE_SECURE=1：若用 http:// 直接访问，浏览器不会回传会话 Cookie，"
                           "登录会静默失败。本地自测请设 COOKIE_SECURE=0。")
    app.run(host="0.0.0.0", port=int(os.environ.get("PORT", 10000)))
