import re, sys, requests, urllib.parse as up

BASE = "http://127.0.0.1:10000"
s = requests.Session()
results = []
def chk(name, ok, extra=""):
    results.append((name, ok, extra))
    print(("PASS " if ok else "FAIL ") + name + (("  " + str(extra)) if extra else ""))

def csrf(html):
    m = re.search(r'name="csrf_token" value="([^"]+)"', html)
    return m.group(1) if m else None

def captcha(sess=None):
    sess = sess or s
    r = sess.get(BASE + "/captcha.svg?refresh=1")
    chars = re.findall(r'>([A-Z2-9])</text>', r.text)
    return "".join(chars)

# 1 首页与页面可达
r = s.get(BASE + "/", allow_redirects=False)
chk("GET / 重定向到 /auth", r.status_code == 302 and "/auth" in r.headers.get("Location", ""), r.headers.get("Location"))

r = s.get(BASE + "/auth")
chk("GET /auth 200", r.status_code == 200, r.status_code)
html = r.text
chk("登录页含 CSRF token", csrf(html) is not None)
chk("登录页含验证码 img", "/captcha.svg" in html)

r = s.get(BASE + "/auth?mode=register")
chk("GET /auth?mode=register 200", r.status_code == 200, r.status_code)
reg_html = r.text
chk("注册页含手机号/安全问题字段", 'name="phone"' in reg_html and 'name="question"' in reg_html)

r = s.get(BASE + "/recover")
chk("GET /recover 200", r.status_code == 200, r.status_code)
chk("找回页有表单", "<form" in r.text, len(r.text))

# 2 验证码错误 -> 失败计数
tok = csrf(s.get(BASE + "/auth?mode=register").text)
r = s.post(BASE + "/auth", data={"csrf_token": tok, "mode": "register", "username": "tester01",
                                 "password": "Str0ngPass123", "captcha": "ZZZZZ", "phone": "13800000000",
                                 "question": "我第一只宠物叫什么？", "answer": "豆豆"})
chk("错误验证码被拒", "验证码不正确" in r.text or "验证码错误" in r.text, r.text[400:600])

# 3 正确注册
r = s.get(BASE + "/auth?mode=register")
tok = csrf(r.text); code = captcha()
r = s.post(BASE + "/auth", data={"csrf_token": tok, "mode": "register", "username": "tester01",
                                 "password": "Str0ngPass123", "captcha": code, "phone": "13800000000",
                                 "question": "我第一只宠物叫什么？", "answer": "豆豆"})
ok_reg = "注册成功" in r.text
chk("注册成功", ok_reg, re.search(r"Q7-\d+", r.text).group(0) if re.search(r"Q7-\d+", r.text) else r.text[600:800])
acct = re.search(r"Q7-\d+", r.text).group(0) if re.search(r"Q7-\d+", r.text) else None

# 4 重复注册
r = s.get(BASE + "/auth?mode=register"); tok = csrf(r.text); code = captcha()
r = s.post(BASE + "/auth", data={"csrf_token": tok, "mode": "register", "username": "tester01",
                                 "password": "Str0ngPass123", "captcha": code, "phone": "13800000000",
                                 "question": "我第一只宠物叫什么？", "answer": "豆豆"})
chk("重复用户名被拦", "已被使用" in r.text or "未完成" in r.text, r.text[600:800])

# 5 登录（用户名）
r = s.get(BASE + "/auth"); tok = csrf(r.text); code = captcha()
r = s.post(BASE + "/auth", data={"csrf_token": tok, "mode": "login", "username": "tester01",
                                 "password": "Str0ngPass123", "captcha": code}, allow_redirects=False)
chk("用户名登录成功跳转 dashboard", r.status_code == 302 and "dashboard" in r.headers.get("Location", ""), r.status_code)

r = s.get(BASE + "/dashboard")
chk("GET /dashboard 200", r.status_code == 200, r.status_code)
chk("控制台显示专属 ID", acct and acct in r.text, acct)
chk("控制台节点计数元素存在", 'id="nodecount"' in r.text)

# 6 用 Q7-ID 登录
s2 = requests.Session()
r = s2.get(BASE + "/auth"); tok = csrf(r.text); code = captcha(s2)
r = s2.post(BASE + "/auth", data={"csrf_token": tok, "mode": "login", "username": acct,
                                  "password": "Str0ngPass123", "captcha": code}, allow_redirects=False)
chk("专属 ID 登录成功", r.status_code == 302, r.status_code)

# 7 导入节点
dash = s.get(BASE + "/dashboard").text
tok = csrf(dash)
nodes_txt = "\n".join([
    "vless://1234abcd-1111-2222-3333-444455556666@example.com:443?encryption=none&security=tls&type=ws#HK-01",
    "ss://YWVzLTI1Ni1nY206cGFzc3dvcmQ@1.2.3.4:8388#SG-01",
    "vless://1234abcd-1111-2222-3333-444455556666@example.com:443?encryption=none&security=tls&type=ws#HK-01",
    "garbage line not a node",
])
r = s.post(BASE + "/api/nodes/import", data={"text": nodes_txt}, headers={"X-CSRFToken": tok})
j = r.json() if r.headers.get("Content-Type", "").startswith("application/json") else {}
chk("导入接口返回统计", j.get("added") == 2 and j.get("duplicate") == 1 and j.get("invalid") == 1, j)

# 8 导出
r = s.get(BASE + "/api/nodes/export")
chk("导出 TXT", r.status_code == 200 and "vless://" in r.text and "ss://" in r.text, r.headers.get("Content-Disposition"))

# 9 删除
dash = s.get(BASE + "/dashboard").text
tok = csrf(dash)
ids = re.findall(r'deleteNode\((\d+)', dash)
chk("控制台渲染出删除按钮", len(ids) >= 1, ids[:3])
if ids:
    r = s.post(BASE + "/api/nodes/%s/delete" % ids[0], headers={"X-CSRFToken": tok})
    chk("删除节点", r.status_code == 200 and r.json().get("ok") is True, r.text[:80])

# 10 无 CSRF 的写操作应被拒
r = s.post(BASE + "/api/nodes/import", data={"text": "vless://x@example.com:443#t"}, allow_redirects=False)
chk("无 CSRF token 的导入被拒", r.status_code in (302, 400, 403), r.status_code)

# 11 安全中心
r = s.get(BASE + "/security")
chk("GET /security 200", r.status_code == 200, r.status_code)
chk("安全中心显示活动记录", "登录成功" in r.text or "注册" in r.text)

# 12 改密码流程
tok = csrf(r.text)
r = s.post(BASE + "/security/password", data={"csrf_token": tok, "current_password": "wrongwrong",
                                              "new_password": "NewStr0ngPass1", "confirm_password": "NewStr0ngPass1"}, allow_redirects=True)
chk("错误当前密码被拒", "当前密码不正确" in r.text, r.text[300:500])
tok = csrf(r.text)
r = s.post(BASE + "/security/password", data={"csrf_token": tok, "current_password": "Str0ngPass123",
                                              "new_password": "NewStr0ngPass1", "confirm_password": "NewStr0ngPass1"}, allow_redirects=True)
chk("改密码成功", "密码修改成功" in r.text)

# 13 新密码登录
s3 = requests.Session(); r = s3.get(BASE + "/auth"); tok = csrf(r.text); code = captcha(s3)
r = s3.post(BASE + "/auth", data={"csrf_token": tok, "mode": "login", "username": "tester01",
                                  "password": "NewStr0ngPass1", "captcha": code}, allow_redirects=False)
chk("新密码可登录", r.status_code == 302, r.status_code)

# 14 找回密码
s4 = requests.Session(); r = s4.get(BASE + "/recover"); tok = csrf(r.text)
chk("找回页带 CSRF", tok is not None)
r = s4.post(BASE + "/recover", data={"csrf_token": tok, "step": "lookup", "username": "tester01", "phone": "13800000000"})
chk("找回第一步显示安全问题", "宠物" in r.text, r.text[:200])
tok = csrf(r.text)
r = s4.post(BASE + "/recover", data={"csrf_token": tok, "step": "reset", "answer": "豆豆",
                                     "new_password": "ResetPass1234"})
chk("找回第二步重置成功", "密码已重置" in r.text, r.text[:300])

# 15 未登录访问受保护页
s5 = requests.Session()
for path in ["/dashboard", "/security", "/api/nodes/export"]:
    r = s5.get(BASE + path, allow_redirects=False)
    chk("未登录 %s 被拦截" % path, r.status_code in (302, 401, 403), r.status_code)

# 16 logout
dash = s.get(BASE + "/dashboard").text; tok = csrf(dash)
r = s.post(BASE + "/logout", data={"csrf_token": tok}, allow_redirects=False)
chk("退出登录", r.status_code == 302)
r = s.get(BASE + "/dashboard", allow_redirects=False)
chk("退出后 dashboard 不可访问", r.status_code == 302)

fail = [x for x in results if not x[1]]
print("\n===== %d/%d 通过 =====" % (len(results) - len(fail), len(results)))
for n, ok, e in fail:
    print("FAILED:", n, "|", e)
sys.exit(1 if fail else 0)
