package com.kiva.infrastructure.seed

import com.kiva.application.usecase.catalog.usecase.query.ListCategoriesUc
import com.kiva.application.usecase.catalog.usecase.query.ListMaterialsUc
import com.kiva.application.usecase.inventory.usecase.command.AdjustInventoryUc
import com.kiva.application.usecase.product.usecase.command.CreateProductUc
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.unwrapOr
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.inventory.model.valueobject.AdjustmentReason
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Fills the catalog of a development database with the prototype's 16 bags (DevCatalogData) the way staff would:
 * CreateProductUc for each bag, then AdjustInventoryUc for the starting stock of each color. Their events and read
 * model are built exactly like real data's; nothing is written to a table directly.
 *
 * Runs on startup only when `kiva.seed.enabled` is on, never on a server. Running it again adds nothing: a bag whose
 * slug is in use (DUPLICATE_SLUG) is taken as seeded and skipped, its stock too. Any other refusal means the seed data
 * is wrong, and stops the boot.
 *
 * Each bag is its own requests, so a crash halfway leaves the bags before it complete; one stopped between its product
 * and its stock stays without that stock (start again from an empty database).
 */
class DevCatalogSeeder(
    private val createProductUc: CreateProductUc,
    private val adjustInventoryUc: AdjustInventoryUc,
    private val listCategoriesUc: ListCategoriesUc,
    private val listMaterialsUc: ListMaterialsUc,
    private val clock: Clock,
) {
    private val logger = getLogger()

    /** How many bags this run made, and how many were there already. */
    data class Report(val created: Int, val skipped: Int)

    suspend fun seed(): Report {
        logger.info("DevCatalogSeeder.seed bags=${DevCatalogData.bags.size}")

        // The prototype names categories and materials by slug; the request takes their ids.
        val categoryIds = listCategoriesUc.execute(ListCategoriesUc.Qry).unwrapOr { error("ListCategoriesUc can't fail") }
            .associate { it.slug to it.id }
        val materialIds = listMaterialsUc.execute(ListMaterialsUc.Qry).unwrapOr { error("ListMaterialsUc can't fail") }
            .associate { it.slug to it.id }
        val newUntil = clock.instant().plus(NEW_FOR)

        var created = 0
        var skipped = 0
        DevCatalogData.bags.forEach { bag ->
            val command = commandFor(bag, categoryIds.getValue(bag.categorySlug), materialIds.getValue(bag.materialSlug), newUntil)
            val product = createProductUc.execute(command).unwrapOr { failure ->
                if (failure is CreateProductUc.Failure.DuplicateSlug) {
                    skipped++
                    return@forEach
                }
                error("Seeding ${bag.slug} was refused: $failure")
            }

            if (bag.stockPerColor > 0) {
                product.variantIds.forEach { (color, variantId) ->
                    val delivery = AdjustInventoryUc.Cmd(variantId, bag.stockPerColor, AdjustmentReason.RESTOCK.name, STOCK_NOTE, UserId.SYSTEM)
                    adjustInventoryUc.execute(delivery).unwrapOr { error("Stock of ${bag.slug} in $color was refused: $it") }
                }
            }
            created++
        }

        logger.info("DevCatalogSeeder: $created bags made, $skipped already there")
        return Report(created, skipped)
    }

    private fun commandFor(bag: DevCatalogData.Bag, categoryId: Long, materialId: Long, newUntil: Instant) = CreateProductUc.Cmd(
        name = bag.name,
        slug = bag.slug,
        sku = bag.sku,
        categoryId = categoryId,
        bagType = bag.bagType,
        materialId = materialId,
        materialDescription = bag.material,
        price = bag.price,
        compareAtPrice = bag.compareAtPrice,
        shortDescription = null,
        description = DevCatalogData.description(bag),
        highlights = DevCatalogData.highlights(bag),
        specs = DevCatalogData.specsByBagType.getValue(bag.bagType),
        newUntil = newUntil.takeIf { bag.isNew },
        maxPerOrder = null,
        returnable = null,
        sortPriority = null,
        // The prototype shows every bag, sold-out ones included.
        status = "ACTIVE",
        variants = bag.colors.map { color ->
            CreateProductUc.Cmd.Variant(
                colorKey = color,
                sku = null,
                priceOverride = null,
                compareAtPriceOverride = null,
                isDefault = false,
                sortOrder = null,
            )
        },
        by = UserId.SYSTEM,
    )

    companion object {
        /** A bag the prototype marks new stays «جدید» this long after seeding. */
        val NEW_FOR: Duration = Duration.ofDays(30)

        const val STOCK_NOTE = "موجودی اولیه‌ی محیط توسعه"
    }
}
