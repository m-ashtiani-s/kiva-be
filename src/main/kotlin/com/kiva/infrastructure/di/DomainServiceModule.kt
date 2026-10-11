package com.kiva.infrastructure.di

import com.kiva.domain.aggregate.address.AddressBookDomainService
import com.kiva.domain.aggregate.inventory.InventoryDomainService
import com.kiva.domain.aggregate.product.ProductDomainService
import org.koin.dsl.module

/** Domain services, built from the ports in daoModule. */
val domainServiceModule = module(createdAtStart = true) {
    single {
        ProductDomainService(
            productRepository = get(),
            productIdGenerator = get(),
            categoryRepository = get(),
            materialRepository = get(),
            colorRepository = get(),
        )
    }
    single { InventoryDomainService(inventoryRepository = get()) }
    single { AddressBookDomainService(geoRepository = get(), addressIdGenerator = get()) }
}
