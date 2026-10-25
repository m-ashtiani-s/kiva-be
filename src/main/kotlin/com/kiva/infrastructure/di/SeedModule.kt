package com.kiva.infrastructure.di

import com.kiva.infrastructure.seed.DevCatalogSeeder
import com.kiva.infrastructure.seed.DevDiscountCodeSeeder
import org.koin.dsl.module

/** The development seeders. Built always, so their wiring is checked; run only when kiva.seed.enabled is on. */
val seedModule = module(createdAtStart = true) {
    single {
        DevCatalogSeeder(
            createProductUc = get(),
            listCategoriesUc = get(),
            listMaterialsUc = get(),
            clock = get(),
        )
    }
    single { DevDiscountCodeSeeder(transactionManager = get(), clock = get()) }
}
