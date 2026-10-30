/* Q7VPN shared Chinese/English localization. Covers text, form attributes, dynamic messages and dialogs. */
(() => {
  'use strict';
  const pairs = {
    '账户':'Account','专属 ID':'Unique ID','Q7VPN · 账户':'Q7VPN · Account','Q7VPN · 控制台':'Q7VPN · Dashboard','Q7VPN · 账户与安全':'Q7VPN · Account & Security','Q7VPN · 找回密码':'Q7VPN · Recover password','Q7VPN · 请求过于频繁':'Q7VPN · Too many requests','文':'EN',
    '显示设置':'Display settings','切换语言':'Switch language','切换主题':'Switch theme','主题':'Theme','色彩':'Accent','创建你的账户':'Create your account','欢迎回来':'Welcome back',
    '建立你的专属 Q7VPN 身份，安全管理节点配置。':'Create your Q7VPN identity and securely manage node configurations.','登录后继续管理你的专属网络配置。':'Sign in to manage your private network configurations.',
    '登录账户':'Sign in','创建账户':'Create account','设置用户名':'Choose a username','例如 q7_user':'e.g. q7_user','用户名 / 专属 ID':'Username / Account ID','用户名或 Q7-0000001':'Username or Q7-0000001',
    '设置登录密码':'Create password','登录密码':'Password','至少 10 位':'At least 10 characters','图片验证码':'Image verification code','输入右侧 4 位验证码':'Enter the 4-character code on the right',
    '刷新验证码':'Refresh verification code','看不清？点击刷新':'Can’t read it? Click to refresh','图片验证码，点击刷新':'Image verification code. Click to refresh','换一张':'Refresh','验证码不通过短信发送，有效期 10 分钟；点击图片可更换。':'No SMS is sent. The code is valid for 10 minutes; click the image to refresh it.',
    '登录暂时锁定':'Sign-in temporarily locked','同一 IP 连续失败达到 5 次，等待后自动解锁。':'This IP reached five consecutive failed attempts. It will unlock automatically after the countdown.','剩余':'Remaining',
    '账户找回设置':'Account recovery','手机号':'Phone number','用于账户找回（当前不发送短信）':'For account recovery (no SMS is sent)','安全问题':'Security question','选择一个安全问题':'Choose a security question',
    '我童年最喜欢的老师姓什么？':'What was the last name of my favorite childhood teacher?','我第一只宠物叫什么？':'What was the name of my first pet?','我最喜欢的城市是哪里？':'What is my favorite city?',
    '安全答案':'Security answer','请使用不容易被猜到的答案':'Choose an answer that is difficult to guess','注册完成后会生成唯一的 Q7-数字 ID，请保存好。以后可用用户名或该 ID 登录。':'A unique Q7 numeric ID is created after registration. Save it; you can sign in with your username or this ID.',
    '创建 Q7VPN 账户':'Create Q7VPN account','登录 Q7VPN':'Sign in to Q7VPN','忘记密码？':'Forgot password?','密码至少 10 位，建议使用独立密码':'Use a unique password of at least 10 characters',
    '加密会话 · 安全防护':'Encrypted session · Security protection','PRIVATE NETWORK · YOUR CONTROL':'PRIVATE NETWORK · YOUR CONTROL','SECURE BY DESIGN':'SECURE BY DESIGN',
    '你好，':'Hello, ','管理你的节点与配置':'Manage your nodes and configurations','账户已登录':'Account signed in','你的网络，':'Your network, ','由你掌控。':'under your control.','集中管理节点配置，清晰、轻量、有序。':'Manage node configurations in one place — clear, light and organized.',
    '管理我的节点':'Manage my nodes','已保存节点':'Saved nodes','条配置':'configurations','连接状态':'Connection status','未连接':'Disconnected','当前为节点管理模式':'Node management mode','导入配置':'Import configuration','从 TXT 批量添加节点':'Add nodes in bulk from TXT',
    '粘贴节点文本':'Paste node text','支持每行一个 ss:// 或 vless:// URI':'One ss://, vless://, vmess:// or trojan:// link per line','在这里粘贴节点 URI…':'Paste node URIs here…','配置可能包含密码或 UUID，请勿分享给陌生人。':'Configurations may contain passwords or UUIDs. Do not share them with strangers.',
    '解析并导入':'Parse and import','节点管理':'Node management','搜索、整理和导出你的配置':'Search, organize and export your configurations','搜索节点名称或协议…':'Search node names or protocols…','导出 TXT':'Export TXT','复制配置':'Copy configuration','发送到客户端 ↗':'Send to client ↗','删除':'Delete','这里还没有节点':'No nodes here yet',
    '先导入一份 TXT 配置，节点会显示在这里。':'Import a TXT configuration and your nodes will appear here.','去导入配置 →':'Import a configuration →','配置管理':'Configuration management','共':'Total','说明：当前版本负责节点配置管理，不会在网页中直接创建 VPN 隧道。连接需交给兼容客户端处理。':'This web console manages node configurations. It does not create a VPN tunnel; connect using a compatible client or the Q7VPN Android app.',
    '来源标识':'Source fingerprint','密码重置':'Password reset','密码修改':'Password changed','登录成功':'Sign-in successful','登录失败':'Sign-in failed','注册成功':'Registration successful','账户注册':'Account registration','登录':'Sign in','注册':'Register','新增':'Added','重复':'Duplicates','无效':'Invalid','条':'items','请先粘贴节点 URI。':'Paste node URIs first.','导入失败，请刷新页面后重试。':'Import failed. Refresh the page and try again.','导入完成：新增':'Import complete: added','确定删除这条节点配置？':'Delete this node configuration?','配置已复制':'Configuration copied','请复制 URI':'Copy the URI','Q7vpn 节点配置':'Q7VPN node configuration','专属 ID 已复制':'Unique ID copied','复制你的专属 ID':'Copy your unique ID',
    '从 TXT 批量添加节点':'Add nodes in bulk from a TXT file','支持每行一个 ss:// 或 vless:// URI':'One ss:// or vless:// URI per line','通过账户信息与安全答案重置密码':'Reset your password using your account information and security answer',
    '账户与安全':'Account & Security','专属身份、密码和账户防护':'Account identity, password and protection','个人中心':'Account','已绑定':'Verified','这是你的专属 ID，可与密码搭配登录':'Your unique ID can be used with your password to sign in','复制 ID ↗':'Copy ID ↗','账户信息':'Account overview','只显示当前登录账户的信息':'Only information for the signed-in account is shown.',
    '用户名':'Username','绑定手机号':'Phone number','注册时间':'Registered','修改密码':'Change password','建议定期更新，并避免与其他网站共用':'Update it regularly and do not reuse passwords from other sites','当前密码':'Current password','输入当前密码':'Enter current password','新密码':'New password','确认新密码':'Confirm new password','再次输入新密码':'Re-enter new password',
    '使用长密码或密码短语；不要把密码发给任何人。':'Use a long password or passphrase. Never send your password to anyone.','保存新密码':'Save new password','账户活动':'Account activity','仅展示与当前账户关联的事件':'Only events associated with this account are shown.','暂无账户活动记录':'No account activity yet','后续登录、密码修改等关联事件会显示在这里。':'Related sign-in and password-change events will appear here.',
    '基础安全提示：请勿向他人提供密码或完整节点配置。账户 ID 是登录标识，不是密码，也不能单独用于登录。':'Security tip: never share your password or full node configurations. Your account ID is a sign-in identifier, not a password, and cannot be used alone.',
    '首页':'Home','节点':'Nodes','导入':'Import','安全中心':'Security center','账户安全':'Account security','退出':'Log out','退出登录':'Log out','复制':'Copy','分享':'Share','账户活动':'Account activity',
    '找回密码':'Recover password','通过账户信息与安全答案重置密码':'Reset your password using your account information and security answer','注册用户名':'Registered username','注册手机号':'Registered phone number','查询安全问题':'Look up security question','安全问题：':'Security question: ','新密码（至少 10 位）':'New password (at least 10 characters)','重置密码':'Reset password','返回登录':'Back to sign in',
    '请求有点快':'You’re going a little too fast','为了保护账户安全，这个操作被临时限制了频率。稍等一会儿再试就好。':'This action has been temporarily rate-limited to protect account security. Please wait a moment and try again.',
    '如果是登录或注册，建议先刷新验证码再提交；找回密码每 5 次／分钟上限。':'For sign-in or registration, refresh the verification code before submitting. Password recovery is limited to five attempts per minute.','返回 Q7VPN':'Return to Q7VPN',
    '该 IP 的登录/注册失败次数过多，请倒计时结束后再试。':'Too many failed sign-in or registration attempts from this IP. Try again when the countdown ends.',
    '图片验证码不正确或已过期，请重新识别。':'The image verification code is incorrect or expired. Please try again.','用户名需为 3–32 位字母、数字、点、下划线或短横线，且不能占用 Q7-数字专属 ID 格式。':'Usernames must be 3–32 characters using letters, numbers, periods, underscores or hyphens, and cannot use the reserved Q7 numeric ID format.',
    '密码至少 10 位，建议使用独立且随机的密码。':'Passwords must be at least 10 characters. Use a unique, randomly generated password.','请输入有效格式的手机号。当前版本不会发送短信。':'Enter a valid phone number. This version does not send SMS messages.','请选择安全问题并填写答案。':'Choose a security question and provide an answer.',
    '注册成功！你的专属 ID 是':'Registration successful! Your unique ID is','请保存好。':'Please save it.','注册未完成：该用户名可能已被使用。':'Registration incomplete: this username may already be in use.','请输入注册用户名或 Q7-数字专属 ID。':'Enter your registered username or Q7 numeric ID.',
    '失败次数已达上限，当前 IP 已锁定 5 分钟。':'The failure limit was reached. This IP is locked for five minutes.','账号 ID / 用户名或密码错误。再失败':'Incorrect account ID, username or password.','次将锁定 5 分钟。':' more failure(s) will lock this IP for five minutes.',
    '请回答注册时设置的安全问题。':'Answer the security question you set during registration.','信息不匹配或账户不存在。':'The information does not match or the account does not exist.','密码已重置，请重新登录。':'Password reset. Please sign in again.','验证失败，或新密码不足 10 位。':'Verification failed or the new password is shorter than 10 characters.',
    '当前密码不正确，密码没有修改。':'The current password is incorrect. Your password was not changed.','新密码至少需要 10 位。':'The new password must be at least 10 characters.','两次输入的新密码不一致。':'The two new passwords do not match.','新密码不能与当前密码相同。':'The new password must differ from the current password.','密码修改成功。请妥善保管新密码。':'Password changed successfully. Keep your new password secure.',
    '安全令牌已失效（页面可能停留过久），请重新提交。':'The security token expired (the page may have been open too long). Please submit again.','已解锁，请刷新页面后重试':'Unlocked. Refresh the page and try again.',
    '输入右侧图片字符':'Enter the characters shown in the image','欢迎使用 Q7VPN':'Welcome to Q7VPN','节点配置':'Node configurations','搜索节点':'Search nodes','粘贴节点 URI':'Paste node URI','开始导入':'Start import','暂时无法连接，请刷新后重试。':'Unable to connect right now. Refresh and try again.'
  };
  const keys = Object.keys(pairs).sort((a,b)=>b.length-a.length);
  const en = () => (localStorage.getItem('q7lang') || 'zh') === 'en';
  function tr(value) {
    if (value == null || value === '') return value;
    let out = String(value);
    for (const zh of keys) {
      const to = en() ? pairs[zh] : zh;
      const from = en() ? zh : pairs[zh];
      if (from && out.includes(from)) out = out.split(from).join(to);
    }
    return out;
  }
  function translateDocument() {
    const lang = en() ? 'en' : 'zh-CN';
    document.documentElement.lang = lang;
    document.title = tr(document.title);
    document.body.classList.toggle('english-ui', en());
    const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, {
      acceptNode(node) {
        const p = node.parentElement;
        if (!p || /^(SCRIPT|STYLE|NOSCRIPT|TEXTAREA)$/i.test(p.tagName) || !node.nodeValue.trim()) return NodeFilter.FILTER_REJECT;
        return NodeFilter.FILTER_ACCEPT;
      }
    });
    const nodes = [];
    while (walker.nextNode()) nodes.push(walker.currentNode);
    nodes.forEach(node => { const next = tr(node.nodeValue); if (next !== node.nodeValue) node.nodeValue = next; });
    document.querySelectorAll('input[placeholder],textarea[placeholder],[aria-label],[title],[alt]').forEach(el => {
      for (const attr of ['placeholder','aria-label','title','alt']) {
        if (el.hasAttribute(attr)) { const old = el.getAttribute(attr), next = tr(old); if (next !== old) el.setAttribute(attr, next); }
      }
    });
    const label = document.getElementById('language-label');
    if (label) label.textContent = en() ? 'EN / 中文' : '中文 / EN';
  }
  window.Q7I18N = { translate: tr, apply: translateDocument };
  window.toggleLanguage = function () {
    localStorage.setItem('q7lang', en() ? 'zh' : 'en');
    translateDocument();
  };
  window.applyLanguage = translateDocument;
  window.translateStatic = translateDocument;
  const oldAlert = window.alert.bind(window);
  const oldConfirm = window.confirm.bind(window);
  const oldPrompt = window.prompt.bind(window);
  window.alert = message => oldAlert(tr(message));
  window.confirm = message => oldConfirm(tr(message));
  window.prompt = (message, value) => oldPrompt(tr(message), value);
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', translateDocument, {once:true});
  else translateDocument();
  const observer = new MutationObserver(records => {
    for (const record of records) {
      if (record.type === 'characterData') { const next = tr(record.target.nodeValue); if (next !== record.target.nodeValue) record.target.nodeValue = next; }
      for (const node of record.addedNodes || []) {
        if (node.nodeType === Node.TEXT_NODE) { const next = tr(node.nodeValue); if (next !== node.nodeValue) node.nodeValue = next; }
        else if (node.nodeType === Node.ELEMENT_NODE) {
          const walker = document.createTreeWalker(node, NodeFilter.SHOW_TEXT);
          const found = []; while (walker.nextNode()) found.push(walker.currentNode);
          found.forEach(textNode => { const next = tr(textNode.nodeValue); if (next !== textNode.nodeValue) textNode.nodeValue = next; });
          if (node.matches && node.matches('[placeholder],[aria-label],[title],[alt]')) {
            for (const attr of ['placeholder','aria-label','title','alt']) if (node.hasAttribute(attr)) { const old = node.getAttribute(attr), next = tr(old); if (next !== old) node.setAttribute(attr,next); }
          }
        }
      }
    }
  });
  observer.observe(document.body, {subtree:true, childList:true, characterData:true});
})();
