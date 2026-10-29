import re, sys, requests, threading, time

BASE = "http://127.0.0.1:10000"
errs, ok = [], []

def csrf(html):
    m = re.search(r'name="csrf_token" value="([^"]+)"', html)
    return m.group(1) if m else None

def captcha(sess):
    r = sess.get(BASE + "/captcha.svg?refresh=1")
    return "".join(re.findall(r'>([A-Z2-9])</text>', r.text))

def worker(i):
    s = requests.Session()
    try:
        r = s.get(BASE + "/auth")
        tok = csrf(r.text)
        code = captcha(s)
        r = s.post(BASE + "/auth", data={"csrf_token": tok, "mode": "register", "username": "load%03d" % i,
                                         "password": "LoadPass12345", "captcha": code,
                                         "phone": "1380000%04d" % i, "question": "我第一只宠物叫什么？",
                                         "answer": "豆豆"}, allow_redirects=False)
        if r.status_code >= 500:
            errs.append((i, "register", r.status_code)); return
        r = s.get(BASE + "/auth"); tok = csrf(r.text); code = captcha(s)
        r = s.post(BASE + "/auth", data={"csrf_token": tok, "mode": "login", "username": "load%03d" % i,
                                         "password": "LoadPass12345", "captcha": code}, allow_redirects=False)
        if r.status_code >= 500 or r.status_code != 302:
            errs.append((i, "login", r.status_code)); return
        d = s.get(BASE + "/dashboard").text
        if d.status_code if False else False: pass
        tok = csrf(d)
        r = s.post(BASE + "/api/nodes/import", data={"text": "vless://u%d@example.com:443#n%d" % (i, i)},
                   headers={"X-CSRFToken": tok})
        if r.status_code >= 500:
            errs.append((i, "import", r.status_code)); return
        s.get(BASE + "/security")
        ok.append(i)
    except Exception as exc:
        errs.append((i, type(exc).__name__ + ":" + str(exc)[:60]))

# 1) 健康检查不能被默认限流打挂（Render 会频繁探活）
codes = []
for _ in range(400):
    codes.append(requests.get(BASE + "/health", timeout=5).status_code)
print("health 400 连击状态码:", sorted(set(codes)), "全部200:", set(codes) == {200})

# 2) 并发注册/登录/写入
t0 = time.time()
threads = [threading.Thread(target=worker, args=(i,)) for i in range(1, 26)]
[t.start() for t in threads]
[t.join() for t in threads]
print("并发完成 %d 成功 / %d 失败, 耗时 %.1fs" % (len(ok), len(errs), time.time() - t0))
for e in errs[:10]:
    print("  err:", e)

# 3) 并发写同一用户的节点，检查锁
s = requests.Session()
r = s.get(BASE + "/auth"); tok = csrf(r.text); code = captcha(s)
r = s.post(BASE + "/auth", data={"csrf_token": tok, "mode": "login", "username": "load001",
                                 "password": "LoadPass12345", "captcha": code}, allow_redirects=False)
print("复用用户登录:", r.status_code)
d = s.get(BASE + "/dashboard").text; tok = csrf(d)
lock_errors = []
def burst(k):
    ss = requests.Session()
    ss.cookies.update(s.cookies)
    for j in range(5):
        rr = ss.post(BASE + "/api/nodes/import", data={"text": "vless://b%d-%d@example.com:443#x%d%d" % (k, j, k, j)},
                     headers={"X-CSRFToken": tok})
        if rr.status_code >= 500:
            lock_errors.append(rr.status_code)
            return
ts = [threading.Thread(target=burst, args=(k,)) for k in range(10)]
[t.start() for t in ts]; [t.join() for t in ts]
print("100 次并发写 + 5xx 数量:", len(lock_errors))
sys.exit(0 if (set(codes) == {200} and not errs and not lock_errors) else 1)
