package com.kiva.infrastructure.di

import com.kiva.application.usecase.account.usecase.command.UpdateMyProfileUc
import com.kiva.application.usecase.account.usecase.query.GetMeUc
import com.kiva.application.usecase.admin.usecase.command.CheckPanelAccessUc
import com.kiva.application.usecase.admin.usecase.command.SetUserRolesUc
import com.kiva.application.usecase.admin.usecase.query.FindCustomersUc
import com.kiva.application.usecase.admin.usecase.query.GetCustomerUc
import com.kiva.application.usecase.auth.usecase.command.BootstrapAdminUc
import com.kiva.application.usecase.auth.usecase.command.LogoutUc
import com.kiva.application.usecase.auth.usecase.command.RefreshSessionUc
import com.kiva.application.usecase.auth.usecase.command.SendLoginCodeUc
import com.kiva.application.usecase.auth.usecase.command.VerifyLoginCodeUc
import com.kiva.application.usecase.catalog.usecase.query.GetCategoryUc
import com.kiva.application.usecase.catalog.usecase.query.ListCategoriesUc
import com.kiva.application.usecase.catalog.usecase.query.ListColorsUc
import com.kiva.application.usecase.catalog.usecase.query.ListMaterialsUc
import com.kiva.application.usecase.inventory.usecase.command.AdjustInventoryUc
import com.kiva.application.usecase.product.usecase.command.CreateProductUc
import com.kiva.application.usecase.product.usecase.query.FindProductsUc
import com.kiva.application.usecase.product.usecase.query.FindRelatedProductsUc
import com.kiva.application.usecase.product.usecase.query.GetProductFacetsUc
import com.kiva.application.usecase.product.usecase.query.GetProductUc
import com.kiva.infrastructure.config.AppConfig
import org.koin.dsl.module

val useCaseModule = module(createdAtStart = true) {
    // --- catalog: queries ---
    single { ListColorsUc(transactionManager = get(), colorRepository = get(), productQmRepository = get()) }
    single { ListMaterialsUc(transactionManager = get(), materialRepository = get(), productQmRepository = get()) }
    single { ListCategoriesUc(transactionManager = get(), categoryRepository = get(), productQmRepository = get()) }
    single { GetCategoryUc(transactionManager = get(), categoryRepository = get(), productQmRepository = get()) }

    // --- product: queries ---
    single {
        FindProductsUc(
            transactionManager = get(),
            productQmRepository = get(),
            categoryRepository = get(),
            colorRepository = get(),
            materialRepository = get(),
            clock = get(),
            freeShippingThreshold = get<AppConfig>().shop.freeShippingThreshold,
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
            freeShippingThreshold = get<AppConfig>().shop.freeShippingThreshold,
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
            freeShippingThreshold = get<AppConfig>().shop.freeShippingThreshold,
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
        SendLoginCodeUc(
            transactionManager = get(),
            otpChallengeRepository = get(),
            otpCodes = get(),
            smsSender = get(),
            securityEventLog = get(),
            clock = get(),
            maxSmsPerHour = get<AppConfig>().auth.maxSmsPerHour,
        )
    }
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
    single { FindCustomersUc(transactionManager = get(), adminCustomerRepository = get()) }
    single { GetCustomerUc(transactionManager = get(), adminCustomerRepository = get()) }
    single {
        SetUserRolesUc(transactionManager = get(), userRepository = get(), authSessionRepository = get(), securityEventLog = get(), clock = get())
    }
    single {
        BootstrapAdminUc(transactionManager = get(), userRepository = get(), authSessionRepository = get(), securityEventLog = get(), clock = get())
    }

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
