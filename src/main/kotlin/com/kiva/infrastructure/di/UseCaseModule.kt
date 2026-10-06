package com.kiva.infrastructure.di

import com.kiva.application.usecase.catalog.usecase.query.GetCategoryUc
import com.kiva.application.usecase.catalog.usecase.query.ListCategoriesUc
import com.kiva.application.usecase.catalog.usecase.query.ListColorsUc
import com.kiva.application.usecase.catalog.usecase.query.ListMaterialsUc
import org.koin.dsl.module

val useCaseModule = module(createdAtStart = true) {
    // --- catalog: queries ---
    single { ListColorsUc(transactionManager = get(), colorRepository = get()) }
    single { ListMaterialsUc(transactionManager = get(), materialRepository = get()) }
    single { ListCategoriesUc(transactionManager = get(), categoryRepository = get()) }
    single { GetCategoryUc(transactionManager = get(), categoryRepository = get()) }
}
