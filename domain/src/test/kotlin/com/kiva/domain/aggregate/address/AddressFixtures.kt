package com.kiva.domain.aggregate.address

import com.kiva.domain.aggregate.address.model.AddressInput

/** A correct address in Tehran (province 8, city 394); each test changes only what it is about. */
fun addressInput(
    provinceId: Int = 8,
    cityId: Int = 394,
    addressLine: String = "خیابان ولیعصر، کوچه نسترن، پلاک ۱۲",
    recipientName: String = "سارا محمدی",
): AddressInput = AddressInput.makeNew(
    title = "خانه",
    provinceId = provinceId,
    cityId = cityId,
    addressLine = addressLine,
    plaque = "12",
    unit = "4",
    postalCode = "1965843117",
    recipientName = recipientName,
    recipientPhone = "09123456789",
    isSelfRecipient = true,
    lat = null,
    lng = null,
).getOrNull() ?: error("the fixture address is refused")
