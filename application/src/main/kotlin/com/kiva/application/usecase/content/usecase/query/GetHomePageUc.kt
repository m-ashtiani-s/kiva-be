package com.kiva.application.usecase.content.usecase.query

import com.kiva.application.dto.HomePageDto
import com.kiva.application.dto.TestimonialListDto
import com.kiva.application.repository.ProductCardQm
import com.kiva.application.repository.ProductFilter
import com.kiva.application.repository.ProductQmRepository
import com.kiva.application.repository.SiteContentRepository
import com.kiva.application.usecase.banner.Banners
import com.kiva.application.usecase.campaign.CampaignShowcases
import com.kiva.application.usecase.catalog.CategoryMenu
import com.kiva.application.usecase.product.ProductCards
import com.kiva.application.usecase.product.usecase.query.ProductSorts
import com.kiva.application.usecase.testimonial.Testimonials
import com.kiva.common.logging.getLogger
import com.kiva.common.pagination.Page
import com.kiva.common.pagination.Pageable
import com.kiva.common.railway.Result
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.QueryUseCase
import com.kiva.domain.aggregate.banner.model.valueobject.BannerPlacement
import com.kiva.domain.aggregate.banner.repository.BannerRepository
import com.kiva.domain.aggregate.campaign.model.policy.CampaignShowcasePolicy
import com.kiva.domain.aggregate.campaign.repository.CampaignRepository
import com.kiva.domain.aggregate.category.repository.CategoryRepository
import com.kiva.domain.aggregate.color.repository.ColorRepository
import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import com.kiva.domain.aggregate.settings.repository.ShopSettingsRepository
import com.kiva.domain.aggregate.testimonial.repository.TestimonialRepository
import java.time.Clock

/**
 * The home page in one answer (`GET /home`, public, cached for 5 minutes; roadmap section 16.6, decisions 15 to 17):
 * the content's fixed sections; the showcase product (the content's choice when it is in the shop, else the newest);
 * the category menu; the 8 newest products (sold out last); the HOME_PROMO banners showing now (6 at most); the running
 * campaign with 4 products; the testimonials' summary and first 6. One transaction, a fixed number of reads whatever
 * the shop holds. No card has a heart: the answer is the same for everyone.
 */
class GetHomePageUc(
    private val transactionManager: TransactionManager,
    private val siteContentRepository: SiteContentRepository,
    private val shopSettingsRepository: ShopSettingsRepository,
    private val productQmRepository: ProductQmRepository,
    private val categoryRepository: CategoryRepository,
    private val colorRepository: ColorRepository,
    private val categoryMenu: CategoryMenu,
    private val bannerRepository: BannerRepository,
    private val banners: Banners,
    private val campaignRepository: CampaignRepository,
    private val campaignShowcases: CampaignShowcases,
    private val testimonialRepository: TestimonialRepository,
    private val testimonials: Testimonials,
    private val clock: Clock,
) : QueryUseCase<GetHomePageUc.Qry, HomePageDto, Nothing> {

    private val logger = getLogger()

    data object Qry

    override suspend fun execute(query: Qry): Result<HomePageDto, Nothing> {
        logger.info("GetHomePageUc.execute")
        val now = clock.instant()
        return transactionManager.inTransaction {
            val home = siteContentRepository.load().home
            val settings = shopSettingsRepository.get()
            val newest = productQmRepository.find(ProductFilter(), NEWEST_EIGHT).items
            val chosen = home.showcaseProductId?.let { id -> productQmRepository.findCards(listOf(ProductId.constructFromPersisted(id))).firstOrNull() }
            val categories = categoryRepository.findAll().associateBy { it.slug }
            val palette = colorRepository.findAll().associateBy { it.key }
            val card = { qm: ProductCardQm ->
                ProductCards.cardOf(qm, categories.getValue(qm.categorySlug), palette, now, settings.freeShippingThreshold, settings.lowStockThreshold)
            }
            val firstPage = testimonialRepository.page(published = true, FIRST_SIX)
            Result.success(
                HomePageDto(
                    home = home,
                    settings = settings,
                    showcase = (chosen ?: newest.firstOrNull())?.let(card),
                    categories = categoryMenu.all(),
                    newArrivals = newest.map(card),
                    promos = banners.of(bannerRepository.findVisible(BannerPlacement.HOME_PROMO, now, MAX_PROMOS)),
                    sale = campaignRepository.findLiveAt(now)?.let { campaignShowcases.of(it, CampaignShowcasePolicy.LIMIT_FOR_HOME, now) },
                    testimonials = TestimonialListDto(
                        settings.siteStats.satisfaction,
                        Page(testimonials.of(firstPage.items), firstPage.page, firstPage.size, firstPage.totalItems),
                    ),
                ),
            )
        }
    }

    companion object {
        const val NEW_ARRIVALS = 8
        const val MAX_PROMOS = 6
        const val TESTIMONIALS = 6

        // Both always valid: the sizes are inside the limits and the sort is one of ProductSorts.
        private val NEWEST_EIGHT: Pageable =
            Pageable.makeNew(1, NEW_ARRIVALS, ProductSorts.NEWEST, ProductSorts.names).getOrNull() ?: error("8 newest is a valid page")
        private val FIRST_SIX: Pageable = Pageable.makeNew(1, TESTIMONIALS).getOrNull() ?: error("6 a page is a valid page")
    }
}
