package com.kiva.infrastructure.di

import com.kiva.application.port.AuditTrail
import com.kiva.application.port.IdempotencyStore
import com.kiva.application.port.SecurityEventLog
import com.kiva.application.repository.AdminCustomerRepository
import com.kiva.application.repository.AuditLogQmRepository
import com.kiva.application.repository.InventoryAdjustmentQmRepository
import com.kiva.application.repository.OfferQmRepository
import com.kiva.application.repository.OrderQmRepository
import com.kiva.application.repository.ProductQmRepository
import com.kiva.application.repository.ReportQmRepository
import com.kiva.application.repository.SiteContentRepository
import com.kiva.domain.aggregate.address.repository.AddressBookRepository
import com.kiva.domain.aggregate.address.repository.AddressIdGenerator
import com.kiva.domain.aggregate.announcement.repository.AnnouncementBarRepository
import com.kiva.domain.aggregate.cart.repository.CartIdGenerator
import com.kiva.domain.aggregate.cart.repository.CartRepository
import com.kiva.domain.aggregate.category.repository.CategoryIdGenerator
import com.kiva.domain.aggregate.category.repository.CategoryRepository
import com.kiva.domain.aggregate.color.repository.ColorRepository
import com.kiva.domain.aggregate.discount.repository.DiscountCodeRepository
import com.kiva.domain.aggregate.discount.repository.DiscountRedemptionRepository
import com.kiva.domain.aggregate.faq.repository.FaqRepository
import com.kiva.domain.aggregate.geo.repository.GeoRepository
import com.kiva.domain.aggregate.inventory.repository.InventoryRepository
import com.kiva.domain.aggregate.material.repository.MaterialIdGenerator
import com.kiva.domain.aggregate.material.repository.MaterialRepository
import com.kiva.domain.aggregate.media.repository.MediaIdGenerator
import com.kiva.domain.aggregate.media.repository.MediaRepository
import com.kiva.domain.aggregate.order.repository.OrderIdGenerator
import com.kiva.domain.aggregate.order.repository.OrderRepository
import com.kiva.domain.aggregate.ordernote.repository.OrderNoteRepository
import com.kiva.domain.aggregate.otp.repository.OtpChallengeRepository
import com.kiva.domain.aggregate.page.repository.StaticPageRepository
import com.kiva.domain.aggregate.payment.repository.PaymentIdGenerator
import com.kiva.domain.aggregate.payment.repository.PaymentRepository
import com.kiva.domain.aggregate.product.repository.ProductIdGenerator
import com.kiva.domain.aggregate.product.repository.ProductRepository
import com.kiva.domain.aggregate.refund.repository.RefundIdGenerator
import com.kiva.domain.aggregate.refund.repository.RefundRepository
import com.kiva.domain.aggregate.returnrequest.repository.ReturnIdGenerator
import com.kiva.domain.aggregate.review.repository.ProductRatingRepository
import com.kiva.domain.aggregate.review.repository.ReviewIdGenerator
import com.kiva.domain.aggregate.review.repository.ReviewRepository
import com.kiva.domain.aggregate.review.repository.ReviewVoteRepository
import com.kiva.domain.aggregate.returnrequest.repository.ReturnRequestRepository
import com.kiva.domain.aggregate.reservation.repository.ReservationGroupIdGenerator
import com.kiva.domain.aggregate.reservation.repository.ReservationGroupRepository
import com.kiva.domain.aggregate.session.repository.AuthSessionRepository
import com.kiva.domain.aggregate.settings.repository.ShopSettingsRepository
import com.kiva.domain.aggregate.user.repository.UserRepository
import com.kiva.infrastructure.cart.RandomCartIds
import com.kiva.infrastructure.media.RandomMediaIds
import com.kiva.infrastructure.payment.RandomPaymentIds
import com.kiva.infrastructure.persistence.exposed.AddressBookRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.AddressIdGeneratorImpl
import com.kiva.infrastructure.persistence.exposed.AdminCustomerRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.AnnouncementBarRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.AuditLogQmRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.AuditTrailImpl
import com.kiva.infrastructure.persistence.exposed.AuthSessionRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.CartRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.CategoryIdGeneratorImpl
import com.kiva.infrastructure.persistence.exposed.CategoryRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.ColorRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.DiscountCodeRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.DiscountRedemptionRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.FaqRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.GeoRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.IdempotencyStoreImpl
import com.kiva.infrastructure.persistence.exposed.InventoryAdjustmentQmRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.InventoryRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.MaterialIdGeneratorImpl
import com.kiva.infrastructure.persistence.exposed.MaterialRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.MediaRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.OfferQmRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.OrderIdGeneratorImpl
import com.kiva.infrastructure.persistence.exposed.OrderNoteRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.OrderQmRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.OrderRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.OtpChallengeRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.PaymentRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.ProductIdGeneratorImpl
import com.kiva.infrastructure.persistence.exposed.ProductQmRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.ProductRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.RefundIdGeneratorImpl
import com.kiva.infrastructure.persistence.exposed.RefundRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.ReturnIdGeneratorImpl
import com.kiva.infrastructure.persistence.exposed.ProductRatingRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.RandomWishlistShareTokens
import com.kiva.infrastructure.persistence.exposed.ReviewIdGeneratorImpl
import com.kiva.infrastructure.persistence.exposed.WishlistRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.WishlistShareRepositoryImpl
import com.kiva.domain.aggregate.wishlist.repository.WishlistRepository
import com.kiva.domain.aggregate.wishlist.repository.WishlistShareRepository
import com.kiva.domain.aggregate.wishlist.repository.WishlistShareTokenGenerator
import com.kiva.infrastructure.persistence.exposed.ReviewRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.ReviewVoteRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.ReturnRequestRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.ReportQmRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.ReservationGroupIdGeneratorImpl
import com.kiva.infrastructure.persistence.exposed.ReservationGroupRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.SecurityEventLogImpl
import com.kiva.infrastructure.persistence.exposed.ShopSettingsRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.SiteContentRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.StaticPageRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.UserRepositoryImpl
import org.koin.dsl.module

/**
 * Repository implementations, bound to the ports the domain and application layers ask for (the read model's and the
 * security log's too).
 */
val daoModule = module(createdAtStart = true) {
    single<ColorRepository> { ColorRepositoryImpl() }
    single<CategoryRepository> { CategoryRepositoryImpl() }
    single<CategoryIdGenerator> { CategoryIdGeneratorImpl() }
    single<MaterialRepository> { MaterialRepositoryImpl() }
    single<MaterialIdGenerator> { MaterialIdGeneratorImpl() }
    single<ProductRepository> { ProductRepositoryImpl() }
    single<ProductIdGenerator> { ProductIdGeneratorImpl() }
    single<InventoryRepository> { InventoryRepositoryImpl() }
    single<ProductQmRepository> { ProductQmRepositoryImpl() }
    single<InventoryAdjustmentQmRepository> { InventoryAdjustmentQmRepositoryImpl() }
    single<UserRepository> { UserRepositoryImpl() }
    single<OtpChallengeRepository> { OtpChallengeRepositoryImpl() }
    single<AuthSessionRepository> { AuthSessionRepositoryImpl() }
    single<SecurityEventLog> { SecurityEventLogImpl() }
    single<AdminCustomerRepository> { AdminCustomerRepositoryImpl() }
    single<GeoRepository> { GeoRepositoryImpl() }
    single<ShopSettingsRepository> { ShopSettingsRepositoryImpl() }
    single<AddressBookRepository> { AddressBookRepositoryImpl() }
    single<AddressIdGenerator> { AddressIdGeneratorImpl() }
    single<CartRepository> { CartRepositoryImpl() }
    single<CartIdGenerator> { RandomCartIds() }
    single<OfferQmRepository> { OfferQmRepositoryImpl() }
    single<OrderRepository> { OrderRepositoryImpl() }
    single<OrderNoteRepository> { OrderNoteRepositoryImpl() }
    single<RefundRepository> { RefundRepositoryImpl() }
    single<RefundIdGenerator> { RefundIdGeneratorImpl() }
    single<ReturnRequestRepository> { ReturnRequestRepositoryImpl() }
    single<ReturnIdGenerator> { ReturnIdGeneratorImpl() }
    single<ReviewRepository> { ReviewRepositoryImpl() }
    single<ReviewIdGenerator> { ReviewIdGeneratorImpl() }
    single<WishlistRepository> { WishlistRepositoryImpl() }
    single<WishlistShareRepository> { WishlistShareRepositoryImpl() }
    single<WishlistShareTokenGenerator> { RandomWishlistShareTokens() }
    single<ReviewVoteRepository> { ReviewVoteRepositoryImpl() }
    single<ProductRatingRepository> { ProductRatingRepositoryImpl() }
    single<OrderIdGenerator> { OrderIdGeneratorImpl() }
    single<OrderQmRepository> { OrderQmRepositoryImpl() }
    single<PaymentRepository> { PaymentRepositoryImpl() }
    single<PaymentIdGenerator> { RandomPaymentIds() }
    single<ReservationGroupRepository> { ReservationGroupRepositoryImpl() }
    single<ReservationGroupIdGenerator> { ReservationGroupIdGeneratorImpl() }
    single<IdempotencyStore> { IdempotencyStoreImpl() }
    single<DiscountCodeRepository> { DiscountCodeRepositoryImpl() }
    single<DiscountRedemptionRepository> { DiscountRedemptionRepositoryImpl() }
    single<AuditTrail> { AuditTrailImpl() }
    single<AuditLogQmRepository> { AuditLogQmRepositoryImpl() }
    single<ReportQmRepository> { ReportQmRepositoryImpl() }
    single<StaticPageRepository> { StaticPageRepositoryImpl() }
    single<FaqRepository> { FaqRepositoryImpl() }
    single<AnnouncementBarRepository> { AnnouncementBarRepositoryImpl() }
    single<SiteContentRepository> { SiteContentRepositoryImpl() }
    single<MediaRepository> { MediaRepositoryImpl() }
    single<MediaIdGenerator> { RandomMediaIds() }
}
