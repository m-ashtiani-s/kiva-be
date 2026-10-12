package com.kiva.infrastructure.di

import com.kiva.domain.aggregate.address.AddressBookDomainService
import com.kiva.domain.aggregate.cart.CartDomainService
import com.kiva.domain.aggregate.inventory.InventoryDomainService
import com.kiva.domain.aggregate.order.OrderDomainService
import com.kiva.domain.aggregate.payment.PaymentDomainService
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
    single { CartDomainService(cartIdGenerator = get()) }
    single { OrderDomainService(orderIdGenerator = get()) }
    single { PaymentDomainService(paymentIdGenerator = get()) }
}
