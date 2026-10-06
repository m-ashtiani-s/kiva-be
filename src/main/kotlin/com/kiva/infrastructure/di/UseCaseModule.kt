package com.kiva.infrastructure.di

import com.kiva.application.usecase.catalog.usecase.query.GetCategoryUc
import com.kiva.application.usecase.catalog.usecase.query.ListCategoriesUc
import com.kiva.application.usecase.catalog.usecase.query.ListColorsUc
import com.kiva.application.usecase.catalog.usecase.query.ListMaterialsUc
import com.kiva.application.usecase.inventory.usecase.command.AdjustInventoryUc
import com.kiva.application.usecase.product.usecase.command.CreateProductUc
import org.koin.dsl.module

val useCaseModule = module(createdAtStart = true) {
    // --- catalog: queries ---
    single { ListColorsUc(transactionManager = get(), colorRepository = get(), productQmRepository = get()) }
    single { ListMaterialsUc(transactionManager = get(), materialRepository = get(), productQmRepository = get()) }
    single { ListCategoriesUc(transactionManager = get(), categoryRepository = get(), productQmRepository = get()) }
    single { GetCategoryUc(transactionManager = get(), categoryRepository = get(), productQmRepository = get()) }

    // --- product: commands ---
    single {
        CreateProductUc(
            transactionManager = get(),
            productDomainService = get(),
            productRepository = get(),
            productQmRepository = get(),
            clock = get(),
        )
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
