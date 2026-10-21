# انتشار کیوا: دستور کار روی سرور

> فاز ۶ ِ [`COMPLETION_ROADMAP.md`](COMPLETION_ROADMAP.md#p6) (بخش ۱۱). هر کاری که روی سرور انجام می‌شه، اینجا قدم‌به‌قدم و با دستور دقیقش اومده. فایل‌ها و اسکریپت‌هاش در `tools/ops/` ان و همه تست دارن: `tools/ops/test/ops-test.sh` (هر اسکریپت روی لینوکس، راه درست و خطاهاش) و `PurgeSeedSqlOnDatabaseTest` (پاک کردن داده‌ی نمونه روی دیتابیس واقعی).
>
> **قانون رازها:** هیچ رمز، کلید یا شناسه‌ی پذیرنده‌ای تو گفتگو، git، اسکرین‌شات یا تاریخچه‌ی shell نمیاد. فقط روی سرور و فقط با `/opt/kiva-ops/set-env.sh --secret NAME` وارد می‌شه، که مقدار رو نشون نمی‌ده (`read -rsp`) و دو بار می‌پرسه.
>
> هر دستوری که با `ssh kiva` یا `.\tools\ops\` شروع می‌شه روی ویندوز ِ خودته (از ریشه‌ی kiva-be)؛ بقیه روی سرور، با `root`.

## در یک نگاه

| گام | از ریپو | کار تو روی سرور | تموم شده یعنی |
|---|---|---|---|
| ۶.۱ | `nginx/admin.kivashop.ir*.conf`، `deploy-fe.ps1`، `deploy-fe-remote.sh`، `set-env.sh` | DNS، کاربر `kivabo`، گواهی، اولین ادمین | با موبایلت وارد پنل روی سرور می‌شی و محصول می‌سازی |
| ۶.۲ | `nginx/api.kivashop.ir.conf`، `nginx/kiva-http.conf` | `libvips`/`ffmpeg`، پوشه‌ی مدیا، nginx | عکس و ویدیو از پنل آپلود می‌شه و با `immutable` باز می‌شه |
| ۶.۳ | `backup.sh`، `restore-test.sh`، `kiva-backup.timer`، `pull-backup.ps1` | روشن کردن timer، تست بازگردانی، کپی بیرون از سرور | بکاپ دیشب هست و بازگردانیش تو گزارش کاره |
| ۶.۴ | `deploy.ps1`، `deploy-remote.sh`، `systemd/kiva.service` | یکی کردن unit با مرجع | deploy ِ عمداً خراب خودش برمی‌گرده |
| ۶.۵ | `ssh/00-kiva.conf`، `firewall.sh`، `arvan-real-ip.sh`، محدودیت‌های nginx | SSH، ufw، آروان، origins | ورود با رمز رد، سه پورت، `429` در بیستمین سفارش |
| ۶.۶ | `kiva.env.example`، متن پیشنهادی الگوها | زرین‌پال، الگوهای فراز، کلید تازه | لاگ شروع بی WARN ِ الگو، درگاه واقعی |
| ۶.۷ | `purge-seed.sh`، `purge_seed.sql`، `trust_badges.sql` | پاک کردن نمونه‌ها، محتوای واقعی | فقط محصول واقعی، صفحه‌های اینماد باز |
| ۶.۸ | `alerts.sh`، `kiva-alerts.timer` | الگوی هشدار، روشن کردن timer، پایش بیرونی | یه ERROR ِ ساختگی ← پیامک تا ۵ دقیقه |
| ۶.۹ | `launch-check.sh` | دور واقعی خرید و لغو | `launch-check.sh` صفر FAIL، و چک‌لیست آدمی تیک‌خورده |

## پیش از گام‌ها (یک بار)

**۱. اسم کوتاه سرور روی ویندوز.** در `%USERPROFILE%\.ssh\config`:

```
Host kiva
    HostName <IP سرور>
    User root
    IdentityFile ~/.ssh/id_ed25519
```

همه‌ی اسکریپت‌های ویندوزی پیش‌فرض `kiva` رو صدا می‌زنن (یا `-Server`، یا متغیر `KIVA_SSH_TARGET`). امتحان: `ssh kiva true` بدون پرسیدن رمز.

**۲. سرور الان چی داره.** اسکریپت‌ها این‌ها رو فرض می‌کنن؛ هر جا سرور فرق داره، یا سرور رو با این یکی کن (بهتر)، یا متغیرش رو به اسکریپت بده:

| فرض | چک | اگه فرق داره |
|---|---|---|
| برنامه در `/opt/kiva`، unit ِ `kiva`، کاربر `kiva`، `/opt/kiva/conf/kiva.env`، پورت ۸۰۸۰ | `systemctl cat kiva`، `ls -la /opt/kiva /opt/kiva/conf` | گام ۶.۴: unit رو با مرجع یکی کن |
| دیتابیس `kiva` با superuser ِ `postgres` | `runuser -u postgres -- psql -l` | `KIVA_DB_NAME` |
| فروشگاه: `/opt/kiva-fe`، کاربر `kivafe`، پورت ۳۱۰۰، pm2 به اسم `kiva-fe` | `ls -la /opt/kiva-fe`، `ps -o user= -C node`، `ss -ltnp \| grep 3100` | `deploy-fe.ps1 -RemoteRoot … -RemoteUser … -Port … -Pm2Name …` |
| webroot ِ certbot: `/var/www/certbot` | `nginx -T 2>/dev/null \| grep -B2 -A3 acme-challenge` | همون پوشه رو در `admin.kivashop.ir.http.conf` و دستور certbot بذار |
| nginx ِ اوبونتو (`sites-available`، `conf.d`، `snippets`) | `ls /etc/nginx` | — |

**۳. فرستادن `tools/ops` به سرور** (هر بار که `tools/ops` عوض شد، دوباره):

```powershell
scp -r tools/ops kiva:/tmp/kiva-ops
ssh kiva "bash /tmp/kiva-ops/install-ops.sh && rm -rf /tmp/kiva-ops"
```

اسکریپت‌ها می‌رن `/opt/kiva-ops` (فقط root)، و unitهای بکاپ و هشدار به `/etc/systemd/system` (هنوز روشن نمی‌شن؛ هر گام خودش روشنشون می‌کنه).

**۴. یه بار تست اسکریپت‌ها** (هر وقت عوضشون کردی): `wsl bash tools/ops/test/ops-test.sh` ← خط آخر `… passed, 0 failed`.

---

## گام ۶.۱: پنل روی سرور

**۱. DNS.** در پنل آروان، دامنه‌ی `kivashop.ir` ← DNS: رکورد `A` با اسم `admin` به IP ِ سرور، CDN روشن (ابر نارنجی). هم‌سایت بودن با `api.kivashop.ir` لازمه تا کوکی `SameSite=Strict` ِ نشست پنل کار کنه.

**۲. کاربر و پوشه‌ی پنل:**

```bash
useradd --system --home-dir /opt/kiva-bo --shell /usr/sbin/nologin kivabo
install -d -o kivabo -g kivabo -m 750 /opt/kiva-bo /opt/kiva-bo/.pm2
node -v && pm2 -v      # همونی که فروشگاه داره؛ نبود: npm install -g pm2
```

**۳. nginx و گواهی:**

```bash
cp /opt/kiva-ops/nginx/kiva-proxy-params.conf /etc/nginx/snippets/
cp /opt/kiva-ops/nginx/admin.kivashop.ir.http.conf /etc/nginx/sites-available/admin.kivashop.ir.http
ln -s ../sites-available/admin.kivashop.ir.http /etc/nginx/sites-enabled/
nginx -t && systemctl reload nginx
certbot certonly --webroot -w /var/www/certbot -d admin.kivashop.ir --deploy-hook "systemctl reload nginx"
cp /opt/kiva-ops/nginx/admin.kivashop.ir.conf /etc/nginx/sites-available/admin.kivashop.ir
ln -s ../sites-available/admin.kivashop.ir /etc/nginx/sites-enabled/
nginx -t && systemctl reload nginx
```

بلوک ۴۴۳ ِ پنل `X-Frame-Options DENY` و `X-Robots-Tag noindex` داره و بدنه‌ی بیشتر از یک مگ نمی‌گیره: آپلودهای پنل مستقیم به `api.kivashop.ir` می‌رن.

**۴. اجازه‌ی مرورگر و اولین ادمین:**

```bash
/opt/kiva-ops/set-env.sh KIVA_ALLOWED_ORIGINS
#   ← https://kivashop.ir,https://www.kivashop.ir,https://admin.kivashop.ir  (localhost ها در گام ۶.۵ می‌رن؛ اگه الان لازمن، ته همین فهرست)
/opt/kiva-ops/set-env.sh KIVA_BOOTSTRAP_ADMIN_PHONE      # موبایل خودت
systemctl restart kiva
journalctl -u kiva -n 200 --no-pager | grep BootstrapAdminUc   # «… is now ADMIN; remove KIVA_BOOTSTRAP_ADMIN_PHONE»
/opt/kiva-ops/set-env.sh --unset KIVA_BOOTSTRAP_ADMIN_PHONE
systemctl restart kiva
```

**۵. خود پنل** (روی ویندوز؛ پوشه‌ی `kiva-backoffice-fe` کنار `kiva-be`، یا با `-Source`):

```powershell
.\tools\ops\deploy-fe.ps1 -App panel
```

`npm ci` و `npm run build` با `NEXT_PUBLIC_API_URL=https://api.kivashop.ir/api/v1/` (از `tools/ops/fe/panel.build.env`)، بسته‌بندی (خروجی standalone ِ Next اگه پروژه `output: 'standalone'` داره، وگرنه `.next` و فایل‌های package و `npm ci --omit=dev` روی سرور)، و روی سرور: `/opt/kiva-bo/app` ← نسخه‌ی تازه، قبلی در `app.prev`، pm2 با `PM2_HOME=/opt/kiva-bo/.pm2` روی `127.0.0.1:3200`. اگه تا ۶۰ ثانیه جواب نده، نسخه‌ی قبلی برمی‌گرده (بار اول قبلی نداره و فقط خطا می‌ده).

بالا اومدن pm2 ِ پنل بعد از ریبوت (یه بار):

```bash
pm2 startup systemd -u kivabo --hp /opt/kiva-bo     # pm2-kivabo.service، با PM2_HOME=/opt/kiva-bo/.pm2
```

**تموم شده یعنی:** با موبایلت در `https://admin.kivashop.ir` وارد می‌شی، نقشت `ADMIN` ـه، و یه دسته و یه محصول می‌سازی.
