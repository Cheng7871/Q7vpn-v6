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
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;
import android.text.method.PasswordTransformationMethod;
import android.text.method.HideReturnsTransformationMethod;
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
    private String csrf = "", captchaCode = "", recoveryCsrf = "", recoveryToken = "", recoveryAccountId = "", recoveryUsername = "", recoveryPhone = "";
    private String noticeText = "";
    private int lockSeconds;
    private boolean busy, lightTheme, sessionAuthenticated;
    private EditText username, password, phone, captcha, confirmPassword, newPassword, currentPassword, recoveryCode;
    private TextView usernameError, passwordError, phoneError, confirmError, captchaError;
    private CaptchaView captchaImageView;
    private Q7AccountClient.AccountInfo accountInfo;
    private CountDownTimer lockTimer, recoveryTimer;
    private long recoveryExpiresAtMillis;

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
        if (recoveryTimer != null) { recoveryTimer.cancel(); recoveryTimer = null; }
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

        // Shared card container for login, registration and password recovery forms.
        // Keep this declaration before any field is added to the card.
        LinearLayout card = vertical();
        card.setPadding(dp(17), dp(18), dp(17), dp(18));
        card.setBackground(surface(
                new int[]{Color.argb(242,24,43,62), Color.argb(244,11,18,32), Color.argb(241,25,22,45)},
                Color.argb(105,130,227,239), 23));
        card.setElevation(dp(9));

        if ("login".equals(mode) || "register".equals(mode)) {
            boolean registering = "register".equals(mode);
            username = field(card, registering ? tr("用户名","Username") : tr("用户名 / 专属 ID","Username / Account ID"),
                    registering ? tr("3–8 位文字或字母","3–8 characters or letters") : tr("用户名或 Q7-0000001","Username or Q7-0000001"),
                    android.text.InputType.TYPE_CLASS_TEXT);
            usernameError = fieldError(card);
            password = passwordField(card, registering ? tr("设置密码","Create password") : tr("登录密码","Password"),
                    registering ? tr("8–12 位","8–12 characters") : tr("输入登录密码","Enter password"));
            passwordError = fieldError(card);
            if (registering) {
                confirmPassword = passwordField(card,tr("确认密码","Confirm password"),tr("再次输入密码","Re-enter password"));
                confirmError = fieldError(card);
                phone = field(card,tr("手机号","Phone number"),tr("用于账户找回（不发送短信）","For account recovery (no SMS)"),android.text.InputType.TYPE_CLASS_PHONE);
                phoneError = fieldError(card);
            }
            label(card,tr("图片验证码","Image verification code"));
            LinearLayout captchaRow = horizontal();
            captcha = new EditText(this); captcha.setSingleLine(true); captcha.setTextSize(14); captcha.setTextColor(Color.WHITE);
            captcha.setHintTextColor(Color.rgb(133,151,173)); captcha.setHint(tr("输入右侧 4 位验证码","Enter the 4-character code"));
            captcha.setLetterSpacing(.12f); captcha.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
            captcha.setPadding(dp(13),dp(9),dp(13),dp(9)); captcha.setBackground(surface(new int[]{Color.rgb(8,16,29),Color.rgb(13,23,38)},borderColor(),12));
            captchaRow.addView(captcha,new LinearLayout.LayoutParams(0,dp(52),1));
            captchaImageView = new CaptchaView(this); captchaImageView.setCode(captchaCode);
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(dp(132),dp(52)); cp.setMargins(dp(8),0,0,0);
            captchaRow.addView(captchaImageView,cp); captchaImageView.setOnClickListener(v -> refreshCaptcha()); card.addView(captchaRow);
            captchaError = fieldError(card);
            Button refresh = button(tr("换一批","Refresh codes"),false);
            refresh.setTextSize(10); refresh.setOnClickListener(v -> refreshCaptcha());
            LinearLayout.LayoutParams refreshParams = new LinearLayout.LayoutParams(-2,dp(34)); refreshParams.setMargins(0,dp(6),0,0); card.addView(refresh,refreshParams);
            TextView hint = text(tr("图片验证码有效期 10 分钟。","Image code is valid for 10 minutes."),9,Color.rgb(147,166,190),false);
            hint.setPadding(0,dp(4),0,dp(10)); card.addView(hint);
            Button submit = button("register".equals(mode)?tr("创建 Q7VPN 账户","Create Q7VPN account"):tr("登录 Q7VPN","Sign in to Q7VPN"),true);
            submit.setTextSize(15); submit.setOnClickListener(v -> submitAuth()); card.addView(submit,new LinearLayout.LayoutParams(-1,dp(53)));
            if (lockSeconds > 0) {
                submit.setEnabled(false); TextView lock = text("",10,Color.rgb(255,194,143),false); lock.setPadding(0,dp(9),0,0); card.addView(lock); startLockTimer(lock,lockSeconds,submit);
            }
            if ("login".equals(mode)) {
                TextView forgot = text(tr("忘记密码？","Forgot password?"),11,Color.rgb(120,223,237),true);
                forgot.setPadding(0,dp(13),0,0); forgot.setOnClickListener(v -> openRecovery()); card.addView(forgot);
            }
        } else if ("recover_lookup".equals(mode)) {
            username = field(card,tr("用户名 / 专属 ID","Username / Account ID"),tr("输入用户名或 Q7-0000001","Enter username or Q7 ID"),android.text.InputType.TYPE_CLASS_TEXT);
            phone = field(card,tr("注册手机号","Registered phone number"),tr("必须与注册时填写的一致","Must match your registered phone"),android.text.InputType.TYPE_CLASS_PHONE);
            label(card,tr("图片验证码","Image verification code"));
            LinearLayout captchaRow = horizontal();
            captcha = fieldWithoutLabel(tr("输入右侧 4 位验证码","Enter the 4-character code"));
            captchaRow.addView(captcha,new LinearLayout.LayoutParams(0,dp(52),1));
            captchaImageView = new CaptchaView(this); captchaImageView.setCode(captchaCode);
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(dp(132),dp(52)); cp.setMargins(dp(8),0,0,0);
            captchaRow.addView(captchaImageView,cp); captchaImageView.setOnClickListener(v -> refreshCaptcha()); card.addView(captchaRow);
            captchaError = fieldError(card);
            Button refresh = button(tr("换一批","Refresh codes"),false); refresh.setTextSize(10); refresh.setOnClickListener(v -> refreshCaptcha());
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-2,dp(34)); rlp.setMargins(0,dp(6),0,dp(8)); card.addView(refresh,rlp);
            TextView securityHint = text(tr("注意：图片验证码只用于拦截自动化请求，不验证手机号归属。知道账户 ID 和注册手机号的人可能重置密码。","Note: the image code only blocks automated requests; it does not prove phone ownership. Someone who knows both your account ID and registered phone may reset the password."),9,Color.rgb(255,190,155),false);
            securityHint.setPadding(0,dp(7),0,dp(12)); card.addView(securityHint);
            Button lookup = button(tr("生成 5 分钟恢复凭证","Generate 5-minute recovery pass"),true); lookup.setOnClickListener(v -> lookupRecovery()); card.addView(lookup,new LinearLayout.LayoutParams(-1,dp(50)));
        } else {
            TextView bill = text(tr("恢复账户凭证","Account recovery pass"),18,Color.WHITE,true); card.addView(bill);
            TextView summary = text(recoveryAccountId+"  ·  @"+recoveryUsername+"\n"+maskPhone(recoveryPhone)+"\n"+tr("临时密码有效期：5 分钟","Temporary password valid for 5 minutes"),11,Color.rgb(190,214,231),false);
            summary.setPadding(0,dp(10),0,dp(12)); card.addView(summary);
            TextView pass = text(recoveryToken,18,Color.rgb(144,240,231),true); pass.setTextIsSelectable(true); card.addView(pass);
            recoveryCode = field(card,tr("临时恢复密码","Temporary recovery password"),tr("输入上方临时密码","Enter the temporary password above"),android.text.InputType.TYPE_CLASS_TEXT);
            recoveryCode.setText(recoveryToken);
            newPassword = passwordField(card,tr("新密码","New password"),tr("8–12 位","8–12 characters"));
            confirmPassword = passwordField(card,tr("确认新密码","Confirm new password"),tr("再次输入新密码","Re-enter new password"));
            Button reset = button(tr("确认修改密码","Confirm password change"),true); reset.setOnClickListener(v -> resetPassword()); card.addView(reset,new LinearLayout.LayoutParams(-1,dp(50)));
            TextView expiry = text("",10,Color.rgb(255,183,193),false);
            expiry.setPadding(0,dp(10),0,0); card.addView(expiry);
            startRecoveryTimer(expiry);
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
    private EditText fieldWithoutLabel(String hint) {
        EditText e=new EditText(this); e.setSingleLine(true); e.setTextSize(13); e.setTextColor(Color.WHITE);
        e.setHintTextColor(Color.rgb(123,145,169)); e.setHint(hint); e.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        e.setPadding(dp(13),dp(8),dp(13),dp(8));
        e.setBackground(surface(new int[]{Color.rgb(8,16,29),Color.rgb(14,24,40)},Color.argb(80,131,213,234),12)); return e;
    }
    private TextView fieldError(LinearLayout parent) {
        TextView t=text("",10,Color.rgb(255,105,125),false); t.setPadding(dp(2),0,dp(2),dp(7)); t.setVisibility(View.GONE); parent.addView(t); return t;
    }
    private void showError(TextView view,String message) {
        if(view==null)return; view.setText(message==null?"":message); view.setVisibility(message==null||message.isEmpty()?View.GONE:View.VISIBLE);
    }
    private EditText passwordField(LinearLayout parent,String title,String hint) {
        label(parent,title); LinearLayout row=horizontal(); EditText e=new EditText(this);
        e.setSingleLine(true); e.setTextSize(13); e.setTextColor(Color.WHITE); e.setHintTextColor(Color.rgb(123,145,169));
        e.setHint(hint); e.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD); e.setPadding(dp(13),dp(8),dp(8),dp(8));
        e.setBackground(surface(new int[]{Color.rgb(8,16,29),Color.rgb(14,24,40)},Color.argb(80,131,213,234),12));
        row.addView(e,new LinearLayout.LayoutParams(0,dp(47),1)); Button eye=button("◉",false); eye.setTextSize(15);
        LinearLayout.LayoutParams ep=new LinearLayout.LayoutParams(dp(44),dp(47)); ep.setMargins(dp(6),0,0,0); row.addView(eye,ep);
        eye.setOnClickListener(v->{int pos=e.getSelectionStart();boolean hidden=e.getTransformationMethod() instanceof PasswordTransformationMethod;
            e.setTransformationMethod(hidden?HideReturnsTransformationMethod.getInstance():PasswordTransformationMethod.getInstance()); eye.setText(hidden?"◎":"◉"); if(pos>=0)e.setSelection(Math.min(pos,e.length()));});
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2); lp.setMargins(0,0,0,dp(11)); parent.addView(row,lp); return e;
    }
    private String maskPhone(String value) { if(value==null||value.length()<7)return value==null?"":value; return value.substring(0,3)+"****"+value.substring(value.length()-4); }
    private String value(EditText e) { return e==null||e.getText()==null?"":e.getText().toString().trim(); }
    private void space(LinearLayout p,int n) { View v=new View(this); p.addView(v,new LinearLayout.LayoutParams(1,dp(n))); }

    private void refreshCaptcha() {
        if(busy)return; busy=true; final String currentMode=mode;
        worker.execute(()->{Q7AccountClient.Bootstrap b=client.bootstrap("register".equals(currentMode)?"register":"login"); runOnUiThread(()->{
            busy=false; csrf=b.csrf; captchaCode=b.captchaCode; lockSeconds=b.lockSeconds;
            if(captchaImageView!=null)captchaImageView.setCode(captchaCode);
            if(captcha!=null)captcha.setText(""); showError(captchaError,"");
            if(!b.message.isEmpty())Toast.makeText(this,translateMessage(b.message),Toast.LENGTH_SHORT).show();
        });});
    }
    private void submitAuth() {
        if(busy)return;
        String u=value(username), p=value(password), ph=value(phone), cp=value(confirmPassword), cap=value(captcha).toUpperCase(Locale.ROOT);
        showError(usernameError,""); showError(passwordError,""); showError(phoneError,""); showError(confirmError,""); showError(captchaError,"");
        boolean registering="register".equals(mode), invalid=false;
        if(registering && !u.matches("[A-Za-z0-9\u4e00-\u9fff]{3,8}")){showError(usernameError,tr("用户名格式错误：请输入 3–8 位文字、字母或数字。","Username must be 3–8 characters or letters."));invalid=true;}
        if(registering && (p.length()<8||p.length()>12)){showError(passwordError,tr("密码格式错误：必须为 8–12 位。","Password must be 8–12 characters."));invalid=true;}
        if(registering && !p.equals(cp)){showError(confirmError,tr("两次密码不一致。","Passwords do not match."));invalid=true;}
        if(registering && !ph.matches("[+0-9 ()-]{7,24}")){showError(phoneError,tr("手机号格式错误。","Enter a valid phone number."));invalid=true;}
        if(cap.length()!=4){showError(captchaError,tr("请输入完整的 4 位验证码。","Enter all 4 captcha characters."));invalid=true;}
        if(invalid)return;
        busy=true; final String submittedMode=mode;
        worker.execute(()->{
            Q7AccountClient.AuthResult r=client.submitAuth(submittedMode,u,p,cp,cap,ph,"legacy-disabled","legacy-disabled",csrf);
            runOnUiThread(()->{
                busy=false;
                if(r.loggedIn){sessionAuthenticated=true;if(TARGET_SECURITY.equals(target))loadSecurity();else returnToHome();}
                else if(r.registered){renderAuth("login",r.message,r.nextBootstrap);}
                else {
                    renderAuth(submittedMode,"",r.nextBootstrap);
                    if(username!=null)username.setText(u); if(password!=null)password.setText(p);
                    if(phone!=null)phone.setText(ph); if(confirmPassword!=null)confirmPassword.setText(cp);
                    boolean captchaBad=r.message!=null&&r.message.contains("验证码");
                    if(captchaBad)showError(captchaError,r.message); else if(r.message!=null){
                        if(r.message.contains("用户名"))showError(usernameError,r.message);
                        else if(r.message.contains("确认密码")||r.message.contains("不一致"))showError(confirmError,r.message);
                        else if(r.message.contains("密码"))showError(passwordError,r.message);
                        else if(r.message.contains("手机号"))showError(phoneError,r.message);
                        else Toast.makeText(this,translateMessage(r.message),Toast.LENGTH_LONG).show();
                    }
                }
            });
        });
    }
    private void openRecovery() {
        if (busy) return;
        busy = true;
        worker.execute(() -> {
            Q7AccountClient.Bootstrap b = client.bootstrap("login");
            runOnUiThread(() -> {
                busy = false;
                target = TARGET_HOME;
                renderAuth("recover_lookup", b == null ? tr("暂时无法获取验证码，请重试。", "Could not load the image code. Please retry.") : b.message, b);
            });
        });
    }

    private void startRecoveryTimer(TextView view) {
        long remaining = Math.max(0L, recoveryExpiresAtMillis - System.currentTimeMillis());
        if (remaining <= 0L) {
            view.setText(tr("恢复凭证已过期，请重新申请。", "Recovery pass expired. Please request a new one."));
            return;
        }
        recoveryTimer = new CountDownTimer(remaining, 1000L) {
            @Override public void onTick(long millisUntilFinished) {
                long seconds = millisUntilFinished / 1000L;
                view.setText(tr("剩余有效时间：", "Time remaining: ") + String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
                        + tr("（只能使用一次）", " (one use only)"));
            }
            @Override public void onFinish() {
                view.setText(tr("恢复凭证已过期，请重新申请。", "Recovery pass expired. Please request a new one."));
                recoveryToken = "";
            }
        }.start();
    }

    private void lookupRecovery() {
        if(busy)return;
        String id=value(username),ph=value(phone),cap=value(captcha).toUpperCase(Locale.ROOT);
        showError(captchaError,"");
        if(cap.length()!=4){showError(captchaError,tr("请输入 4 位图片验证码。","Enter the 4-character image code."));return;}
        busy=true;
        worker.execute(()->{
            Q7AccountClient.RecoveryResult r=client.beginRecovery(id,ph,cap,csrf);
            runOnUiThread(()->{busy=false;
                if(r.success){recoveryCsrf=r.csrf;recoveryToken=r.recoveryToken;recoveryAccountId=r.accountId;recoveryUsername=r.username;recoveryPhone=r.phone;recoveryExpiresAtMillis=System.currentTimeMillis()+Math.max(1,r.expiresIn)*1000L;renderAuth("recover_reset","",null);}
                else {renderAuth("recover_lookup","",r.nextBootstrap);if(username!=null)username.setText(id);if(phone!=null)phone.setText(ph);showError(captchaError,r.message);}
            });
        });
    }
    private void resetPassword() {
        if(busy)return;
        String token=value(recoveryCode),next=value(newPassword),confirm=value(confirmPassword);
        if(!token.equals(recoveryToken)){Toast.makeText(this,tr("恢复凭证不正确。","Recovery pass does not match."),Toast.LENGTH_LONG).show();return;}
        if(next.length()<8||next.length()>12){Toast.makeText(this,tr("新密码必须为 8–12 位。","New password must be 8–12 characters."),Toast.LENGTH_LONG).show();return;}
        if(!next.equals(confirm)){Toast.makeText(this,tr("两次输入的新密码不一致。","Passwords do not match."),Toast.LENGTH_LONG).show();return;}
        busy=true;
        worker.execute(()->{
            Q7AccountClient.RecoveryResult r=client.completeRecovery(token,next,confirm,recoveryCsrf);
            Q7AccountClient.Bootstrap b=r.success?client.bootstrap("login"):r.nextBootstrap;
            runOnUiThread(()->{busy=false;if(r.success){recoveryToken="";renderAuth("login",r.message,b);}else renderAuth("recover_reset",r.message,b);});
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
        Button back=button(tr("返回","Back"),false); back.setOnClickListener(v->backFromSecurity()); top.addView(back); page.addView(top); space(page,24);
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
        currentPassword=passwordField(passwordCard,tr("当前密码","Current password"),tr("输入当前密码","Enter current password"));
        newPassword=passwordField(passwordCard,tr("新密码","New password"),tr("8–12 位","8–12 characters"));
        confirmPassword=passwordField(passwordCard,tr("确认新密码","Confirm new password"),tr("再次输入新密码","Re-enter new password"));
        Button save=button(tr("保存新密码","Save password"),true); save.setOnClickListener(v->changePassword()); passwordCard.addView(save,new LinearLayout.LayoutParams(-1,dp(49)));
        TextView forgot=text(tr("忘记密码？","Forgot password?"),11,Color.rgb(120,223,237),true);forgot.setPadding(0,dp(10),0,0);forgot.setOnClickListener(v->openRecovery());passwordCard.addView(forgot); page.addView(passwordCard);
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
        String old=value(currentPassword),next=value(newPassword),confirm=value(confirmPassword);
        if(next.length()<8||next.length()>12){Toast.makeText(this,tr("新密码必须为 8–12 位。","New password must be 8–12 characters."),Toast.LENGTH_LONG).show();return;}
        if(!next.equals(confirm)){Toast.makeText(this,tr("两次输入的新密码不一致。","Passwords do not match."),Toast.LENGTH_LONG).show();return;} busy=true;
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
    private void backFromSecurity(){ if(sessionAuthenticated)setResult(RESULT_OK); finish(); }
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
        if(TARGET_SECURITY.equals(target)){backFromSecurity();return;}
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
