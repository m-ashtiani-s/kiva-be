# ریویوی تماس و خبرنامه: فاز ۱۳ (گام‌های ۱۳.۰ تا ۱۳.۵)

> برنچ `contact`، ۱ آبان ۱۴۰۵. نقشه‌ی راه: `COMPLETION_ROADMAP.md`، فاز ۱۳. راهنمای فرانت: `docs/FRONTEND_CONTACT.md`.
> ردیف ۱۲ ِ بخش ۳ ِ نقشه‌ی راه (اسپم پیامک) برای فرم تماس، و خطرهای خود این فاز: داده‌ی شخصی ِ فرم و خبرنامه در لاگ، متن آزاد در پیامک، پاسخ ِ خبرنامه که لو بده ایمیلی عضوه یا نه، و ایمیل ِ خوش‌آمد که به آدرس دیگران شلیک بشه. برای هر کدوم: دفاع، تست نگهبان، و نتیجه‌ی خرابکاری عمدی.

---

## خلاصه

| چی بررسی شد | نتیجه |
|---|---|
| `./gradlew test --continue --rerun-tasks` | ✅ **۳۰۶۳ تست** سبز. ۵ تست skip: تست‌های ابزار واقعی (`vips` و `ffmpeg` روی این ماشین نیستن) |
| هشدار کامپایلر | ✅ صفر |
| قوانین لایه‌ها | ✅ `domain`، `common` و `application` هیچ import از Ktor، Exposed، Koin یا Jakarta Mail ندارن (پورت `EmailSender` در application، پیاده‌سازی‌ها در infrastructure) |
| کانترکت | ✅ 1.19.0 (گام ۱۳.۰). پاسخ‌های `ContactTopicOption`، `ContactMessageResponse`، `AdminContactMessage`، `MessageResponse` و `NewsletterSubscription` در تست‌ها با شِماهای کانترکت مقایسه می‌شن |
| migration | ✅ `V38__contact_newsletter.sql` (هر دو جدول، با CHECKهای اسم‌دار؛ `ContactMigrationTest`) |
| ایمیل | ✅ `provider = log` پیش‌فرض؛ SMTP با Angus Mail آماده (گام ۱۳.۱). **ارائه‌دهنده هنوز انتخاب نشده** (سؤال ۳ ِ بخش ۲۸) |
| پیامک تازه | ✅ `sendContactReplied` با مسیر کامل ۹ مرحله‌ای؛ الگوی `KIVA_FARAZ_CONTACT_REPLIED_PATTERN` باید در پنل فراز ساخته و تأیید بشه |
| حذف حساب (بخش ۴.۱۰) | ⚠️ برای فاز ۱۴: `contactmessage_tbl` موبایل، ایمیل، اسم، متن و `userId` داره؛ `AccountEraser` باید پیام‌های اون کاربر رو پاک کنه (یا بی‌نام). خبرنامه به حساب وصل نیست (فقط ایمیل) |
| دور واقعی روی سرور با kiva-fe | ⬜ مونده برای تو (پایین‌تر) |
| merge به `main` و deploy | ⬜ منتظر تأیید تو (این برنچ از سر فاز ۱۲ ساخته شده)؛ با `KIVA_EMAIL_PROVIDER=log` تا ارائه‌دهنده انتخاب بشه |

---

## خطرهای این فاز

| خطر | دفاع | تست‌ها |
|---|---|---|
| اسپم با فرم تماس (ردیف ۱۲ ِ بخش ۳) | سقف IP (۵ در ساعت، `RateLimit` ِ `contact`) و سقف موبایل (۵ در ۲۴ ساعت از هر IPای، شمارش از جدول، پشت قفل advisory ِ همون موبایل). فرم تماس **هیچ پیامکی** نمی‌فرسته (تصمیم ۶) | `WebV1ContactOnDatabaseTest` › `the sixth message from one address …`، `the sixth message of one mobile in a day …`، `eight messages of one mobile at once …` |
| داده‌ی شخصی در لاگ | `Cmd`/`Input`/`Draft` ِ تماس و خبرنامه `toString` ِ بی داده دارن؛ لاگ فقط کد، موضوع، نتیجه؛ شکست ایمیل فقط با **نوع** خطا (پیام سرور ایمیل می‌تونه آدرس رو داشته باشه)؛ `LogEmailSender` ایمیل رو ماسک می‌کنه و متن رو نمی‌نویسه | `WebV1ContactOnDatabaseTest` › `the log has the code and the topic, never …` (با لاگ SQL)؛ `WebV1NewsletterOnDatabaseTest` › `the log never has the email or the token`؛ `EmailSendersTest` › `log - …`؛ `ContactMessageDraftTest` و `ContactMessageChangeTest` (`toString`) |
| متن آزاد در پیامک | `SmsSender.sendContactReplied(to, ticketCode)` اصلاً متنی نمی‌گیره؛ پاسخ پیامکی فقط خبر می‌ده (تصمیم ۷) | `WebV1AdminContactMessagesOnDatabaseTest` › `a reply by SMS - the SMS has the code and never the reply's text …` |
| متن پاسخ یا یادداشت در لاگ تغییرات | `changes`: وضعیت، `replied` و `replyChannel`، و `internalNote` فقط «عوض شد» (تصمیم ۲۰) | همون تست و `a second reply replaces the first … never in the change log` |
| خبرنامه لو بده ایمیلی عضوه | همیشه همون `200` با همون بدنه؛ لغو همیشه `204` (ناشناخته هم) | `WebV1NewsletterOnDatabaseTest` › `a new email - 200 … again, in other capitals, the same 200 and no mail`، `leaving with the link - … a made-up token of the shape 204 …` |
| بمباران ایمیل خوش‌آمد به آدرس دیگران | سقف IP (۵ در ساعت، `newsletter`)؛ عضو ِ از قبل فعال ایمیل نمی‌گیره | `… the sixth subscribe from one address in an hour is 429 …`؛ `… no mail` |
| کد پیگیری قابل حدس | `CT-` + ۶ رقم از `SecureRandom`، نه شماره‌ی پشت‌سرهم؛ برخورد با `ON CONFLICT DO NOTHING` و کشیدن دوباره | `… a code already taken is drawn again …` |
| داده‌ی شخصی ِ بیشتر از لازم | job ِ `delete-old-contact-data` (۵:۰۰ تهران): پیام `CLOSED` بعد از ۳۶۵ روز، لغوشده بعد از ۹۰ روز | `WebV1NewsletterOnDatabaseTest` › `the nightly clean-up - …`؛ `EngagementJobsOnDatabaseTest` › `the engagement's jobs and when they run` |
| ایمیل‌های مشترک‌ها دست نقش اشتباه | لیست خبرنامه فقط `ADMIN`؛ پیام‌های تماس `ADMIN` و `SUPPORT` | `… ADMIN only …`؛ `… OPERATOR and CONTENT_EDITOR are 403` |
| رمز SMTP لو بره | فقط از محیط (`set-env.sh --secret`)؛ `toString` ِ `SmtpConfig` نشونش نمی‌ده؛ بدون host یا فرستنده برنامه بالا نمیاد | `AppConfigTest` › `the SMTP password never shows …`، `smtp without its host or sender …` |

---

## خرابکاری عمدی

هر گام با یه اسکریپت خرابکاری‌هاش رو اجرا کرد و **هر ۴۹ تا قرمز کردن**:

| گام | نگهبان‌ها | نتیجه |
|---|---|---|
| ۱۳.۱ | host و from ِ اجباری، `startTls` ِ سخت‌گیر و پیش‌فرض ِ `true`، نصف ِ ورود، بازه‌ی پورت، رمز در `toString`، provider ِ ناشناخته، متن یا ایمیل کامل در لاگ ِ `log`، charset ِ موضوع و متن، گیرنده | ۱۳ از ۱۳ قرمز |
| ۱۳.۲ | سقف IP، سقف موبایل، زمان انتظار (`Retry-After`)، قفل موبایل (هشت پیام هم‌زمان)، `userId` ِ واردشده، کشیدن دوباره‌ی کد گرفته‌شده، موبایل در لاگ، `toString` ِ ورودی، کش موضوع‌ها، خط تازه‌ی متن، حداقل اسم | ۱۱ از ۱۱ قرمز |
| ۱۳.۳ | پیامک یا ایمیل به کانال درست، چک `EMAIL_NOT_AVAILABLE`، ذخیره‌ی بی‌تغییر، متن پاسخ یا یادداشت در لاگ تغییرات، شکست پیامک که کار رو برگردونه، نقش `OPERATOR`، فیلتر وضعیت، پاسخ بی کانال، وضعیت ِ نفرستاده که عوض بشه، `repliedAt` ِ پاسخ دوم، ترتیب لیست | ۱۲ از ۱۲ قرمز |
| ۱۳.۴ | خوش‌آمد به عضو قدیمی، پاسخی که لو بده، برنگشتن ِ لغوشده، توکن بدشکل ← `204`، لغو ِ ذخیره‌نشده، `SUPPORT` در لیست خبرنامه، `q`، سقف IP، پاک شدن پیام `OPEN`، نگه‌داری ِ کوتاه‌تر، ایمیل در لاگ، لینک بی توکن، توکن در `toString` | ۱۳ از ۱۳ قرمز |

---

## چیزهایی که موقع کار پیدا شد

- **`normalizePersianLetters` روی کل متن خط تازه رو فاصله می‌کنه** (هر `\s+`): متن پیام تماس و پاسخ و یادداشت خط به خط نرمال می‌شن (تصمیم ۲۲). تب هم فاصله می‌شه. `TestimonialDraft` همین تابع رو روی کل متن پیام رضایت می‌زنه و خط تازه‌ش رو از دست می‌ده؛ اگه مهمه، یه اصلاح کوچیک در فاز بعد.
- **سقف موبایل بدون قفل رد می‌شد**: هشت پیام هم‌زمان ِ یه موبایل همه از شمارش رد می‌شدن؛ قفل advisory ِ تراکنشی با کلید موبایل (پارامتر، پس تو لاگ SQL نیست) اضافه شد (تصمیم ۲۳، قفل ۱۵ ِ بخش ۴.۱۱).
- **`V38` یکجا در ۱۳.۲** نوشته شد (جدول خبرنامه هم) تا migration ِ commit‌شده دیگه عوض نشه.
- **`EmailTexts` در لایه‌ی application** (تصمیم ۲۴)، چون UseCase ایمیل رو می‌نویسه؛ نقشه‌ی راه اون رو کنار متن‌های HTTP گذاشته بود.
- **ایمیل خوش‌آمد در همون درخواست** فرستاده می‌شه (مثل پیامک‌ها): با SMTP ِ کند، پاسخ ِ عضو تازه کمی دیرتر از عضو قدیمیه. پذیرفته شد (تصمیم ۲۷)؛ اگه مهم شد، فرستادن به یه صف پس‌زمینه می‌ره.
- **`launch-check.sh`** الگوهای فاز ۱۰ و ۱۳ («موجود شد»، «پشتیبانی رسیدگی کرد») رو چک نمی‌کنه؛ هر دو اختیاری‌ان و بی‌الگو فقط WARN می‌دن. جدول `LAUNCH.md` هر دو رو داره.

---

## دور واقعی روی سرور (مونده برای تو)

1. `V38` روی سرور اجرا می‌شه (دو جدول تازه، بی داده).
2. پنل فراز: الگوی «پشتیبانی رسیدگی کرد» با متغیر `code` بساز، تأیید بگیر، `KIVA_FARAZ_CONTACT_REPLIED_PATTERN` رو با `set-env.sh` بنویس (متن پیشنهادی در README و `LAUNCH.md`).
3. ایمیل: تا ارائه‌دهنده انتخاب نشده `KIVA_EMAIL_PROVIDER=log` بمونه (پیش‌فرض). بعدش `KIVA_SMTP_HOST`، `KIVA_SMTP_FROM`، ورود و رمز (README، بخش «Email»)، و یه پاسخ ایمیلی آزمایشی به یه پیام تماس ِ خودت.
4. kiva-fe: فرم تماس ← کد `CT-…`؛ پنل ← پیام در صف `OPEN`، پاسخ پیامکی ← پیامک خبر (بی متن)؛ فوتر ← عضویت، دوباره با حروف بزرگ ← همون پیام؛ لینک لغو ِ ایمیل خوش‌آمد ← صفحه‌ی لغو (با `log` لینک جایی دیده نمی‌شه، چون متن ایمیل لاگ نمی‌شه؛ با SMTP امتحانش کن).
5. شش پیام پشت‌سرهم از یه موبایل ← ششمی `429` با زمان انتظار.
