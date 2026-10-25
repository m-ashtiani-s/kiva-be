# ریویوی حداقل محتوا: فاز ۵ (گام‌های ۵.۰ تا ۵.۵)

> برنچ `content-basics`، ۲۹ مهر ۱۴۰۵. نقشه‌ی راه: `COMPLETION_ROADMAP.md`، فاز ۵. راهنمای فرانت: `docs/FRONTEND_CONTENT.md`.
> برای هر ردیف بخش ۳ ِ نقشه‌ی راه که مال این فازه: دفاع کجاست، کدوم تست نگهبانشه، و نتیجه‌ی خرابکاری عمدی (نگهبان یه بار برداشته شد، تست قرمز شد، برگردونده شد).

---

## خلاصه

| چی بررسی شد | نتیجه |
|---|---|
| `./gradlew test --rerun-tasks` | ✅ **۲۷۲۲ تست** سبز (common ۲۳۳، domain ۱۱۳۸، application ۲۲۷، اصلی ۱۱۲۴). ۴ تست skip: ابزار واقعی ِ مدیا (`vips`، `ffmpeg`) روی این ماشین نیست |
| هشدار کامپایلر | ✅ صفر |
| قوانین لایه‌ها | ✅ `domain`، `common` و `application` هیچ import از Ktor، Exposed، Koin یا jsoup ندارن (jsoup فقط پشت پورت‌های `HtmlSanitizer` و `HtmlText`) |
| کانترکت | ✅ 1.12.0 (گام ۵.۰). پاسخ‌ها با `ContractSchemas` مقایسه می‌شن (`StaticPage`، `AdminStaticPageSummary`، `FaqGroup`، `FaqItem`، `FaqResponse`، `Announcement`، `SiteConfig` و بخش‌هاش) |
| `RouteGuardsTest` | ✅ هر ۹ endpoint ِ این فاز (`/admin/*` با `ADMIN` و `CONTENT_EDITOR`، سه endpoint ِ عمومی بی توکن) |
| دور با پنل و kiva-fe | ⬜ مونده برای تو (پایین‌تر) |
| merge به `main` | ⬜ منتظر تأیید تو (فازهای ۱ تا ۴ هم هنوز merge نشدن؛ این برنچ از سر فاز ۴ ساخته شده) |

---

## ردیف‌های بخش ۳

| # | خطر | دفاع | تست‌ها |
|---|---|---|---|
| 4 | XSS ِ ذخیره‌شده | هر HTML ِ این فاز **قبل** از ذخیره از `JsoupHtmlSanitizer` رد می‌شه (`PageDraft` برای متن صفحه، `FaqDraft` برای جواب سؤال؛ توضیح محصول از فاز ۱) و بعد از پاک‌سازی سنجیده می‌شه. لینک فقط به `http(s)://`، مسیر سایت (`/…`، نه `//` و `/\`) و `#…` (تصمیم‌های ۶ و ۱۹). هایلایت سؤالات: متن سؤال **escape** و بعد `<mark>`؛ جواب فقط در text nodeها علامت می‌خوره (`JsoupHtmlText`)، نه در اسم تگ یا آدرس. لینک اطلاعیه با `SiteLink` (`javascript:` و `//` ← ۴۰۰) | `JsoupHtmlSanitizerTest` (هر راه اجرای کد، هر لینک غیرمجاز، و لینک‌های مجاز)؛ `PageDraftTest` › `a title and some content make a page - … the content cleaned`، `content is measured after cleaning - …`؛ `WebV1AdminPagesOnDatabaseTest` › `script, on-handlers and javascript links go - a path of the site, a place on the page and eNamad stay, a protocol-relative link loses its href`؛ `FaqDraftTest` › `a good tree is kept in the arrays' order, answers cleaned, …`؛ `WebV1AdminFaqOnDatabaseTest` › `a tree of three groups is saved … the answers cleaned`، `a question's own HTML is shown escaped in its highlight`، `q finds only matching groups and questions … never in a tag or an address`؛ `FaqSearchTest` › `a question is escaped and marked - …`؛ `JsoupHtmlTextTest` › `a tag's name and a link's address are never marked`؛ `SiteLinkTest`؛ `WebV1AdminProductWritesOnDatabaseTest` › `a description is kept without script, … a path of the site stay` |
| 1 | بالا رفتن دسترسی | `/admin/pages*`، `/admin/faq/groups` و `/admin/announcements` فقط `ADMIN` و `CONTENT_EDITOR` (`x-roles` ِ کانترکت)؛ `/config`، `/pages/{slug}` و `/faq` عمومی و فقط خوندنی | `RouteGuardsTest`؛ صریح: `WebV1AdminPagesOnDatabaseTest` › `ADMIN and CONTENT_EDITOR may - OPERATOR and SUPPORT are 403 …`، `WebV1AdminFaqOnDatabaseTest` › `OPERATOR and SUPPORT are 403 …`، `WebV1AdminAnnouncementsOnDatabaseTest` › `OPERATOR and SUPPORT are 403 - ADMIN may` |
| 15 | داده در لاگ | متن صفحه هیچ‌وقت تو لاگ تغییرات نمیاد (فقط «عوض شد»؛ تصمیم ۲۰)؛ `Cmd`/`Fields`/DTOها `toString` ِ بدون متن دارن؛ لاگ برنامه فقط slug، تعداد و شناسه | `WebV1AdminPagesOnDatabaseTest` › `PUT makes a page and rewrites it - … one PAGE_SAVED row each, never the text` |

**جاهای دیگه‌ای که این فاز قفل یا قانون گذاشت** (بیرون از جدول بخش ۳، با همون قاعده‌ی «هر نگهبان یه خرابکاری»): قفل هر صفحه (دو اولین‌ذخیره‌ی همزمان)، قفل سؤالات و قفل نوار اطلاعیه (دو ذخیره‌ی همزمان کل درخت یا لیست)، «فقط اطلاعیه‌ی الان» و «فقط پیام‌رسان یعنی بسته» در `/config`.

---

## خرابکاری عمدی

| نگهبان | چی برداشته شد | تست(هایی) که قرمز شد |
|---|---|---|
| پاک‌سازی متن صفحه (ردیف ۴) | `sanitizer::clean` از `PageDraft` | `PageDraftTest` › `a title and some content make a page - …`، `content is measured after cleaning - …`، `no title, no content and content that is only a script - …`؛ `WebV1AdminPagesOnDatabaseTest` › `script, on-handlers and javascript links go - …`، `no title and content that is only a script - …` |
| پاک‌سازی جواب سؤال (ردیف ۴) | `sanitizer::clean` از `FaqDraft` | `FaqDraftTest` › `a good tree is kept … answers cleaned …`، `every problem at once, each on its path`؛ `WebV1AdminFaqOnDatabaseTest` › `a tree of three groups is saved … the answers cleaned` |
| پاک‌سازی توضیح محصول (ردیف ۴) | `sanitizer::clean` از `ProductDescription` | `WebV1AdminProductWritesOnDatabaseTest` › `a description is kept without script, on-handlers or javascript links - …` |
| لینک `//` (ردیف ۴، گام ۵.۱) | `!href.startsWith("//")` از `JsoupHtmlSanitizer` | `JsoupHtmlSanitizerTest` › `a protocol-relative link loses its href`؛ صفحه و توضیح محصول |
| هایلایت امن: escape ِ سؤال (ردیف ۴، گام ۵.۲) | `escape` ِ `FaqSearch.markText` بی‌اثر | `FaqSearchTest` › `a question is escaped and marked - …`؛ `WebV1AdminFaqOnDatabaseTest` › `a question's own HTML is shown escaped in its highlight` |
| هایلایت امن: فقط متن (ردیف ۴) | `JsoupHtmlText.mark` روی HTML ِ خام (اسم تگ و آدرس هم) | `JsoupHtmlTextTest` › `a tag's name and a link's address are never marked` |
| `SiteLink` (ردیف ۴، گام ۵.۳) | قانون لینک برداشته شد | `SiteLinkTest` (۷ مورد)؛ `WebV1AdminAnnouncementsOnDatabaseTest` › `11 announcements, a javascript link, … are 400 on their paths` |
| قفل صفحه (گام ۵.۱) | `pg_advisory_xact_lock` از `StaticPageRepositoryImpl.lock` | `two first saves of one page at the same moment, 10 times - …` (دومی ۵۰۰) |
| قفل سؤالات (گام ۵.۲) | همون، در `FaqRepositoryImpl.lock` | `two saves at the same moment, 10 times - … never two trees mixed` |
| قفل نوار اطلاعیه (گام ۵.۳) | همون، در `AnnouncementBarRepositoryImpl.lock` | `two saves at the same moment, 10 times - … never two lists mixed` |
| «فقط اطلاعیه‌ی الان» (گام ۵.۴) | `showingAt(now)` ← همه | `WebV1ConfigOnDatabaseTest` › `only the announcements whose time it is - …` |
| «فقط پیام‌رسان یعنی بسته» (گام ۵.۴) | `messengerOnly` از `SupportHours.isOpenAt` | `SupportHoursOpenTest` › `a line for messengers only, or without hours, is never open`؛ `WebV1ConfigOnDatabaseTest` › `isOpenNow by Tehran's clock - …` |

---

## چیزهایی که موقع کار پیدا شد

- **لینک `/\evil.com`.** تصمیم ۶ فقط «نه `//`» رو گفته بود؛ مرورگرها `/\` رو هم مثل `//` می‌خونن (آدرس سایت دیگه). حالا هر دو رد می‌شن، و فاصله‌ی دو سر و نویسه‌ی کنترلی (`/fa&#9;q`) هم (تصمیم ۱۹).
- **هایلایت با `normalizePersian` جای `<mark>` رو گم می‌کرد**، چون اون تابع فاصله و نیم‌فاصله رو فشرده می‌کنه و طول متن عوض می‌شه. جستجو و هایلایت حالا حرف‌به‌حرف تا می‌کنن (طول ثابت)، و فقط خود `q` با `normalizePersian` تمیز می‌شه (تصمیم ۱۷). سؤال متن ساده‌ست؛ بدون escape، `<b>` ِ نوشته‌ی ادیتور در هایلایت تگ می‌شد.
- **عددهای متن‌های نمونه** («رزرو ۴ روزه»، «۷ روز بازگشت») با عوض شدن تنظیمات دروغ می‌شدن؛ حالا `{holdDays}` و `{returnWindowDays}` و `{freeShippingThreshold}` ـن و `/config` پرشون می‌کنه (تصمیم ۲۳).
- **`SiteContent` به‌جای Aggregate یه read model شد** (تصمیم ۲۵): قانونی نداره و فقط خونده می‌شه. سند JSONB سخت‌گیرانه خونده می‌شه: کلیدی که با SQL جا بیفته یا غلط نوشته بشه خطای بلند می‌ده، نه سایت خالی.
- **خطای کل آرایه** (`> ۲۰` گروه، `> ۱۰` اطلاعیه) جایی برای `field` نداشت؛ روی `groups` و `announcements` اومد و کانترکت هم گفت (تصمیم ۲۴).
- **`SLUG_IMMUTABLE`** متن «محصول تازه بساز» رو داشت؛ عمومی شد (تصمیم ۲۲).
- **Gradle:** کامپایل همه‌ی تست‌ها از صفر (`--rerun-tasks`) دو بار با `OutOfMemoryError` ِ daemon ِ Kotlin شکست (مجموعه‌ی تست بزرگ شده). `kotlin.daemon.jvmargs=-Xmx3g` به `gradle.properties` اضافه شد؛ بعدش اجرای کامل سبز شد. اگه روی سرور CI حافظه کمه، این عدد رو ببین.
- **`PerkHttpResponse`** از قبل (صفحه‌ی محصول) بود؛ `/config` همون رو استفاده می‌کنه. نگاشت روش ارسال، ساعت پشتیبانی، شبکه‌ها و `features` هم حالا بین پاسخ تنظیمات پنل و `/config` مشترکه.
- **محیط:** Postgres ِ محلی روی `5432` (همون که فاز ۴ روشن شد)؛ Gradle با `-Djdk.net.unixdomain.tmpdir` ِ کوتاه.

---

## دور با پنل و kiva-fe (مونده برای تو)

اینجا پنل و kiva-fe در دسترس نبودن؛ همین مسیرها با تست‌های route روی routeهای واقعی رفته شدن. دور واقعی:

1. **صفحه‌ها:** پنل ← صفحه‌ی `terms` رو با یه لینک `/faq#return`، یه لینک اینماد و (برای امتحان) یه `<script>` بنویس. kiva-fe: `/pages/terms` بدون اسکریپت، با هر دو لینک. همین برای `privacy`، `returns` و `about` (چک‌لیست انتشار ۶.۷ این چهار رو می‌خواد).
2. **سؤالات:** پنل ← سه گروه (`reserve`، `shipping`، `return`) با چند سؤال؛ متن‌ها با قانون‌های این سند جور باشن (لغو تا قبل از تغییر وضعیت، استرداد با تماس تلفنی؛ تصمیم ۱۲). kiva-fe: `/faq` و `/faq#reserve`؛ جستجوی «رزرو» و «كار» (کاف عربی) با `<mark>`؛ جستجوی بی‌نتیجه ← پیام خالی.
3. **اطلاعیه‌ها:** پنل ← سه پیام (یکی برای فردا، یکی تمام‌شده)؛ kiva-fe: فقط پیام الان در نوار (تا ۵ دقیقه بعد از ذخیره).
4. **`/config`:** kiva-fe ثابت‌های `SHIPPING`، `FREE_POST`، `MSGR`، پیام‌های نوار، تلفن و ساعت پشتیبانی رو برداره و از `/config` بخونه (`docs/FRONTEND_CONTENT.md`، بخش ۱). پنل ← تنظیمات ← هزینه‌ی پست رو عوض کن؛ بعد از ۵ دقیقه هم صفحه‌ی سبد و هم فوتر عدد تازه رو دارن. ساعت پشتیبانی رو تنظیم کن و «الان پاسخگوییم» رو در ساعت کاری و بیرونش ببین.
5. **محتوای ثابت سایت:** شعار، متن «درباره»، لینک‌های فوتر و (موقع انتشار) نشان اینماد و ساماندهی فعلاً با SQL در `sitecontent_tbl` عوض می‌شن؛ متن‌های نمونه رو یه بار بخون و اگه لازمه بگو تا عوض کنم.
