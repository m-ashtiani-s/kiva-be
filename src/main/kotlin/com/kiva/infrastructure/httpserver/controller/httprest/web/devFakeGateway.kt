package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.infrastructure.common.http.toPersianNumber
import com.kiva.infrastructure.payment.FakePaymentGateway
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

/** The path parameter of the pretend bank's page. */
private const val AUTHORITY = "authority"

/**
 * The pretend bank's page of the fake gateway (roadmap section 10.4), outside `/api/v1` because it is not the shop's:
 * it is mounted only when `kiva.payment.provider = fake`. It shows the amount and the order code with «پرداخت موفق» and
 * «انصراف»; each button records what the shopper did and sends them back to the same callback Zarinpal would.
 */
fun Route.devFakeGateway(gateway: FakePaymentGateway) {
    route("/dev/fake-gateway/{$AUTHORITY}") {
        get {
            val authority = call.authority()
            val session = gateway.session(authority) ?: return@get call.respondNotFound()
            call.respondText(pageOf(authority, session), ContentType.Text.Html.withParameter("charset", "utf-8"))
        }
        post("/pay") { call.press(gateway, paid = true) }
        post("/cancel") { call.press(gateway, paid = false) }
    }
}

private suspend fun ApplicationCall.press(gateway: FakePaymentGateway, paid: Boolean) {
    val authority = authority()
    if (gateway.session(authority) == null) return respondNotFound()
    val done = if (paid) gateway.pay(authority) else gateway.cancel(authority)
    if (!done) return respondText("این پرداخت قبلاً به شکل دیگه‌ای تموم شده.", ContentType.Text.Plain, HttpStatusCode.Conflict)
    respondRedirect(gateway.callbackUrl(authority, paid))
}

private suspend fun ApplicationCall.respondNotFound() =
    respondText("چنین پرداختی در درگاه آزمایشی نیست.", ContentType.Text.Plain, HttpStatusCode.NotFound)

// Always present: the routes only match when the segment is there.
private fun ApplicationCall.authority(): String = parameters[AUTHORITY].orEmpty()

// Every value put in the page is the gateway's own: an authority it made (letters, digits, -), an order code and a
// number. Nothing a shopper typed, so nothing to escape.
private fun pageOf(authority: String, session: FakePaymentGateway.Session): String = """
    <!doctype html>
    <html lang="fa" dir="rtl">
    <head>
      <meta charset="utf-8">
      <meta name="viewport" content="width=device-width, initial-scale=1">
      <title>درگاه پرداخت آزمایشی</title>
      <style>
        body { font-family: Tahoma, sans-serif; background: #f4f1ec; color: #2b2b2b; display: flex; justify-content: center; padding: 48px 16px; }
        main { background: #fff; border-radius: 12px; padding: 32px; max-width: 420px; width: 100%; box-shadow: 0 2px 12px rgba(0, 0, 0, .08); }
        .warn { background: #fff4d6; border-radius: 8px; padding: 8px 12px; font-size: 13px; }
        .amount { font-size: 28px; font-weight: bold; margin: 16px 0 4px; }
        form { display: inline; }
        button { border: 0; border-radius: 8px; padding: 12px 20px; font-size: 15px; cursor: pointer; margin-left: 8px; }
        .pay { background: #2e7d32; color: #fff; }
        .cancel { background: #e0e0e0; }
      </style>
    </head>
    <body>
    <main>
      <p class="warn">درگاه آزمایشی: هیچ پولی جابه‌جا نمی‌شه. فقط برای توسعه.</p>
      <p>سفارش ${session.orderCode.value}</p>
      <p class="amount">${session.amount.toman.toPersianNumber()} تومان</p>
      <p>وضعیت: ${session.state.name}</p>
      <form method="post" action="/dev/fake-gateway/$authority/pay"><button class="pay" type="submit">پرداخت موفق</button></form>
      <form method="post" action="/dev/fake-gateway/$authority/cancel"><button class="cancel" type="submit">انصراف</button></form>
    </main>
    </body>
    </html>
""".trimIndent()
