package com.kiva.application.usecase.account

import com.kiva.application.port.IdempotencyStore
import com.kiva.domain.aggregate.address.repository.AddressBookRepository
import com.kiva.domain.aggregate.cart.repository.CartRepository
import com.kiva.domain.aggregate.contact.repository.ContactMessageRepository
import com.kiva.domain.aggregate.newsletter.repository.NewsletterSubscriptionRepository
import com.kiva.domain.aggregate.review.repository.ReviewRepository
import com.kiva.domain.aggregate.stockalert.repository.StockAlertRepository
import com.kiva.domain.aggregate.user.entity.User
import com.kiva.domain.aggregate.wishlist.repository.WishlistRepository
import com.kiva.domain.aggregate.wishlist.repository.WishlistShareRepository

/**
 * The list of what goes with a deleted account (roadmap phase 14, decision 4), in one place: everything that is only
 * the customer's own and no financial record is deleted; what others still read is made anonymous.
 *
 * | what | |
 * |---|---|
 * | addresses, the cart, the wishlist and its share link, «موجود شد خبرم کن», `Idempotency-Key`s, messages of the contact form sent logged in, the newsletter subscription of the account's email | deleted |
 * | reviews | kept, without the author's name («کاربر کیوا») and contact |
 *
 * Not here, on purpose: the orders (each made anonymous by its own event, by the use case, lock 8), the account's row
 * (User.erase), its sessions (revoked), returns, refunds and payments (financial records, kept as they are), the
 * security log (append-only; it gets an ACCOUNT_DELETED row), media attached to a review or a return (they go with
 * those), and the votes «مفید بود» on others' reviews (just a count once the account has no name).
 *
 * **Every table added later that keeps a user's own data gets a line here** (roadmap section 4.10), and a row in
 * AccountErasureOnDatabaseTest.
 *
 * Runs inside the caller's transaction, under its locks (the cart, the wishlist, the address book), before the account
 * itself is erased: the newsletter is found by the account's email.
 */
class AccountEraser(
    private val addressBookRepository: AddressBookRepository,
    private val cartRepository: CartRepository,
    private val wishlistRepository: WishlistRepository,
    private val wishlistShareRepository: WishlistShareRepository,
    private val stockAlertRepository: StockAlertRepository,
    private val idempotencyStore: IdempotencyStore,
    private val contactMessageRepository: ContactMessageRepository,
    private val newsletterSubscriptionRepository: NewsletterSubscriptionRepository,
    private val reviewRepository: ReviewRepository,
) {

    /** How many rows of each kind went or were made anonymous: numbers only, for the log. */
    data class Erased(
        val addresses: Int,
        val cart: Boolean,
        val wishlistItems: Int,
        val stockAlerts: Int,
        val idempotencyKeys: Int,
        val contactMessages: Int,
        val newsletter: Boolean,
        val reviewsAnonymized: Int,
    )

    /** Deletes and anonymizes what decision 4 says of [user], a live account about to be erased. */
    fun eraseAllOf(user: User): Erased {
        check(!user.isDeleted) { "User ${user.id.value} is erased already" }
        val userId = user.id
        val erased = Erased(
            addresses = addressBookRepository.deleteAllOf(userId),
            cart = cartRepository.deleteOfUser(userId),
            wishlistItems = wishlistRepository.deleteAllOf(userId),
            stockAlerts = stockAlertRepository.deleteAllOf(userId),
            idempotencyKeys = idempotencyStore.deleteAllOf(userId),
            contactMessages = contactMessageRepository.deleteAllOf(userId),
            newsletter = user.email?.let(newsletterSubscriptionRepository::delete) ?: false,
            reviewsAnonymized = reviewRepository.anonymizeAllOf(userId),
        )
        wishlistShareRepository.delete(userId)
        return erased
    }
}
