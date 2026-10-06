package com.kiva.infrastructure.di

import com.kiva.infrastructure.seed.DevCatalogSeeder
import org.koin.dsl.module

/** The development seeder. Built always, so its wiring is checked; run only when kiva.seed.enabled is on. */
val seedModule = module(createdAtStart = true) {
    single {
        DevCatalogSeeder(
            createProductUc = get(),
            adjustInventoryUc = get(),
            listCategoriesUc = get(),
            listMaterialsUc = get(),
            clock = get(),
        )
    }
}
