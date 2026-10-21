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

---

## گام ۶.۲: مدیا روی سرور

**۱. ابزارها** (از mirror ِ آروان):

```bash
apt install -y libvips-tools ffmpeg
vips --version && ffmpeg -version | head -n 1
```

**۲. پوشه.** مالک `kiva`، گروه `www-data` تا nginx بخونه و هیچ‌کس دیگه نه؛ `2` (setgid) یعنی هر پوشه‌ی تازه‌ای که برنامه می‌سازه هم گروه `www-data` می‌گیره:

```bash
install -d -o kiva -g www-data -m 750  /var/lib/kiva
install -d -o kiva -g www-data -m 2750 /var/lib/kiva/media
/opt/kiva-ops/set-env.sh KIVA_MEDIA_ROOT            # /var/lib/kiva/media
/opt/kiva-ops/set-env.sh KIVA_MEDIA_PUBLIC_URL      # https://api.kivashop.ir/media
/opt/kiva-ops/set-env.sh KIVA_MEDIA_SERVE_LOCALLY   # false
```

فایل‌ها باید برای گروه خوندنی باشن: unit ِ `kiva` باید `UMask=0027` داشته باشه (مرجعش `/opt/kiva-ops/systemd/kiva.service`). اگه نداره و هنوز به گام ۶.۴ نرسیدی: `systemctl edit kiva` و

```ini
[Service]
UMask=0027
```

**۳. nginx ِ API.** فایل http-context (zoneهای محدودیت گام ۶.۵ هم توشن و تا اون موقع بی‌اثرن) و از مرجع، فقط `location /media/` و دو `location = …` ِ آپلود رو به بلوک ۴۴۳ ِ فعلی ِ `api.kivashop.ir` اضافه کن:

```bash
cp /opt/kiva-ops/nginx/kiva-http.conf /etc/nginx/conf.d/
cp /opt/kiva-ops/nginx/kiva-proxy-params.conf /etc/nginx/snippets/     # اگه در ۶.۱ نکردی
diff <(nginx -T 2>/dev/null | sed -n '/server_name api.kivashop.ir/,/^}/p') /opt/kiva-ops/nginx/api.kivashop.ir.conf
nano /etc/nginx/sites-available/api.kivashop.ir
nginx -t && systemctl reload nginx && systemctl restart kiva
```

بقیه‌ی API همون `client_max_body_size 1m` می‌مونه؛ آپلود پنل `210m` با `proxy_read_timeout 120s`، آپلود مشتری (فاز ۷) `55m`.

**۴. سقف آپلود CDN ِ آروان.** در پنل آروان، تنظیمات CDN ِ `kivashop.ir`، حداکثر حجم درخواست رو ببین. کمتر از ۲۰۰ مگ بود؟ سقف ویدیوی `MediaLimitsPolicy` باید پایین بیاد (یه تغییر کد و یه ردیف کانترکت؛ خبرم کن). عددش رو تو گزارش کار بنویس (سؤال باز ۴).

**تموم شده یعنی:** یه عکس و یه ویدیو از پنل آپلود می‌شن، و:

```bash
f=$(find /var/lib/kiva/media -name '*.webp' | head -n 1); curl -sI "https://api.kivashop.ir/media/${f#/var/lib/kiva/media/}" | grep -i cache-control
#   cache-control: public, max-age=31536000, immutable
```

---

## گام ۶.۳: بکاپ روزانه و تست بازگردانی

**۱. روشن کردن و اولین اجرا:**

```bash
systemctl enable --now kiva-backup.timer
systemctl list-timers kiva-backup.timer          # بعدی: ۰۳:۰۰ ِ تهران
systemctl start kiva-backup.service && journalctl -u kiva-backup -n 20 --no-pager
ls -la /root/backups/$(TZ=Asia/Tehran date +%F)  # kiva.dump، media.tgz، SHA256SUMS
```

`backup.sh` هر شب: `pg_dump -Fc` ِ `kiva` (و یه بار خوندن فهرستش، تا dump ِ خراب بکاپ حساب نشه)، tar ِ پوشه‌ی مدیا، و `SHA256SUMS`. پوشه‌ی روز فقط وقتی پیدا می‌شه که هر سه کامل باشن. روزهای قدیمی‌تر از ۱۴ روز پاک می‌شن. ساعت ۳ قبل از پاک‌سازی‌های شبانه‌ی برنامه‌ست (۳:۳۰ و ۴:۰۰ و ۴:۳۰).

**۲. تست بازگردانی:**

```bash
/opt/kiva-ops/restore-test.sh
```

checksumها، `pg_restore` در دیتابیس تازه‌ی `kiva_restore_test`، شمارش سفارش‌ها و پرداخت‌ها و استردادها و محصول‌ها و … کنار دیتابیس زنده، شماره‌ی آخرین migration، و فهرست `media.tgz` در برابر پوشه؛ بعد دیتابیس آزمایشی پاک می‌شه (`--keep` نگهش می‌داره). خط آخرش (`restore-test: OK …`) رو تو گزارش کار ِ نقشه‌ی راه بنویس. دیتابیس زنده فقط خونده می‌شه.

**۳. یه کپی بیرون از سرور** (سؤال باز ۲؛ یکی رو انتخاب کن):

- **الف) فضای ابری آروان** (خودکار، هر شب):

  ```bash
  apt install -y rclone
  rclone config        # یه remote ِ s3 با اسم arvan، provider «Arvan»، endpoint ِ همون باکت در پنل آروان؛ کلیدها رو خودت وارد کن
  install -d -m 700 /etc/kiva
  echo 'KIVA_BACKUP_RCLONE_REMOTE=arvan:kiva-backups' > /etc/kiva/backup.env && chmod 600 /etc/kiva/backup.env
  systemctl start kiva-backup.service && rclone ls arvan:kiva-backups | tail -n 3
  ```

  کپی نشدن ابری بکاپ محلی رو خراب نمی‌کنه، ولی سرویس رو `failed` می‌کنه و هشدار گام ۶.۸ (`BACKUP_OLD`) خبرت می‌کنه.

- **ب) دانلود روی کامپیوتر خودت** (هفته‌ای یه بار، یا با Task Scheduler):

  ```powershell
  .\tools\ops\pull-backup.ps1 -Destination D:\kiva-backups
  ```

  آخرین روز رو می‌آره، SHA-256 ِ هر فایل رو چک می‌کنه، و ۸ روز آخر رو نگه می‌داره.

**تموم شده یعنی:** بکاپ دیشب هست، `restore-test.sh` خط `OK` داده و تو گزارش کار اومده، و راه کپی بیرونی انتخاب و یه بار امتحان شده.

---

## گام ۶.۴: deploy با یه دستور

**۱. unit ِ برنامه، یه بار.** `deploy-remote.sh` فرض می‌کنه کد در `/opt/kiva` و مال `root` ـه، `kiva.env` رو systemd می‌خونه (پس `root` و `600` می‌مونه) و برنامه با کاربر `kiva` اجرا می‌شه:

```bash
diff <(systemctl cat kiva | grep -v '^#') <(grep -v '^#' /opt/kiva-ops/systemd/kiva.service)
```

فرق مهم (`ExecStart` جای دیگه، `EnvironmentFile` نیست، کاربر دیگه) ← مرجع رو بذار:

```bash
cp /opt/kiva-ops/systemd/kiva.service /etc/systemd/system/kiva.service
systemctl daemon-reload && systemctl restart kiva && curl -s http://127.0.0.1:8080/health
```

مرجع چند خط محافظ هم داره (`ProtectSystem=full`، `ProtectHome=read-only`، `PrivateTmp`، `NoNewPrivileges`). اگه بعدش آپلود پنل ۵۰۰ داد و لاگ `media processing failed` گفت، `ProtectHome` رو بردار و بگو.

**۲. هر deploy ِ بک‌اند** (روی ویندوز، از ریشه‌ی kiva-be؛ تست‌ها رو قبلش روی همون کامیت زده باشی):

```powershell
.\tools\ops\deploy.ps1
```

`gradlew installDist`، بسته با `kiva-release.txt` (کامیت و زمان)، و روی سرور: `pg_dump` ِ سریع در `/root/backups/deploy` (پنج تای آخر)، باز کردن در `/opt/kiva.new` با کپی `conf`، stop، `/opt/kiva` ← `/opt/kiva.prev`، start، و صبر تا `/health` بگه `UP`. تا ۹۰ ثانیه نگفت: نسخه‌ی قبلی سر جاش برمی‌گرده و start می‌شه، نسخه‌ی خراب در `/opt/kiva.failed` می‌مونه، و اسکریپت خطا می‌ده. دو deploy همزمان نمی‌شه (قفل).

نسخه‌ی قبلی روی schema ای که نسخه‌ی تازه migrate کرده هم بالا میاد (Flyway migrationهای ناشناخته‌ی آینده رو نادیده می‌گیره). اگه خود migration داده رو خراب کرده بود، راه برگشت همون dump ِ قبل از deploy ـه.

**۳. فروشگاه و پنل:**

```powershell
.\tools\ops\deploy-fe.ps1 -App shop
.\tools\ops\deploy-fe.ps1 -App panel
```

بار اولِ فروشگاه با این اسکریپت: اگه الان در `/opt/kiva-fe/app` با pm2 ِ `kiva-fe` نیست، یا layout ِ فعلی رو با پارامترها بده، یا یه بار جابه‌جاش کن (`app` و `.pm2` زیر `/opt/kiva-fe`) و process ِ قدیمی pm2 رو `pm2 delete` کن. `NEXT_PUBLIC_*` ِ فروشگاه رو قبلش در `tools/ops/fe/shop.build.env` کامل کن.

**تموم شده یعنی:**

```powershell
.\tools\ops\deploy.ps1 -BreakOnPurpose
```

(یه نسخه که `bin/kiva-be` اش فقط `java` ِ بی‌آرگومان اجرا می‌کنه) خطا می‌ده با «the previous one is UP again»، و `https://api.kivashop.ir/health` بعدش `UP` ـه. سایت حداکثر همون ۹۰ ثانیه‌ی انتظار پایینه و پایین نمی‌مونه. بعد یه `.\tools\ops\deploy.ps1` ِ معمولی.

---

## گام ۶.۵: امنیت سرور

**۱. SSH بدون رمز.** اول مطمئن شو راه برگشت هست: `ssh kiva true` با کلید کار می‌کنه، و کنسول وب سرور در پنل آروان رو باز کردی. بعد:

```bash
cp /opt/kiva-ops/ssh/00-kiva.conf /etc/ssh/sshd_config.d/
sshd -t && systemctl reload ssh
sshd -T | grep -E '^(passwordauthentication|permitrootlogin)'   # no، without-password
```

پنجره‌ی فعلی رو نبند؛ از یه پنجره‌ی تازه امتحان کن:

```powershell
ssh kiva true                                                                   # باید وصل شه
ssh -o PubkeyAuthentication=no -o PreferredAuthentications=password kiva true   # Permission denied (publickey)
```

`00-` یعنی این فایل قبل از `50-cloud-init.conf` ِ خود سیستم خونده می‌شه، و sshd اولین مقدار هر تنظیم رو نگه می‌داره.

**۲. فایروال:**

```bash
/opt/kiva-ops/firewall.sh
```

اول پورت SSH ِ واقعی (از `sshd -T`) رو باز می‌کنه، بعد ۸۰ و ۴۴۳، بعد بقیه رو می‌بنده و ufw رو روشن می‌کنه. قانون‌های قبلی ِ دیگه رو پاک نمی‌کنه، فقط اسمشون رو می‌گه؛ اگه لازم نیستن: `ufw status numbered` و `ufw delete <شماره>`.

**۳. آروان ← سرور با HTTPS.** در پنل آروان، تنظیمات CDN ِ دامنه: پروتکل اتصال به سرور اصلی = HTTPS. (برای همینه که nginx به برنامه `X-Forwarded-Proto https` می‌گه.)

**۴. IP ِ واقعی و محدودیت درخواست:**

```bash
/opt/kiva-ops/arvan-real-ip.sh
```

فهرست IPهای آروان رو از `https://www.arvancloud.ir/fa/ips.txt` می‌گیره و `conf.d/arvan-real-ip.conf` رو می‌سازه: هدر `ar-real-ip` فقط وقتی پذیرفته می‌شه که درخواست واقعاً از لبه‌ی آروان بیاد. هر ماه یه بار دوباره اجراش کن (`launch-check.sh` بعد از ۳۰ روز یادت می‌ندازه).

بعد بقیه‌ی بلوک API از مرجع: `error_page 429 = @kiva_rate_limited;`، پنج location ِ محدود، و خود `@kiva_rate_limited`:

```bash
diff <(nginx -T 2>/dev/null | sed -n '/server_name api.kivashop.ir/,/^}/p') /opt/kiva-ops/nginx/api.kivashop.ir.conf
nano /etc/nginx/sites-available/api.kivashop.ir
nginx -t && systemctl reload nginx
```

| مسیر | به ازای هر IP |
|---|---|
| `/api/v1/auth/otp/` | ۱۰ در دقیقه، تا ۵ تا پشت‌سرهم |
| `/api/v1/cart` | ۱۲۰ در دقیقه، تا ۶۰ تا پشت‌سرهم |
| `/api/v1/orders` | ۳۰ در دقیقه، تا ۱۰ تا پشت‌سرهم (دوازدهمی ۴۲۹) |
| `/api/v1/tracking/lookup` | ۱۰ در دقیقه، تا ۵ تا (برنامه خودش ۱۰ در ۱۰ دقیقه) |
| `/api/v1/contact/messages` | ۶ در دقیقه، تا ۳ تا (فاز ۱۳) |

۴۲۹ ِ nginx همون Problem ِ برنامه‌ست (`RATE_LIMITED`، `meta.retryAfterSeconds`) با هدرهای CORS، تا فرانت فرقشون رو نفهمه. فهرست origin ِ `map` در `kiva-http.conf` باید همون `KIVA_ALLOWED_ORIGINS` باشه.

**۵. بدون localhost:**

```bash
/opt/kiva-ops/set-env.sh KIVA_ALLOWED_ORIGINS   # https://kivashop.ir,https://www.kivashop.ir,https://admin.kivashop.ir
/opt/kiva-ops/set-env.sh KIVA_TRUSTED_PROXY_HOPS   # 1 (اگه نیست)
systemctl restart kiva
```

**تموم شده یعنی:** ورود با رمز رد می‌شه، `ufw status` فقط سه پورت داره، و

```bash
/opt/kiva-ops/launch-check.sh --rate-limit | grep -E '429|SSH|ufw'
```

برای همه‌شون `ok` می‌گه (بیست POST ِ پشت‌سرهم به `/api/v1/orders` از سرور، از راه آروان؛ دوازدهمی به بعد ۴۲۹).

---

## گام ۶.۶: حساب‌ها و رازها

`/opt/kiva-ops/kiva.env.example` همه‌ی متغیرهای سرور رو با مقدار درستشون داره (رازها `<SECRET>`). فایل زنده رو باهاش مقایسه کن:

```bash
diff <(grep -oE '^[A-Z_]+=' /opt/kiva/conf/kiva.env | sort) <(grep -oE '^[A-Z_]+=' /opt/kiva-ops/kiva.env.example | sort)
```

**۱. زرین‌پال.** در پنل زرین‌پال:

- سطح احراز پذیرنده به حد لازم برسه (وگرنه خطای ‎`-16`).
- دامنه‌ی ثبت‌شده‌ی درگاه `api.kivashop.ir` باشه، چون callback به `https://api.kivashop.ir/api/v1/payment-callbacks/…` برمی‌گرده (وگرنه ‎`-14`).
- IP ِ سرور ثبت بشه (برای `reverse` ِ پرداخت‌هایی که سفارششون دیگه پذیرفتنی نیست).

بعد روی سرور:

```bash
/opt/kiva-ops/set-env.sh KIVA_PAYMENT_PROVIDER       # zarinpal
/opt/kiva-ops/set-env.sh KIVA_ZARINPAL_SANDBOX       # false
/opt/kiva-ops/set-env.sh --secret KIVA_ZARINPAL_MERCHANT_ID
/opt/kiva-ops/set-env.sh KIVA_API_PUBLIC_URL         # https://api.kivashop.ir
/opt/kiva-ops/set-env.sh KIVA_PAYMENT_RESULT_URL     # https://kivashop.ir/checkout/result (یا مسیر واقعی صفحه‌ی نتیجه‌ی فروشگاه)
/opt/kiva-ops/set-env.sh KIVA_SITE_URL               # https://kivashop.ir
```

**۲. فراز اس‌ام‌اس.** کلیدی که یه بار تو گفتگو فرستاده شده بود سوخته: در پنل فراز یه کلید تازه بساز، `set-env.sh --secret KIVA_FARAZ_API_KEY`، restart، یه کد ورود امتحان کن، بعد کلید قدیمی رو در پنل حذف کن.

هر الگو رو در پنل فراز بساز، منتظر تأییدش بمون، و کدش رو با `set-env.sh <متغیر>` بذار. متن‌ها پیشنهادن؛ فقط اسم متغیرها (`%order%` و …) باید همین باشه:

| متغیر env | متغیرها | متن پیشنهادی |
|---|---|---|
| `KIVA_FARAZ_CODE_PATTERN` | `code` | (موجود) «کد ورود: %code% / وبسایت کیوا» |
| `KIVA_FARAZ_PHONE_CHANGED_PATTERN` | `ending` | «حساب کیوای شما به شماره‌ی منتهی به %ending% منتقل شد. اگه خودت نبودی، به پشتیبانی بگو.» |
| `KIVA_FARAZ_ORDER_PAID_PATTERN` | `order` | «سفارش %order% پرداخت شد. پیگیری: https://kivashop.ir/track?order=%order%» |
| `KIVA_FARAZ_PHOTOS_SENT_PATTERN` | `order` | «عکس کیف سفارش %order% تو پیام‌رسانت فرستاده شد. همونه؟ تو صفحه‌ی سفارش بگو.» |
| `KIVA_FARAZ_ORDER_SHIPPED_PATTERN` | `order`، `carrier`، `tracking` | «سفارش %order% با %carrier% ارسال شد. کد رهگیری: %tracking%» |
| `KIVA_FARAZ_ORDER_DELIVERED_PATTERN` | `order` | «سفارش %order% تحویل شد. نظرت درباره‌ی کیفت برامون مهمه.» |
| `KIVA_FARAZ_ORDER_CANCELLED_PATTERN` | `order` | «سفارش %order% لغو شد.» |
| `KIVA_FARAZ_REFUND_COMPLETED_PATTERN` | `order` | «مبلغ سفارش %order% به حسابت برگشت.» |
| `KIVA_FARAZ_CHANGE_RESOLVED_PATTERN` | `order` | «درخواستت برای سفارش %order% بررسی شد؛ جزئیات تو صفحه‌ی سفارش.» |
| `KIVA_FARAZ_ALERT_PATTERN` (گام ۶.۸) | `what` | «هشدار کیوا: %what%» |

الگوهای فازهای بعد (مرجوعی، «موجود شد»، پاسخ تماس) با همون فازها اضافه می‌شن.

**۳. آدرس‌های رهگیری.** در پنل، تنظیمات ← روش‌های ارسال: آدرس رهگیری تیپاکس و پست رو با یه کد واقعی در سایت خودشون امتحان کن و اگه لازمه اصلاح کن (`{code}` جای کد).

```bash
systemctl restart kiva
journalctl -u kiva -n 300 --no-pager | grep -E "no pattern for|is 'fake'|is 'log'"     # هیچی
```

**تموم شده یعنی:** لاگ شروع هیچ WARN ِ «no pattern for …» نداره، و `launch-check.sh` برای خط‌های «payment» و «SMS» `ok` می‌گه. برنامه خطی مثل `provider is Zarinpal (sandbox=false)` لاگ نمی‌کنه (تصمیم ۴ ِ این فاز)؛ به جاش نبود WARN ِ `Payment gateway is 'fake'` و مقدارهای `kiva.env` چک می‌شن.
