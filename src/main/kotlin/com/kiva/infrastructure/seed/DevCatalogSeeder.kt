package com.kiva.infrastructure.seed

import com.kiva.application.usecase.catalog.usecase.query.ListCategoriesUc
import com.kiva.application.usecase.catalog.usecase.query.ListMaterialsUc
import com.kiva.application.usecase.product.ProductInput
import com.kiva.application.usecase.product.usecase.command.CreateProductUc
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.unwrapOr
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Fills the catalog of a development database with the prototype's 16 bags (DevCatalogData) the way staff would:
 * CreateProductUc for each bag, with the starting stock of each color in the same request (one RESTOCK each). Their
 * events and read model are built exactly like real data's; nothing is written to a table directly.
 *
 * Runs on startup only when `kiva.seed.enabled` is on, never on a server. Running it again adds nothing: a bag whose
 * slug is in use (DUPLICATE_SLUG) is taken as seeded and skipped, its stock too. Any other refusal means the seed data
 * is wrong, and stops the boot.
 *
 * Each bag is its own transaction, stock included, so a crash halfway leaves the bags before it complete and no bag
 * half made.
 */
class DevCatalogSeeder(
    private val createProductUc: CreateProductUc,
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
            createProductUc.execute(command).unwrapOr { failure ->
                if (failure is CreateProductUc.Failure.DuplicateSlug) {
                    skipped++
                    return@forEach
                }
                error("Seeding ${bag.slug} was refused: $failure")
            }
            created++
        }

        logger.info("DevCatalogSeeder: $created bags made, $skipped already there")
        return Report(created, skipped)
    }

    private fun commandFor(bag: DevCatalogData.Bag, categoryId: Long, materialId: Long, newUntil: Instant) = CreateProductUc.Cmd(
        fields = ProductInput.Fields(
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
            tags = null,
            newUntil = newUntil.takeIf { bag.isNew }?.toString(),
            maxPerOrder = null,
            returnable = null,
            reservable = bag.reservable,
            sortPriority = null,
            seo = null,
        ),
        // The prototype shows every bag, sold-out ones included.
        status = "ACTIVE",
        variants = bag.colors.map { color -> ProductInput.Variant(colorKey = color, stockQuantity = bag.stockPerColor) },
        actor = null,
    )

    companion object {
        /** A bag the prototype marks new stays «جدید» this long after seeding. */
        val NEW_FOR: Duration = Duration.ofDays(30)
    }
}
