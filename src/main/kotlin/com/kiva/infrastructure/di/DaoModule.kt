package com.kiva.infrastructure.di

import com.kiva.application.port.SecurityEventLog
import com.kiva.application.repository.AdminCustomerRepository
import com.kiva.application.repository.ProductQmRepository
import com.kiva.domain.aggregate.address.repository.AddressBookRepository
import com.kiva.domain.aggregate.address.repository.AddressIdGenerator
import com.kiva.domain.aggregate.otp.repository.OtpChallengeRepository
import com.kiva.domain.aggregate.session.repository.AuthSessionRepository
import com.kiva.domain.aggregate.category.repository.CategoryRepository
import com.kiva.domain.aggregate.color.repository.ColorRepository
import com.kiva.domain.aggregate.geo.repository.GeoRepository
import com.kiva.domain.aggregate.inventory.repository.InventoryRepository
import com.kiva.domain.aggregate.material.repository.MaterialRepository
import com.kiva.domain.aggregate.product.repository.ProductIdGenerator
import com.kiva.domain.aggregate.product.repository.ProductRepository
import com.kiva.domain.aggregate.settings.repository.ShopSettingsRepository
import com.kiva.domain.aggregate.user.repository.UserRepository
import com.kiva.infrastructure.persistence.exposed.AddressBookRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.AddressIdGeneratorImpl
import com.kiva.infrastructure.persistence.exposed.AdminCustomerRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.AuthSessionRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.CategoryRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.ColorRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.GeoRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.InventoryRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.MaterialRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.OtpChallengeRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.SecurityEventLogImpl
import com.kiva.infrastructure.persistence.exposed.ProductIdGeneratorImpl
import com.kiva.infrastructure.persistence.exposed.ProductQmRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.ProductRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.ShopSettingsRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.UserRepositoryImpl
import org.koin.dsl.module

/**
 * Repository implementations, bound to the ports the domain and application layers ask for (the read model's and the
 * security log's too).
 */
val daoModule = module(createdAtStart = true) {
    single<ColorRepository> { ColorRepositoryImpl() }
    single<CategoryRepository> { CategoryRepositoryImpl() }
    single<MaterialRepository> { MaterialRepositoryImpl() }
    single<ProductRepository> { ProductRepositoryImpl() }
    single<ProductIdGenerator> { ProductIdGeneratorImpl() }
    single<InventoryRepository> { InventoryRepositoryImpl() }
    single<ProductQmRepository> { ProductQmRepositoryImpl() }
    single<UserRepository> { UserRepositoryImpl() }
    single<OtpChallengeRepository> { OtpChallengeRepositoryImpl() }
    single<AuthSessionRepository> { AuthSessionRepositoryImpl() }
    single<SecurityEventLog> { SecurityEventLogImpl() }
    single<AdminCustomerRepository> { AdminCustomerRepositoryImpl() }
    single<GeoRepository> { GeoRepositoryImpl() }
    single<ShopSettingsRepository> { ShopSettingsRepositoryImpl() }
    single<AddressBookRepository> { AddressBookRepositoryImpl() }
    single<AddressIdGenerator> { AddressIdGeneratorImpl() }
}
