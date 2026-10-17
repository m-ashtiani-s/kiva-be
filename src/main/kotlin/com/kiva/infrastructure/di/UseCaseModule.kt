package com.kiva.infrastructure.di

import com.kiva.application.usecase.account.usecase.command.ConfirmPhoneChangeUc
import com.kiva.application.usecase.account.usecase.command.RequestPhoneChangeUc
import com.kiva.application.usecase.account.usecase.command.UpdateMyProfileUc
import com.kiva.application.usecase.account.usecase.query.GetMeUc
import com.kiva.application.usecase.address.usecase.command.AddAddressUc
import com.kiva.application.usecase.address.usecase.command.DeleteAddressUc
import com.kiva.application.usecase.address.usecase.command.SetDefaultAddressUc
import com.kiva.application.usecase.address.usecase.command.UpdateAddressUc
import com.kiva.application.usecase.address.usecase.query.GetMyAddressUc
import com.kiva.application.usecase.address.usecase.query.ListMyAddressesUc
import com.kiva.application.usecase.admin.usecase.command.CheckPanelAccessUc
import com.kiva.application.usecase.admin.usecase.command.SetUserRolesUc
import com.kiva.application.usecase.admin.usecase.query.FindCustomersUc
import com.kiva.application.usecase.admin.usecase.query.GetCustomerUc
import com.kiva.application.usecase.audit.usecase.command.DeleteOldAuditEntriesUc
import com.kiva.application.usecase.audit.usecase.query.ListAuditLogUc
import com.kiva.application.usecase.media.usecase.command.DeleteUnattachedMediaUc
import com.kiva.application.usecase.media.usecase.command.UploadMediaUc
import com.kiva.application.usecase.media.usecase.query.FindUnattachedMediaUc
import com.kiva.application.usecase.auth.OtpIssuer
import com.kiva.application.usecase.auth.usecase.command.BootstrapAdminUc
import com.kiva.application.usecase.auth.usecase.command.LogoutUc
import com.kiva.application.usecase.auth.usecase.command.RefreshSessionUc
import com.kiva.application.usecase.auth.usecase.command.SendLoginCodeUc
import com.kiva.application.usecase.auth.usecase.command.VerifyLoginCodeUc
import com.kiva.application.usecase.cart.CartCommands
import com.kiva.application.usecase.cart.usecase.command.AddCartItemUc
import com.kiva.application.usecase.cart.usecase.command.ApplyDiscountCodeUc
import com.kiva.application.usecase.cart.usecase.command.ChangeCartItemUc
import com.kiva.application.usecase.cart.usecase.command.ChooseShippingMethodUc
import com.kiva.application.usecase.cart.usecase.command.ClearCartUc
import com.kiva.application.usecase.cart.usecase.command.DeleteStaleGuestCartsUc
import com.kiva.application.usecase.cart.usecase.command.MergeGuestCartUc
import com.kiva.application.usecase.cart.usecase.command.OpenCartUc
import com.kiva.application.usecase.cart.usecase.command.RemoveCartItemUc
import com.kiva.application.usecase.cart.usecase.command.RemoveDiscountCodeUc
import com.kiva.application.usecase.cart.usecase.command.SetCartReservationUc
import com.kiva.application.usecase.cart.usecase.query.GetCartUc
import com.kiva.application.usecase.catalog.usecase.query.GetCategoryUc
import com.kiva.application.usecase.catalog.usecase.query.ListCategoriesUc
import com.kiva.application.usecase.catalog.usecase.query.ListColorsUc
import com.kiva.application.usecase.catalog.usecase.query.ListMaterialsUc
import com.kiva.application.usecase.checkout.usecase.command.HandleGatewayCallbackUc
import com.kiva.application.usecase.checkout.usecase.command.PlaceOrderUc
import com.kiva.application.usecase.checkout.usecase.command.StartPaymentAttemptUc
import com.kiva.application.usecase.checkout.usecase.command.StartPaymentUc
import com.kiva.application.usecase.checkout.usecase.query.GetCheckoutContextUc
import com.kiva.application.usecase.checkout.usecase.query.GetPaymentInitUc
import com.kiva.application.usecase.checkout.usecase.query.GetPaymentResultUc
import com.kiva.application.usecase.discount.Discounts
import com.kiva.application.usecase.geo.usecase.query.ListCitiesOfProvinceUc
import com.kiva.application.usecase.geo.usecase.query.ListProvincesUc
import com.kiva.application.usecase.idempotency.IdempotencyGuard
import com.kiva.application.usecase.idempotency.usecase.command.DeleteOldIdempotencyKeysUc
import com.kiva.application.usecase.inventory.usecase.command.AdjustInventoryUc
import com.kiva.application.usecase.order.usecase.command.ExpireUnpaidOrderUc
import com.kiva.application.usecase.order.usecase.command.ReorderUc
import com.kiva.application.usecase.order.usecase.query.FindExpirableOrdersUc
import com.kiva.application.usecase.order.usecase.query.GetMyOrderUc
import com.kiva.application.usecase.order.usecase.query.GetOrderSummaryUc
import com.kiva.application.usecase.order.usecase.query.ListMyOrdersUc
import com.kiva.application.usecase.payment.PaymentConfirmation
import com.kiva.application.usecase.payment.usecase.command.ReconcilePaymentUc
import com.kiva.application.usecase.payment.usecase.query.FindStalePaymentsUc
import com.kiva.application.usecase.product.usecase.command.CreateProductUc
import com.kiva.application.usecase.product.usecase.query.FindProductsUc
import com.kiva.application.usecase.product.usecase.query.FindRelatedProductsUc
import com.kiva.application.usecase.product.usecase.query.GetProductFacetsUc
import com.kiva.application.usecase.product.usecase.query.GetProductUc
import com.kiva.application.usecase.reservation.Reservations
import com.kiva.application.usecase.reservation.usecase.command.ReleaseReservationGroupUc
import com.kiva.application.usecase.reservation.usecase.query.FindDueReservationGroupsUc
import com.kiva.application.usecase.reservation.usecase.query.GetMyReservationUc
import com.kiva.infrastructure.config.AppConfig
import org.koin.dsl.module

val useCaseModule = module(createdAtStart = true) {
    // --- catalog: queries ---
    single { ListColorsUc(transactionManager = get(), colorRepository = get(), productQmRepository = get()) }
    single { ListMaterialsUc(transactionManager = get(), materialRepository = get(), productQmRepository = get()) }
    single { ListCategoriesUc(transactionManager = get(), categoryRepository = get(), productQmRepository = get()) }
    single { GetCategoryUc(transactionManager = get(), categoryRepository = get(), productQmRepository = get()) }

    // --- address book ---
    single { ListMyAddressesUc(transactionManager = get(), addressBookRepository = get(), geoRepository = get()) }
    single { GetMyAddressUc(transactionManager = get(), addressBookRepository = get(), geoRepository = get()) }
    single { AddAddressUc(transactionManager = get(), addressBookDomainService = get(), addressBookRepository = get(), clock = get()) }
    single { UpdateAddressUc(transactionManager = get(), addressBookDomainService = get(), addressBookRepository = get(), clock = get()) }
    single { DeleteAddressUc(transactionManager = get(), addressBookRepository = get(), clock = get()) }
    single { SetDefaultAddressUc(transactionManager = get(), addressBookRepository = get(), clock = get()) }

    // --- the use cases that take an Idempotency-Key (steps 13 and 15) ---
    single { IdempotencyGuard(store = get(), clock = get()) }

    // --- «رزرو ۴ روزه»: what it means for the cart, checkout, placing and order cards ---
    single {
        Reservations(
            reservationGroupRepository = get(), addressBookRepository = get(), geoRepository = get(), orderQmRepository = get(),
            reservationDomainService = get(),
        )
    }
    single {
        Discounts(discountCodeRepository = get(), discountRedemptionRepository = get(), orderQmRepository = get(), discountDomainService = get())
    }

    // --- cart ---
    single {
        CartCommands(
            transactionManager = get(), cartRepository = get(), cartDomainService = get(), offerQmRepository = get(),
            shopSettingsRepository = get(), reservations = get(), discounts = get(), clock = get(),
        )
    }
    single { OpenCartUc(cartCommands = get(), cartDomainService = get()) }
    single { AddCartItemUc(cartCommands = get(), cartDomainService = get()) }
    single { ChangeCartItemUc(cartCommands = get(), cartDomainService = get()) }
    single { RemoveCartItemUc(cartCommands = get(), cartDomainService = get()) }
    single { ClearCartUc(cartCommands = get(), cartDomainService = get()) }
    single { ChooseShippingMethodUc(cartCommands = get(), cartDomainService = get()) }
    single { SetCartReservationUc(cartCommands = get(), cartDomainService = get()) }
    single { ApplyDiscountCodeUc(cartCommands = get(), cartDomainService = get(), discounts = get()) }
    single { RemoveDiscountCodeUc(cartCommands = get(), cartDomainService = get()) }
    single { MergeGuestCartUc(cartCommands = get(), cartDomainService = get()) }
    single {
        GetCartUc(
            transactionManager = get(), cartRepository = get(), offerQmRepository = get(), shopSettingsRepository = get(), reservations = get(),
            discounts = get(), clock = get(),
        )
    }

    // --- checkout ---
    single {
        GetCheckoutContextUc(
            transactionManager = get(), cartRepository = get(), offerQmRepository = get(), shopSettingsRepository = get(),
            addressBookRepository = get(), geoRepository = get(), userRepository = get(), paymentGateways = get(), reservations = get(),
            discounts = get(), clock = get(),
        )
    }
    single {
        PlaceOrderUc(
            transactionManager = get(), idempotencyGuard = get(), cartRepository = get(), offerQmRepository = get(), shopSettingsRepository = get(),
            addressBookRepository = get(), addressBookDomainService = get(), geoRepository = get(), userRepository = get(),
            orderDomainService = get(), orderRepository = get(), orderQmRepository = get(), inventoryDomainService = get(),
            inventoryRepository = get(), paymentRepository = get(), paymentIdGenerator = get(), paymentGateways = get(), smsSender = get(),
            reservations = get(), discounts = get(), clock = get(),
        )
    }
    single { StartPaymentUc(transactionManager = get(), paymentRepository = get(), paymentGateways = get(), clock = get()) }
    single { GetPaymentInitUc(transactionManager = get(), paymentRepository = get(), orderQmRepository = get()) }

    // --- payments: back from the gateway, and the result page ---
    single {
        PaymentConfirmation(
            transactionManager = get(), paymentRepository = get(), orderRepository = get(), userRepository = get(), paymentDomainService = get(),
            reservationGroupRepository = get(), reservationDomainService = get(), shopSettingsRepository = get(), paymentGateways = get(), smsSender = get(),
            discounts = get(), clock = get(),
        )
    }
    single {
        HandleGatewayCallbackUc(
            transactionManager = get(), paymentRepository = get(), orderRepository = get(), paymentDomainService = get(), paymentGateways = get(),
            paymentConfirmation = get(), clock = get(),
        )
    }
    single {
        StartPaymentAttemptUc(
            transactionManager = get(), idempotencyGuard = get(), paymentRepository = get(), orderRepository = get(), orderQmRepository = get(),
            shopSettingsRepository = get(), paymentDomainService = get(), paymentGateways = get(), clock = get(),
        )
    }
    single {
        GetPaymentResultUc(
            transactionManager = get(), paymentRepository = get(), orderQmRepository = get(), shopSettingsRepository = get(),
            reservationGroupRepository = get(), clock = get(),
        )
    }

    // --- the scheduled jobs (roadmap section 15) ---
    single { FindExpirableOrdersUc(transactionManager = get(), orderQmRepository = get(), clock = get()) }
    single {
        ExpireUnpaidOrderUc(
            transactionManager = get(), paymentRepository = get(), orderRepository = get(), paymentDomainService = get(), inventoryDomainService = get(),
            inventoryRepository = get(), discounts = get(), clock = get(),
        )
    }
    single { FindStalePaymentsUc(transactionManager = get(), paymentRepository = get(), clock = get()) }
    single {
        ReconcilePaymentUc(
            transactionManager = get(), paymentRepository = get(), orderRepository = get(), paymentDomainService = get(), paymentGateways = get(),
            paymentConfirmation = get(), clock = get(),
        )
    }
    single { DeleteStaleGuestCartsUc(transactionManager = get(), cartRepository = get(), clock = get()) }
    single { DeleteOldIdempotencyKeysUc(transactionManager = get(), idempotencyStore = get(), clock = get()) }
    single { FindDueReservationGroupsUc(transactionManager = get(), reservationGroupRepository = get(), clock = get()) }
    single {
        ReleaseReservationGroupUc(
            transactionManager = get(), reservationGroupRepository = get(), orderRepository = get(), orderQmRepository = get(),
            reservationDomainService = get(), clock = get(),
        )
    }
    single { GetMyReservationUc(transactionManager = get(), reservations = get(), clock = get()) }

    // --- orders ---
    single { ListMyOrdersUc(transactionManager = get(), orderQmRepository = get(), shopSettingsRepository = get(), reservations = get(), clock = get()) }
    single {
        GetMyOrderUc(
            transactionManager = get(), orderQmRepository = get(), paymentRepository = get(), shopSettingsRepository = get(), colorRepository = get(),
            reservations = get(), clock = get(),
        )
    }
    single { ReorderUc(transactionManager = get(), orderQmRepository = get(), cartCommands = get(), cartDomainService = get()) }
    single { GetOrderSummaryUc(transactionManager = get(), orderQmRepository = get(), shopSettingsRepository = get(), reservations = get(), clock = get()) }

    // --- geo: queries ---
    single { ListProvincesUc(transactionManager = get(), geoRepository = get()) }
    single { ListCitiesOfProvinceUc(transactionManager = get(), geoRepository = get()) }

    // --- product: queries ---
    single {
        FindProductsUc(
            transactionManager = get(),
            productQmRepository = get(),
            categoryRepository = get(),
            colorRepository = get(),
            materialRepository = get(),
            clock = get(),
            shopSettingsRepository = get(),
        )
    }
    single {
        GetProductFacetsUc(
            transactionManager = get(),
            productQmRepository = get(),
            categoryRepository = get(),
            colorRepository = get(),
            materialRepository = get(),
            clock = get(),
        )
    }
    single {
        FindRelatedProductsUc(
            transactionManager = get(),
            productQmRepository = get(),
            categoryRepository = get(),
            colorRepository = get(),
            clock = get(),
            shopSettingsRepository = get(),
        )
    }

    single {
        GetProductUc(
            transactionManager = get(),
            productQmRepository = get(),
            categoryRepository = get(),
            materialRepository = get(),
            colorRepository = get(),
            clock = get(),
            shopSettingsRepository = get(),
        )
    }

    // --- product: commands ---
    single {
        CreateProductUc(
            transactionManager = get(),
            productDomainService = get(),
            productRepository = get(),
            productQmRepository = get(),
            htmlSanitizer = get(),
            clock = get(),
        )
    }

    // --- auth: commands ---
    single {
        OtpIssuer(
            otpChallengeRepository = get(),
            otpCodes = get(),
            securityEventLog = get(),
            maxSmsPerHour = get<AppConfig>().auth.maxSmsPerHour,
        )
    }
    single { SendLoginCodeUc(transactionManager = get(), otpIssuer = get(), smsSender = get(), clock = get()) }
    single {
        VerifyLoginCodeUc(
            transactionManager = get(),
            otpChallengeRepository = get(),
            userRepository = get(),
            authSessionRepository = get(),
            otpCodes = get(),
            sessionTokens = get(),
            accessTokens = get(),
            securityEventLog = get(),
            clock = get(),
        )
    }

    single {
        RefreshSessionUc(
            transactionManager = get(),
            authSessionRepository = get(),
            userRepository = get(),
            sessionTokens = get(),
            accessTokens = get(),
            securityEventLog = get(),
            clock = get(),
        )
    }
    single { LogoutUc(transactionManager = get(), authSessionRepository = get(), sessionTokens = get(), securityEventLog = get(), clock = get()) }

    // --- account ---
    single { GetMeUc(transactionManager = get(), userRepository = get()) }
    single { UpdateMyProfileUc(transactionManager = get(), userRepository = get(), clock = get()) }
    single {
        RequestPhoneChangeUc(
            transactionManager = get(),
            userRepository = get(),
            authSessionRepository = get(),
            otpIssuer = get(),
            smsSender = get(),
            clock = get(),
        )
    }
    single {
        ConfirmPhoneChangeUc(
            transactionManager = get(),
            otpChallengeRepository = get(),
            userRepository = get(),
            authSessionRepository = get(),
            otpCodes = get(),
            sessionTokens = get(),
            accessTokens = get(),
            smsSender = get(),
            securityEventLog = get(),
            clock = get(),
        )
    }

    // --- admin: the guard of every panel request ---
    single {
        CheckPanelAccessUc(
            transactionManager = get(),
            authSessionRepository = get(),
            userRepository = get(),
            securityEventLog = get(),
            clock = get(),
        )
    }

    // --- admin: customers and roles ---
    single { FindCustomersUc(transactionManager = get(), adminCustomerRepository = get(), orderQmRepository = get()) }
    single { GetCustomerUc(transactionManager = get(), adminCustomerRepository = get(), orderQmRepository = get()) }
    single {
        SetUserRolesUc(
            transactionManager = get(),
            userRepository = get(),
            authSessionRepository = get(),
            securityEventLog = get(),
            auditTrail = get(),
            clock = get(),
        )
    }
    single {
        BootstrapAdminUc(transactionManager = get(), userRepository = get(), authSessionRepository = get(), securityEventLog = get(), clock = get())
    }

    // --- admin: the change log ---
    single { ListAuditLogUc(transactionManager = get(), auditLogQmRepository = get()) }
    single { DeleteOldAuditEntriesUc(transactionManager = get(), auditTrail = get(), clock = get()) }

    // --- admin: uploads ---
    single {
        UploadMediaUc(
            transactionManager = get(),
            mediaProcessor = get(),
            mediaStorage = get(),
            mediaRepository = get(),
            mediaIdGenerator = get(),
            clock = get(),
        )
    }
    single { FindUnattachedMediaUc(transactionManager = get(), mediaRepository = get(), clock = get()) }
    single { DeleteUnattachedMediaUc(transactionManager = get(), mediaRepository = get(), mediaStorage = get(), clock = get()) }

    // --- inventory: commands ---
    single {
        AdjustInventoryUc(
            transactionManager = get(),
            inventoryDomainService = get(),
            inventoryRepository = get(),
            productQmRepository = get(),
            clock = get(),
        )
    }
}
