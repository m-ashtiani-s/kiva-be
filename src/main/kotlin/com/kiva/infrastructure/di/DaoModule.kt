package com.kiva.infrastructure.di

import com.kiva.domain.aggregate.category.repository.CategoryRepository
import com.kiva.domain.aggregate.color.repository.ColorRepository
import com.kiva.domain.aggregate.material.repository.MaterialRepository
import com.kiva.domain.aggregate.product.repository.ProductIdGenerator
import com.kiva.domain.aggregate.product.repository.ProductRepository
import com.kiva.infrastructure.persistence.exposed.CategoryRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.ColorRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.MaterialRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.ProductIdGeneratorImpl
import com.kiva.infrastructure.persistence.exposed.ProductRepositoryImpl
import org.koin.dsl.module

/** Repository implementations, bound to the ports the domain and application layers ask for. */
val daoModule = module(createdAtStart = true) {
    single<ColorRepository> { ColorRepositoryImpl() }
    single<CategoryRepository> { CategoryRepositoryImpl() }
    single<MaterialRepository> { MaterialRepositoryImpl() }
    single<ProductRepository> { ProductRepositoryImpl() }
    single<ProductIdGenerator> { ProductIdGeneratorImpl() }
}
