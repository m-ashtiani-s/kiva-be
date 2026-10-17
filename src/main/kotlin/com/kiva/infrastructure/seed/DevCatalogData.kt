package com.kiva.infrastructure.seed

import com.kiva.application.usecase.product.ProductInput

/**
 * The frontend prototype's catalog (its assets/kiva.js): 16 bags, kept as the prototype has them, so a development
 * shop looks like the prototype from the first run.
 *
 * Where the prototype is coarser than the domain:
 * - its free-text material (`mat`) is a material of the list (V2__catalog_reference_data.sql) plus that same text as
 *   the product's materialDescription
 * - its single stock number per bag is the starting stock of each of the bag's colors
 * - its `isNew` flag is newUntil, a while after seeding (DevCatalogSeeder)
 * - specs, description and highlights come from its spec table per bag type and its product page's texts
 * - its sales counts, ratings, photos and videos are left out: nothing in the domain records them yet
 */
internal object DevCatalogData {

    /**
     * One bag of the prototype.
     *
     * @property material the prototype's own words for it; [materialSlug] is the material of the list behind them.
     * @property colors in the prototype's order; the first is the default.
     * @property reservable can be held with «رزرو ۴ روزه» (the frontend's mock has the party clutch as not).
     */
    data class Bag(
        val sku: String,
        val slug: String,
        val name: String,
        val categorySlug: String,
        val bagType: String,
        val materialSlug: String,
        val material: String,
        val price: Long,
        val compareAtPrice: Long?,
        val colors: List<String>,
        val stockPerColor: Int,
        val isNew: Boolean,
        val reservable: Boolean = true,
    )

    // In the prototype's order, so on an empty database product 1 is KV-1001, as there.
    val bags = listOf(
        Bag(
            "KV-1001", "mahak-shoulder-bag", "کیف دوشی ماهک", "shoulder", "HOBO", "faux-leather-matte", "چرم مصنوعی مات درجه یک",
            1_890_000, null, listOf("black", "lilac", "cream", "brown"), stockPerColor = 12, isNew = true,
        ),
        Bag(
            "KV-1002", "nila-crossbody-bag", "کراس‌بادی نیلا", "cross", "CROSSBODY", "faux-leather-soft", "چرم مصنوعی سافت",
            1_450_000, 1_750_000, listOf("black", "olive", "red", "cream", "gray", "pink", "blue"), stockPerColor = 9, isNew = false,
        ),
        Bag(
            "KV-1003", "sora-backpack", "کوله‌پشتی سورا", "backpack", "BACKPACK", "waterproof-canvas", "برزنت ضدآب با جزئیات چرمی",
            2_350_000, null, listOf("navy", "black", "caramel"), stockPerColor = 7, isNew = true,
        ),
        Bag(
            "KV-1004", "aida-handbag", "کیف دستی آیدا", "hand", "SATCHEL", "genuine-cow-leather", "چرم طبیعی گاوی",
            2_790_000, 3_290_000, listOf("caramel", "black", "cream", "red"), stockPerColor = 3, isNew = false,
        ),
        Bag(
            "KV-1005", "shabtab-clutch", "کیف مجلسی شب‌تاب", "party", "CLUTCH", "satin", "ساتن با سگک فلزی طلایی",
            980_000, null, listOf("black", "lilac", "white"), stockPerColor = 0, isNew = false,
        ),
        Bag(
            "KV-1006", "rozhan-shopper-bag", "شاپر روژان", "tote", "TOTE", "thick-cotton", "کتان ضخیم با دسته چرمی",
            1_290_000, null, listOf("cream", "black", "olive", "lilac", "brown", "blue"), stockPerColor = 22, isNew = true,
        ),
        Bag(
            "KV-1007", "parnia-bucket-bag", "کیف سطلی پرنیا", "bucket", "BUCKET", "faux-suede", "جیر مصنوعی",
            1_690_000, 1_990_000, listOf("brown", "black", "pink"), stockPerColor = 4, isNew = false,
        ),
        Bag(
            "KV-1008", "termeh-wallet", "کیف پول ترمه", "wallet", "WALLET", "faux-leather", "چرم مصنوعی",
            590_000, null, listOf("lilac", "black", "caramel", "red", "pink"), stockPerColor = 30, isNew = true,
        ),
        Bag(
            "KV-1009", "hasti-shoulder-bag", "کیف دوشی هستی", "shoulder", "HOBO", "genuine-goat-leather", "چرم طبیعی بزی",
            2_150_000, null, listOf("cream", "caramel", "black"), stockPerColor = 2, isNew = false,
        ),
        Bag(
            "KV-1010", "delaram-crossbody-bag", "کراس‌بادی دلارام", "cross", "CROSSBODY", "faux-leather-glossy", "چرم مصنوعی براق",
            1_350_000, null, listOf("lilac", "white", "black", "olive"), stockPerColor = 15, isNew = false,
        ),
        Bag(
            "KV-1011", "barana-backpack", "کوله‌پشتی بارانا", "backpack", "BACKPACK", "waterproof-canvas", "برزنت ضدآب",
            1_980_000, 2_400_000, listOf("gray", "black", "olive", "pink"), stockPerColor = 11, isNew = false,
        ),
        Bag(
            "KV-1012", "gelareh-handbag", "کیف دستی گلاره", "hand", "SATCHEL", "genuine-cow-leather", "چرم طبیعی گاوی",
            3_150_000, null, listOf("purple", "black", "cream"), stockPerColor = 6, isNew = true,
        ),
        Bag(
            "KV-1013", "mahtab-clutch", "کلاچ مهتاب", "party", "CLUTCH", "velvet", "مخمل با زنجیر طلایی",
            1_150_000, 1_390_000, listOf("white", "pink", "black", "lilac", "cream", "red"), stockPerColor = 8, isNew = false, reservable = false,
        ),
        Bag(
            "KV-1014", "aseman-tote-bag", "توت آسمان", "tote", "TOTE", "faux-leather", "چرم مصنوعی",
            1_590_000, null, listOf("blue", "navy", "cream"), stockPerColor = 0, isNew = false,
        ),
        Bag(
            "KV-1015", "nazli-wallet", "کیف پول نازلی", "wallet", "WALLET", "genuine-leather", "چرم طبیعی",
            690_000, 790_000, listOf("brown", "black", "olive"), stockPerColor = 18, isNew = false,
        ),
        Bag(
            "KV-1016", "sheyda-bucket-bag", "کیف سطلی شیدا", "bucket", "BUCKET", "faux-leather-soft", "چرم مصنوعی سافت",
            1_750_000, null, listOf("olive", "cream", "black", "caramel"), stockPerColor = 5, isNew = true,
        ),
    )

    /** The prototype's spec table, one per bag type: every bag of a type shares it. */
    val specsByBagType: Map<String, ProductInput.Specs> = mapOf(
        "HOBO" to specs(
            ProductInput.Size(34.0, 26.0, 12.0), 650, "ZIPPER",
            "بند دوشی ثابت ۵۵ سانتی‌متر", "۱ جیب زیپ‌دار داخلی، ۲ جیب موبایل", listOf("DAILY", "UNIVERSITY", "WORK"),
        ),
        "CROSSBODY" to specs(
            ProductInput.Size(22.0, 16.0, 8.0), 420, "MAGNETIC_FLAP",
            "بند بلند قابل تنظیم تا ۱۲۰ سانتی‌متر", "۱ جیب داخلی، ۱ جیب پشت", listOf("DAILY", "TRAVEL", "GATHERING"),
        ),
        "BACKPACK" to specs(
            ProductInput.Size(38.0, 28.0, 14.0), 800, "DOUBLE_ZIPPER",
            "دو بند دوشی قابل تنظیم + دسته", "جای لپ‌تاپ تا ۱۴ اینچ، جیب جلو", listOf("UNIVERSITY", "TRAVEL"),
        ),
        "SATCHEL" to specs(
            ProductInput.Size(30.0, 22.0, 12.0), 750, "ZIPPER_WITH_DECORATIVE_LOCK",
            "دسته کوتاه + بند بلند جداشونده", "۲ جیب داخلی، ۱ جیب زیپ‌دار", listOf("WORK", "FORMAL"),
        ),
        "CLUTCH" to specs(
            ProductInput.Size(24.0, 14.0, 5.0), 300, "MAGNETIC_FLAP",
            "زنجیر جداشونده ۱۰۰ سانتی‌متر", "۱ جیب کارت داخلی", listOf("PARTY"),
        ),
        "TOTE" to specs(
            ProductInput.Size(40.0, 32.0, 14.0), 550, "MAGNETIC_SNAP",
            "دو دسته بلند", "کیف کوچک داخلی جداشونده", listOf("SHOPPING", "DAILY", "TRAVEL"),
        ),
        "BUCKET" to specs(
            ProductInput.Size(26.0, 24.0, 18.0), 500, "DRAWSTRING",
            "بند دوشی قابل تنظیم", "۱ جیب داخلی", listOf("DAILY", "GATHERING"),
        ),
        "WALLET" to specs(
            ProductInput.Size(19.0, 10.0, 2.0), 180, "METAL_BUTTON",
            "بدون بند", "۱۲ جای کارت، ۲ جای اسکناس، جیب سکه", listOf("DAILY"),
        ),
    )

    /** The prototype's strap and pockets are one line of text each: a strap with that label, pockets with that summary. */
    private fun specs(size: ProductInput.Size, weightGrams: Int, closure: String, strap: String, pockets: String, occasions: List<String>) =
        ProductInput.Specs(
            dimensions = size,
            weightGrams = weightGrams,
            closure = closure,
            straps = listOf(ProductInput.Strap(label = strap)),
            pockets = ProductInput.Pockets(summary = pockets),
            occasions = occasions,
        )

    /** The prototype's description text, the same for every bag but its name and material. */
    fun description(bag: Bag): String =
        "<p>${bag.name} با ${bag.material}، دوخت دقیق و یراق‌آلات مقاوم طراحی شده تا هم شیک باشه و هم سال‌ها همراهت بمونه. " +
            "فضای داخلی حساب‌شده‌ش جای وسایل روزمره رو داره و فرم مینیمالش با هر استایلی هماهنگه. " +
            "عکس‌ها و ویدیوها کاملاً واقعی و بدون ادیت اغراق‌آمیز گرفته شدن؛ چون شعار ما اینه: هرچی ببینی، همون می‌رسه.</p>"

    /**
     * The prototype's «چرا …؟» bullets. Its fourth one lists the colors; that is worked out from the colors themselves,
     * not stored, or it would go stale when a color changes.
     */
    fun highlights(bag: Bag): List<String> = listOf(
        "${bag.material} با دوخت دولایه و لبه‌دوزی دستی",
        "یراق‌آلات فلزی ضدزنگ با روکش طلایی مات",
        "آستر داخلی ضدلک با رنگ یاسی کیوا",
    )
}
