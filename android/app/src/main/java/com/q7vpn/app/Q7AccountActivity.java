package com.q7vpn.app;

import android.app.Activity;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.view.Gravity;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Native Q7VPN login, registration, recovery and security UI migrated from the legacy website. */
public final class Q7AccountActivity extends Activity {
    public static final String EXTRA_TARGET = "q7.account.target";
    public static final String TARGET_HOME = "home";
    public static final String TARGET_SECURITY = "security";
    private static final String PREFS = "q7vpn";
    private static final String KEY_THEME = "q7_theme";

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private Q7AccountClient client;
    private String target = TARGET_HOME;
    private String mode = "login";
    private String csrf = "", captchaCode = "", recoveryCsrf = "", recoveryQuestion = "";
    private String noticeText = "";
    private int lockSeconds;
    private boolean busy, lightTheme, sessionAuthenticated;
    private EditText username, password, phone, answer, captcha, newPassword;
    private EditText currentPassword, confirmPassword;
    private Spinner securityQuestion;
    private Q7AccountClient.AccountInfo accountInfo;
    private CountDownTimer lockTimer;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        target = getIntent().getStringExtra(EXTRA_TARGET);
        if (target == null) target = TARGET_HOME;
        lightTheme = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_THEME, false);
        client = new Q7AccountClient(this);
        showLoading();
        worker.execute(() -> {
            boolean active = client.isAuthenticated();
            if (active) {
                sessionAuthenticated = true;
                if (TARGET_SECURITY.equals(target)) {
                    Q7AccountClient.AccountInfo info = client.fetchAccountInfo();
                    runOnUiThread(() -> {
                        if (info == null) { sessionAuthenticated = false; showLoginBootstrap(""); }
                        else renderSecurity(info, "");
                    });
                } else {
                    runOnUiThread(this::returnToHome);
                }
            } else {
                runOnUiThread(() -> showLoginBootstrap(""));
            }
        });
    }

    private boolean english() { return Q7Strings.isEnglish(this); }
    private String tr(String zh, String en) { return english() ? en : zh; }
    private int dp(float v) { return (int) (v * getResources().getDisplayMetrics().density + .5f); }
    private int textColor() { return lightTheme ? Color.rgb(25,33,48) : Color.rgb(244,248,255); }
    private int borderColor() { return lightTheme ? Color.rgb(210,222,236) : Color.argb(88,131,216,236); }

    private GradientDrawable surface(int[] colors, int stroke, int radius) {
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TL_BR, colors);
        d.setCornerRadius(dp(radius));
        if (stroke != 0) d.setStroke(dp(1), stroke);
        return d;
    }
    private TextView text(String value, float size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value); t.setTextSize(size); t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }
    private LinearLayout vertical() {
        LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l;
    }
    private LinearLayout horizontal() {
        LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL); l.setGravity(Gravity.CENTER_VERTICAL); return l;
    }
    private Button button(String value, boolean primary) {
        Button b = new Button(this);
        b.setText(value); b.setAllCaps(false); b.setTextSize(primary ? 14 : 11); b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        int[] gradient = lightTheme
                ? new int[]{Color.rgb(93,175,192),Color.rgb(115,137,220),Color.rgb(155,129,208)}
                : new int[]{Color.rgb(128,237,237),Color.rgb(139,163,255),Color.rgb(194,160,255)};
        b.setTextColor(primary ? Color.rgb(7,20,31) : textColor());
        b.setBackground(primary
                ? surface(gradient, Color.argb(150,230,255,255), 14)
                : surface(new int[]{lightTheme ? Color.WHITE : Color.rgb(25,38,58), lightTheme ? Color.rgb(243,247,254) : Color.rgb(19,29,48)}, borderColor(), 14));
        b.setMinHeight(dp(primary ? 52 : 38)); b.setElevation(dp(primary ? 4 : 1));
        return b;
    }

    private void showLoading() {
        FrameLayoutRoot frame = new FrameLayoutRoot(this);
        LinearLayout center = vertical(); center.setGravity(Gravity.CENTER);
        TextView logo = text("Q7VPN",46,Color.WHITE,true);
        logo.setShadowLayer(dp(24),0,0,Color.rgb(91,222,239)); center.addView(logo);
        TextView caption = text(tr("正在连接账户服务…","Connecting to account service…"),12,Color.rgb(175,195,218),false);
        caption.setPadding(0,dp(14),0,0); center.addView(caption);
        frame.addView(center,new android.widget.FrameLayout.LayoutParams(-1,-1)); setContentView(frame);
    }
    private void showLoginBootstrap(String notice) {
        worker.execute(() -> {
            Q7AccountClient.Bootstrap b = client.bootstrap("login");
            runOnUiThread(() -> renderAuth("login", notice == null || notice.isEmpty() ? b.message : notice, b));
        });
    }

    private void renderAuth(String requestedMode, String notice, Q7AccountClient.Bootstrap bootstrap) {
        mode = requestedMode;
        if (bootstrap != null) {
            csrf = bootstrap.csrf == null ? "" : bootstrap.csrf;
            captchaCode = bootstrap.captchaCode == null ? "" : bootstrap.captchaCode;
            lockSeconds = bootstrap.lockSeconds;
            if (bootstrap.message != null && !bootstrap.message.isEmpty()) notice = bootstrap.message;
        }
        noticeText = notice == null ? "" : notice;
        getWindow().setStatusBarColor(Color.rgb(6,10,19));
        getWindow().setNavigationBarColor(Color.rgb(6,10,19));
        if (Build.VERSION.SDK_INT >= 23) getWindow().getDecorView().setSystemUiVisibility(0);

        LinearLayout page = vertical(); page.setPadding(dp(18),dp(16),dp(18),dp(25));
        LinearLayout top = horizontal();
        TextView brand = text("Q7VPN",27,Color.WHITE,true);
        brand.setShadowLayer(dp(17),0,0,Color.rgb(88,224,238));
        top.addView(brand,new LinearLayout.LayoutParams(0,dp(42),1));
        Button language = button(tr("EN","中文"),false);
        language.setOnClickListener(v -> { Q7Strings.setEnglish(this,!english()); renderAuth(mode,noticeText,null); });
        top.addView(language,new LinearLayout.LayoutParams(-2,dp(38)));
        Button theme = button(lightTheme ? "◐" : "◑",false);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(dp(43),dp(38)); tp.setMargins(dp(7),0,0,0);
        theme.setOnClickListener(v -> {
            lightTheme = !lightTheme;
            getSharedPreferences(PREFS,MODE_PRIVATE).edit().putBoolean(KEY_THEME,lightTheme).apply();
            renderAuth(mode,noticeText,null);
        });
        top.addView(theme,tp); page.addView(top); space(page,31);

        TextView kicker = text("PRIVATE NETWORK · YOUR CONTROL",9,Color.rgb(127,227,238),true);
        kicker.setGravity(Gravity.CENTER); kicker.setLetterSpacing(.18f); page.addView(kicker);
        TextView word = text("Q7VPN",64,Color.WHITE,true);
        word.setGravity(Gravity.CENTER); word.setLetterSpacing(-.055f);
        word.setShadowLayer(dp(26),0,0,Color.rgb(90,219,237)); page.addView(word);

        String title = "register".equals(mode) ? tr("创建你的账户","Create your account")
                : ("recover_lookup".equals(mode) || "recover_reset".equals(mode)) ? tr("找回密码","Recover password")
                : tr("欢迎回来","Welcome back");
        String intro = "register".equals(mode)
                ? tr("建立你的专属 Q7VPN 身份，安全管理节点配置。","Create your Q7VPN identity and manage configurations securely.")
                : tr("登录后继续管理你的专属网络配置。","Sign in to manage your private network configurations.");
        TextView heading = text(title,25,Color.WHITE,true); heading.setGravity(Gravity.CENTER); heading.setPadding(0,dp(10),0,dp(4)); page.addView(heading);
        TextView subtitle = text(intro,11,Color.rgb(169,190,213),false); subtitle.setGravity(Gravity.CENTER); page.addView(subtitle); space(page,23);

        if ("login".equals(mode) || "register".equals(mode)) {
            LinearLayout tabs = horizontal(); tabs.setPadding(dp(4),dp(4),dp(4),dp(4));
            tabs.setBackground(surface(new int[]{Color.argb(210,8,16,28),Color.argb(210,18,22,42)},borderColor(),15));
            Button login = button(tr("登录账户","Sign in"),"login".equals(mode));
            login.setOnClickListener(v -> renderAuth("login","",null));
            Button register = button(tr("创建账户","Create account"),"register".equals(mode));
            register.setOnClickListener(v -> renderAuth("register","",null));
            tabs.addView(login,new LinearLayout.LayoutParams(0,dp(42),1));
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0,dp(42),1); rp.setMargins(dp(5),0,0,0); tabs.addView(register,rp);
            page.addView(tabs); space(page,12);
        }

        LinearLayout card = vertical(); card.setPadding(dp(17),dp(18),dp(17),dp(18));
        card.setBackground(surface(new int[]{Color.argb(242,24,43,62),Color.argb(244,11,18,32),Color.argb(241,25,22,45)},Color.argb(105,130,227,239),23));
        card.setElevation(dp(9));

        if ("login".equals(mode) || "register".equals(mode)) {
            username = field(card,tr("用户名","Username"),"register".equals(mode) ? tr("例如 q7_user","e.g. q7_user") : tr("用户名或 Q7-0000001","Username or Q7-0000001"),android.text.InputType.TYPE_CLASS_TEXT);
            password = field(card,tr("登录密码","Password"),tr("至少 10 位","At least 10 characters"),android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
            if ("register".equals(mode)) {
                phone = field(card,tr("手机号","Phone number"),tr("用于账户找回（不发送短信）","For account recovery (no SMS)"),android.text.InputType.TYPE_CLASS_PHONE);
                label(card,tr("安全问题","Security question"));
                securityQuestion = new Spinner(this);
                String[] options = english()
                        ? new String[]{"What was your favorite teacher's last name?","What was your first pet's name?","What is your favorite city?"}
                        : new String[]{"我童年最喜欢的老师姓什么？","我第一只宠物叫什么？","我最喜欢的城市是哪里？"};
                ArrayAdapter<String> adapter = new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,options);
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); securityQuestion.setAdapter(adapter);
                securityQuestion.setBackground(surface(new int[]{Color.rgb(10,18,31),Color.rgb(16,23,39)},borderColor(),12));
                LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1,dp(47)); sp.setMargins(0,0,0,dp(12)); card.addView(securityQuestion,sp);
                answer = field(card,tr("安全答案","Security answer"),tr("请使用不容易被猜到的答案","Choose a hard-to-guess answer"),android.text.InputType.TYPE_CLASS_TEXT);
            }
            label(card,tr("图片验证码","Image verification code"));
            LinearLayout captchaRow = horizontal();
            captcha = new EditText(this); captcha.setSingleLine(true); captcha.setTextSize(14); captcha.setTextColor(Color.WHITE);
            captcha.setHintTextColor(Color.rgb(133,151,173)); captcha.setHint(tr("输入右侧 4 位验证码","Enter the 4-character code"));
            captcha.setLetterSpacing(.12f); captcha.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
            captcha.setPadding(dp(13),dp(9),dp(13),dp(9)); captcha.setBackground(surface(new int[]{Color.rgb(8,16,29),Color.rgb(13,23,38)},borderColor(),12));
            captchaRow.addView(captcha,new LinearLayout.LayoutParams(0,dp(52),1));
            CaptchaView image = new CaptchaView(this); image.setCode(captchaCode);
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(dp(132),dp(52)); cp.setMargins(dp(8),0,0,0);
            captchaRow.addView(image,cp); image.setOnClickListener(v -> refreshCaptcha()); card.addView(captchaRow);
            TextView hint = text(tr("验证码有效期 10 分钟，点击图片可更换。","Valid for 10 minutes. Tap image to refresh."),9,Color.rgb(147,166,190),false);
            hint.setPadding(0,dp(6),0,dp(10)); card.addView(hint);
            Button submit = button("register".equals(mode)?tr("创建 Q7VPN 账户","Create Q7VPN account"):tr("登录 Q7VPN","Sign in to Q7VPN"),true);
            submit.setTextSize(15); submit.setOnClickListener(v -> submitAuth()); card.addView(submit,new LinearLayout.LayoutParams(-1,dp(53)));
            if (lockSeconds > 0) {
                submit.setEnabled(false); TextView lock = text("",10,Color.rgb(255,194,143),false); lock.setPadding(0,dp(9),0,0); card.addView(lock); startLockTimer(lock,lockSeconds,submit);
            }
            if ("login".equals(mode)) {
                TextView forgot = text(tr("忘记密码？","Forgot password?"),11,Color.rgb(120,223,237),true);
                forgot.setPadding(0,dp(13),0,0); forgot.setOnClickListener(v -> renderAuth("recover_lookup","",null)); card.addView(forgot);
            }
        } else if ("recover_lookup".equals(mode)) {
            username = field(card,tr("注册用户名","Registered username"),tr("输入注册用户名","Enter username"),android.text.InputType.TYPE_CLASS_TEXT);
            phone = field(card,tr("注册手机号","Registered phone number"),tr("输入注册手机号","Enter phone number"),android.text.InputType.TYPE_CLASS_PHONE);
            Button lookup = button(tr("查询安全问题","Find security question"),true); lookup.setOnClickListener(v -> lookupRecovery()); card.addView(lookup,new LinearLayout.LayoutParams(-1,dp(50)));
        } else {
            TextView q = text(recoveryQuestion,12,Color.rgb(195,221,236),true); q.setPadding(0,0,0,dp(10)); card.addView(q);
            answer = field(card,tr("安全答案","Security answer"),tr("输入安全答案","Enter security answer"),android.text.InputType.TYPE_CLASS_TEXT);
            newPassword = field(card,tr("新密码","New password"),tr("至少 10 位","At least 10 characters"),android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
            Button reset = button(tr("重置密码","Reset password"),true); reset.setOnClickListener(v -> resetPassword()); card.addView(reset,new LinearLayout.LayoutParams(-1,dp(50)));
        }

        if (!noticeText.isEmpty()) {
            TextView noticeView = text(translateMessage(noticeText), 10,
                    noticeText.contains("成功")
                            ? Color.rgb(139, 235, 202)
                            : Color.rgb(255, 183, 193), false);
            noticeView.setPadding(dp(2), dp(12), dp(2), 0);
            card.addView(noticeView);
        }
        page.addView(card); space(page,18);
        TextView footer = text("Q7VPN · PRIVATE NETWORK · SECURE BY DESIGN",8,Color.rgb(111,137,164),true);
        footer.setGravity(Gravity.CENTER); page.addView(footer);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setClipToPadding(false); scroll.addView(page);
        android.widget.FrameLayout frame = new android.widget.FrameLayout(this);
        frame.addView(new FlowBackground(this),new android.widget.FrameLayout.LayoutParams(-1,-1));
        frame.addView(scroll,new android.widget.FrameLayout.LayoutParams(-1,-1)); setContentView(frame);
    }

    private void label(LinearLayout parent,String value) { TextView l=text(value,10,Color.rgb(165,190,208),true); l.setPadding(dp(1),dp(3),0,dp(6)); parent.addView(l); }
    private EditText field(LinearLayout parent,String title,String hint,int inputType) {
        label(parent,title); EditText e=new EditText(this); e.setSingleLine(true); e.setTextSize(13); e.setTextColor(Color.WHITE);
        e.setHintTextColor(Color.rgb(123,145,169)); e.setHint(hint); e.setInputType(inputType); e.setPadding(dp(13),dp(8),dp(13),dp(8));
        e.setBackground(surface(new int[]{Color.rgb(8,16,29),Color.rgb(14,24,40)},Color.argb(80,131,213,234),12));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(47)); lp.setMargins(0,0,0,dp(11)); parent.addView(e,lp); return e;
    }
    private String value(EditText e) { return e==null||e.getText()==null?"":e.getText().toString().trim(); }
    private void space(LinearLayout p,int n) { View v=new View(this); p.addView(v,new LinearLayout.LayoutParams(1,dp(n))); }

    private void refreshCaptcha() {
        if(busy)return; busy=true;
        worker.execute(()->{Q7AccountClient.Bootstrap b=client.bootstrap("register".equals(mode)?"register":"login"); runOnUiThread(()->{busy=false;renderAuth(mode,b.message,b);});});
    }
    private void submitAuth() {
        if(busy)return;
        String question="";
        if(securityQuestion!=null&&securityQuestion.getSelectedItemPosition()>=0){
            String[] q={"我童年最喜欢的老师姓什么？","我第一只宠物叫什么？","我最喜欢的城市是哪里？"};
            question=q[Math.min(securityQuestion.getSelectedItemPosition(),q.length-1)];
        }
        String submittedMode=mode, selectedQuestion=question; busy=true;
        worker.execute(()->{
            Q7AccountClient.AuthResult r=client.submitAuth(submittedMode,value(username),value(password),value(captcha).toUpperCase(Locale.ROOT),value(phone),selectedQuestion,value(answer),csrf);
            runOnUiThread(()->{
                busy=false;
                if(r.loggedIn){sessionAuthenticated=true;if(TARGET_SECURITY.equals(target))loadSecurity();else returnToHome();}
                else if(r.registered)renderAuth("login",r.message,r.nextBootstrap);
                else renderAuth(submittedMode,r.message,r.nextBootstrap);
            });
        });
    }
    private void lookupRecovery() {
        if(busy)return; busy=true;
        worker.execute(()->{
            Q7AccountClient.RecoveryResult r=client.beginRecovery(value(username),value(phone));
            runOnUiThread(()->{busy=false;if(!r.question.isEmpty()){recoveryQuestion=r.question;recoveryCsrf=r.csrf;renderAuth("recover_reset",r.message,null);}else renderAuth("recover_lookup",r.message,null);});
        });
    }
    private void resetPassword() {
        if(busy)return; busy=true;
        worker.execute(()->{
            Q7AccountClient.RecoveryResult r=client.completeRecovery(value(answer),value(newPassword),recoveryCsrf);
            Q7AccountClient.Bootstrap b=r.success?client.bootstrap("login"):null;
            runOnUiThread(()->{busy=false;if(r.success)renderAuth("login",r.message,b);else renderAuth("recover_reset",r.message,null);});
        });
    }

    private void loadSecurity() {
        worker.execute(()->{
            Q7AccountClient.AccountInfo info=client.fetchAccountInfo();
            runOnUiThread(()->{if(info==null){client.clearSession();sessionAuthenticated=false;renderAuth("login",tr("账户信息加载失败，请重新登录。","Could not load account. Please sign in again."),null);}else{accountInfo=info;renderSecurity(info,"");}});
        });
    }
    private void renderSecurity(Q7AccountClient.AccountInfo info,String message) {
        accountInfo=info;
        LinearLayout page=vertical(); page.setPadding(dp(18),dp(18),dp(18),dp(24));
        LinearLayout top=horizontal(); TextView brand=text("Q7VPN",26,Color.WHITE,true); brand.setShadowLayer(dp(15),0,0,Color.rgb(88,222,237));
        top.addView(brand,new LinearLayout.LayoutParams(0,dp(43),1));
        Button back=button(tr("返回","Back"),false); back.setOnClickListener(v->finish()); top.addView(back); page.addView(top); space(page,24);
        page.addView(text(tr("账户与安全","Account & Security"),26,Color.WHITE,true));
        TextView sub=text(tr("专属身份、密码和账户防护","Your identity, password and account protection"),11,Color.rgb(171,190,211),false);
        sub.setPadding(0,dp(6),0,dp(17)); page.addView(sub);
        LinearLayout details=vertical(); details.setPadding(dp(16),dp(16),dp(16),dp(16));
        details.setBackground(surface(new int[]{Color.argb(239,25,49,65),Color.argb(238,20,24,48)},Color.argb(100,124,223,239),20));
        addInfo(details,tr("专属 ID","Account ID"),info.accountId); addInfo(details,tr("用户名","Username"),info.username);
        addInfo(details,tr("绑定手机号","Phone number"),info.phone); addInfo(details,tr("已保存节点","Saved nodes"),info.nodeCount); page.addView(details); space(page,18);
        LinearLayout passwordCard=vertical(); passwordCard.setPadding(dp(16),dp(16),dp(16),dp(16));
        passwordCard.setBackground(surface(new int[]{Color.argb(236,19,31,49),Color.argb(236,13,21,37)},Color.argb(80,123,213,232),19));
        passwordCard.addView(text(tr("修改密码","Change password"),18,Color.WHITE,true));
        currentPassword=field(passwordCard,tr("当前密码","Current password"),tr("输入当前密码","Enter current password"),android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        newPassword=field(passwordCard,tr("新密码","New password"),tr("至少 10 位","At least 10 characters"),android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        confirmPassword=field(passwordCard,tr("确认新密码","Confirm new password"),tr("再次输入新密码","Re-enter new password"),android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        Button save=button(tr("保存新密码","Save password"),true); save.setOnClickListener(v->changePassword()); passwordCard.addView(save,new LinearLayout.LayoutParams(-1,dp(49))); page.addView(passwordCard);
        if(!message.isEmpty()){TextView n=text(translateMessage(message),10,Color.rgb(138,235,203),false);n.setPadding(0,dp(10),0,0);page.addView(n);}
        space(page,16); Button signout=button(tr("退出登录","Sign out"),false); signout.setOnClickListener(v->logout()); page.addView(signout,new LinearLayout.LayoutParams(-1,dp(47)));
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.addView(page);
        android.widget.FrameLayout frame=new android.widget.FrameLayout(this);frame.addView(new FlowBackground(this),new android.widget.FrameLayout.LayoutParams(-1,-1));frame.addView(scroll,new android.widget.FrameLayout.LayoutParams(-1,-1));setContentView(frame);
    }
    private void addInfo(LinearLayout parent,String key,String value){
        LinearLayout row=horizontal();TextView k=text(key,11,Color.rgb(156,178,199),false);TextView v=text(value==null?"—":value,12,Color.WHITE,true);v.setGravity(Gravity.RIGHT);
        row.addView(k,new LinearLayout.LayoutParams(0,dp(36),1));row.addView(v);parent.addView(row);
    }
    private void changePassword(){
        String old=value(currentPassword),next=value(newPassword),confirm=value(confirmPassword);busy=true;
        worker.execute(()->{
            String msg=client.changePassword(accountInfo==null?"":accountInfo.csrf,old,next,confirm);Q7AccountClient.AccountInfo fresh=client.fetchAccountInfo();
            runOnUiThread(()->{busy=false;if(fresh!=null){accountInfo=fresh;renderSecurity(fresh,msg);}else Toast.makeText(this,translateMessage(msg),Toast.LENGTH_LONG).show();});
        });
    }
    private void logout(){
        busy=true;worker.execute(()->{
            client.logout();sessionAuthenticated=false;Q7AccountClient.Bootstrap b=client.bootstrap("login");
            runOnUiThread(()->{busy=false;target=TARGET_HOME;renderAuth("login",tr("已安全退出账户。","You have signed out."),b);});
        });
    }
    private void startLockTimer(TextView view,int seconds,Button submit){
        if(lockTimer!=null)lockTimer.cancel();
        lockTimer=new CountDownTimer(seconds*1000L,1000L){
            public void onTick(long ms){long n=ms/1000L;view.setText(tr("该 IP 暂时锁定：","This IP is locked:")+String.format(Locale.ROOT,"%d:%02d",n/60,n%60));}
            public void onFinish(){view.setText(tr("锁定已结束，请刷新验证码。","Lock expired. Refresh the CAPTCHA."));submit.setEnabled(true);}
        }.start();
    }
    private String translateMessage(String msg){
        if(!english()||msg==null)return msg==null?"":msg;
        String[][] p={{"注册成功！","Registration successful. "},{"图片验证码不正确或已过期，请重新识别。","The image code is incorrect or expired. Please try again."},
        {"该 IP 的登录/注册失败次数过多，请倒计时结束后再试。","Too many failed attempts from this IP. Try again after the lock expires."},
        {"失败次数已达上限，当前 IP 已锁定 5 分钟。","The IP has been locked for 5 minutes after repeated failures."},
        {"账号 ID / 用户名或密码错误。","Account ID, username or password is incorrect."},{"请回答注册时设置的安全问题。","Please answer the registered security question."},
        {"密码已重置，请重新登录。","Password reset. Please sign in again."},{"当前密码不正确，密码没有修改。","Current password is incorrect. Password was not changed."},{"密码修改成功。","Password changed successfully."}};
        String out=msg;for(String[] pair:p)out=out.replace(pair[0],pair[1]);return out;
    }
    private void returnToHome(){setResult(RESULT_OK);finish();}

    @Override public void onBackPressed(){
        if("register".equals(mode)||"recover_lookup".equals(mode)||"recover_reset".equals(mode)){
            if (busy) return;
            busy=true;
            worker.execute(()->{
                Q7AccountClient.Bootstrap b=client.bootstrap("login");
                runOnUiThread(()->{busy=false;renderAuth("login","",b);});
            });
            return;
        }
        if(TARGET_SECURITY.equals(target)){if(sessionAuthenticated)setResult(RESULT_OK);finish();return;}
        super.onBackPressed();
    }
    @Override protected void onDestroy(){if(lockTimer!=null)lockTimer.cancel();worker.shutdownNow();super.onDestroy();}

    private static final class FrameLayoutRoot extends android.widget.FrameLayout{
        FrameLayoutRoot(Context c){super(c);setBackgroundColor(Color.rgb(7,11,21));}
    }
    private final class FlowBackground extends View{
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);private ValueAnimator animator;private float phase;
        FlowBackground(Context c){super(c);setLayerType(View.LAYER_TYPE_HARDWARE,null);}
        @Override protected void onAttachedToWindow(){super.onAttachedToWindow();if(animator==null){animator=ValueAnimator.ofFloat(0f,(float)(Math.PI*2));animator.setDuration(22000L);animator.setRepeatCount(ValueAnimator.INFINITE);animator.setInterpolator(new LinearInterpolator());animator.addUpdateListener(a->{phase=(float)a.getAnimatedValue();postInvalidateOnAnimation();});}animator.start();}
        @Override protected void onDetachedFromWindow(){if(animator!=null)animator.cancel();super.onDetachedFromWindow();}
        @Override protected void onDraw(Canvas c){
            super.onDraw(c);int w=getWidth(),h=getHeight();c.drawColor(lightTheme?Color.rgb(239,245,252):Color.rgb(6,10,20));float r=Math.max(w,h)*.66f;
            float x1=w*(.15f+.1f*(float)Math.sin(phase)),y1=h*(.2f+.08f*(float)Math.cos(phase*.8f));
            paint.setShader(new RadialGradient(x1,y1,r,new int[]{lightTheme?0x5581D8E8:0x5550DCEB,0x004F79B8},null,Shader.TileMode.CLAMP));c.drawCircle(x1,y1,r,paint);
            float x2=w*(.86f-.11f*(float)Math.sin(phase*.72f+1.4f)),y2=h*(.68f+.1f*(float)Math.cos(phase*.6f));
            paint.setShader(new RadialGradient(x2,y2,r*.8f,new int[]{lightTheme?0x44B2A8F4:0x4D8167E8,0x001A2040},null,Shader.TileMode.CLAMP));c.drawCircle(x2,y2,r*.8f,paint);paint.setShader(null);
        }
    }
    private static final class CaptchaView extends View{
        private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);private String code="";
        CaptchaView(Context c){super(c);setLayerType(View.LAYER_TYPE_HARDWARE,null);}
        void setCode(String value){code=value==null?"":value;invalidate();}
        @Override protected void onDraw(Canvas c){
            super.onDraw(c);float w=getWidth(),h=getHeight();if(w<=0||h<=0)return;
            p.setShader(new android.graphics.LinearGradient(0,0,w,h,new int[]{Color.rgb(20,42,59),Color.rgb(38,31,66),Color.rgb(18,51,61)},null,Shader.TileMode.CLAMP));
            c.drawRoundRect(new android.graphics.RectF(0,0,w,h),11,11,p);p.setShader(null);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1);
            for(int i=0;i<7;i++){p.setColor(Color.argb(70,112,190,224));android.graphics.Path q=new android.graphics.Path();float x=w*(i+1)/8f;q.moveTo(x-13,(i*13f)%h);q.lineTo(x+19,(i*23f+8)%h);c.drawPath(q,p);}
            p.setStyle(Paint.Style.FILL);p.setTextAlign(Paint.Align.CENTER);p.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));p.setTextSize(Math.min(25,h*.55f));
            int[] colors={Color.rgb(232,251,255),Color.rgb(169,243,240),Color.rgb(201,186,255),Color.rgb(220,240,255)};
            for(int i=0;i<Math.min(4,code.length());i++){float x=w*(i+.5f)/4f,y=h*.68f;c.save();c.rotate(i%2==0?-5:6,x,y);p.setColor(colors[i]);p.setShadowLayer(4,0,0,Color.argb(120,95,225,241));c.drawText(String.valueOf(code.charAt(i)),x,y,p);p.clearShadowLayer();c.restore();}
        }
    }
}
