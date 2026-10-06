package com.kiva.infrastructure.config

import com.kiva.common.valueobject.Money
import io.ktor.server.config.ApplicationConfig

data class AppConfig(
    val datasource: DatasourceConfig,
    val shop: ShopConfig = ShopConfig.DEFAULT,
    val seed: SeedConfig = SeedConfig(enabled = false),
) {
    companion object {
        fun from(config: ApplicationConfig): AppConfig {
            val ds = config.config("kiva.datasource")
            return AppConfig(
                datasource = DatasourceConfig(
                    url = ds.property("url").getString(),
                    username = ds.property("username").getString(),
                    password = ds.property("password").getString(),
                    maxPoolSize = ds.property("maxPoolSize").getString().toInt(),
                ),
                shop = ShopConfig(
                    freeShippingThreshold = Money.constructFromPersisted(config.property("kiva.shop.freeShippingThreshold").getString().toLong()),
                    siteUrl = config.property("kiva.shop.siteUrl").getString(),
                ),
                // Strict: a typo such as "yes" stops the boot instead of quietly meaning false.
                seed = SeedConfig(enabled = config.property("kiva.seed.enabled").getString().toBooleanStrict()),
            )
        }
    }
}

/**
 * Shop rules from config, until the admin settings keep them.
 *
 * @property siteUrl the shop's own address (`https://kiva.ir`), for the canonical and share links of pages.
 */
data class ShopConfig(val freeShippingThreshold: Money, val siteUrl: String) {
    companion object {
        /** The contract's numbers. */
        val DEFAULT = ShopConfig(freeShippingThreshold = Money.constructFromPersisted(3_000_000), siteUrl = "https://kiva.ir")
    }
}

/** @property enabled run DevCatalogSeeder on startup. Local development only. */
data class SeedConfig(val enabled: Boolean)

data class DatasourceConfig(
    val url: String,
    val username: String,
    val password: String,
    val maxPoolSize: Int,
) {
    // Keep the password out of logs if this object is ever printed.
    override fun toString() = "DatasourceConfig(url=$url, username=$username, maxPoolSize=$maxPoolSize)"
}
