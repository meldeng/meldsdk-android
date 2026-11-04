# MeldSDK (Android)

Present a crypto on-ramp provider's payment UI in your Android app: an embedded card widget
(Mercuryo, Uphold, Banxa) or a Google Pay sheet (Coinbase). The native counterpart of
[meldsdk-ios](https://github.com/meldeng/meldsdk-ios); same public API shape as the web SDK
(`@meldcrypto/sdk`).

MeldSDK mounts the order's payment UI and relays its lifecycle events, with one uniform call:
`Meld.mount(order, host, handlers)` for a widget that renders into a view you own, or
`Meld.mount(order, activity, handlers)` for a wallet sheet that draws into none. It never renders
or transports card data — the provider's own page does, loaded over HTTPS in a `WebView`.
Before the order exists, `Meld.ensureReady` runs what the customer still needs for the providers you
offer (see [Customer requirements](#customer-requirements-before-the-order)).

## Install

Add the dependency (published to Maven Central as `io.meld:meldsdk`):

```kotlin
// app/build.gradle.kts
dependencies {
    implementation("io.meld:meldsdk:0.10.0")
}
```

`mavenCentral()` is in the default repositories of new Android projects; add it if yours doesn't
have it. Requires `minSdk 24`, `compileSdk 34` or later and Android Gradle plugin 8.1.1 or later
(set by `androidx.webkit` 1.14.0). The library declares `INTERNET` and `CAMERA` permissions (camera
is used for in-widget KYC document/selfie capture). Google Pay adds more; see
[Google Pay](#google-pay).

## Usage

```kotlin
Meld.configure(MeldEnvironment.SANDBOX)

val order = MeldOrder.fromJson(backendOrderJson) // POST /crypto/order/headless, passed through

val handlers = MeldEventHandlers(
    onReady = { orderId -> /* widget loaded, or the wallet sheet was requested */ },
    onPaymentSubmitted = { orderId -> /* UX hint — user finished the flow, NOT settlement */ },
    onStatusChange = { e -> /* code against e.status (normalized), not e.providerStatus */ },
    onCancel = { orderId -> },
    onError = { e -> /* e.code, e.message, e.recoverable, e.attemptMayExist, e.paymentMethodType */ },
)

// Guard before mounting.
val capabilities = Meld.capabilities(order)
if (capabilities.surface != "unsupported") {
    val handle = if (capabilities.embeddable) {
        Meld.mount(order, host, handlers) // a ViewGroup you own, sized for the widget
    } else {
        Meld.mount(order, activity, handlers) // a wallet sheet; nothing is drawn into your views
    }

    // On teardown (navigation, dismissal):
    handle.unmount()
}
```

Configure the Meld backend your server created the order on: `SANDBOX` (`api-sb.meld.io`), `QA`
(`api-qa.meld.io`) or `PRODUCTION` (`api.meld.io`). The SDK checks a `QA` order's provider hosts
as it checks a `SANDBOX` one. `QA` is a new entry, so an exhaustive `when` over `MeldEnvironment`
with no `else` branch needs a `QA` case.

Branch only on two things:

- **`capabilities.surface == "unsupported"`:** this SDK build cannot present the order here. Do not
  mount it; offer another method.
- **`capabilities.embeddable`:** true means the payment UI renders into `host`, so give it room.
  False means it draws into none of your views; mount it with `Meld.mount(order, activity,
  handlers)`. An embeddable order mounted that way throws `MeldMountException.MissingHost`.

Every other `surface` value is informational. Android reports `embedded` and `native-wallet`, iOS
reports others such as `native-applepay`, and new values may appear without notice, so never
branch on them. `requiresUserGesture` true means: mount from the customer's tap.

All callbacks are invoked on the main thread.

## Events

| Event | Fires when | Do |
|---|---|---|
| `onReady` | Widget document loaded, or the wallet sheet was requested | Hide spinner |
| `onPaymentSubmitted` | User finished the provider payment flow — **exactly once per mount** (UX hint only) | Unmount, show "processing" |
| `onStatusChange` | Order status changed; `e.status` is `PENDING` \| `COMPLETED` \| `FAILED` \| `CANCELLED` | React to status; `COMPLETED` = provider "order complete" (still not settlement). Dropped after the terminal callback, so a `COMPLETED` after `onPaymentSubmitted` never arrives |
| `onCancel` | User cancelled | Show retry CTA |
| `onError` | Load failure, bad order, or terminal `FAILED` status | Show error; `e.recoverable` says retry vs. new order |

Each mount delivers exactly one terminal callback — `onPaymentSubmitted`, `onCancel` or a
non-recoverable `onError` — and drops every callback after it. `onPaymentSubmitted` fires once and
only once, however the provider signals it. Some send a "payment finished" message and never a
status; some report `COMPLETED` and never a finished message; some send both, in either order. The
SDK collapses that into a single callback, so you do not need a `settledOnce` guard of your own. A
`FAILED`/`CANCELLED` status blocks the submission, so a failure is never followed by one. A session
that ends without a terminal callback, while you still hold the handle, reports `onError` with
`PAYMENT_OUTCOME_UNKNOWN`, or `PAYMENT_REJECTED` after a `FAILED` status.

`status` is normalized across providers — code against it, not the raw provider string (in
`e.providerStatus`). A terminal `FAILED` also fires `onError`, and a `CANCELLED` also fires
`onCancel`. Every callback also receives the `orderId`.

## Error codes

`MeldErrorCode` names the normalized `e.code` values. The provider's own event and code travel in
`e.detail`. The codes and their `attemptMayExist` values are the same in meldsdk-ios 0.10.0,
`@meldcrypto/react-native-sdk` 0.10.0 and the web SDK `@meldcrypto/sdk` 0.7.0.

`e.paymentMethodType` is the mounted order's `paymentMethodType`, such as `GOOGLE_PAY`, or null
when the order has none. Word copy such as "Google Pay isn't available" from it, without
branching on the code.

| `e.code` | `e.attemptMayExist` | Means | Do |
|---|---|---|---|
| `PAYMENT_METHOD_UNAVAILABLE` | false | This device or customer cannot pay with the order's method | Offer another method |
| `PRESENTATION_FAILED` | false | The payment UI never came up | Create a new order for the next attempt |
| `PAYMENT_REJECTED` | false | Declined; nothing will settle | Offer another option |
| `ORDER_STATE_CHANGED` | false | The provider no longer accepts this order | Create a new order |
| `VERIFICATION_PENDING` | false | The provider is reviewing the customer | Tell the customer; no payment was made |
| `PAYMENT_OUTCOME_UNKNOWN` | true | A payment attempt may exist | Track the order and never pay it again |
| `PROVIDER_LOAD_FAILED` | true | The provider's page did not load | If `e.recoverable`, remounting may work; otherwise track the order before offering a new one |

Any code this build does not know has `attemptMayExist` true. Android never emits
`VERIFICATION_PENDING` today; it is listed so code shared across platforms reads it the same way.

Android adds no normalized code of its own. The Banxa and Uphold card widgets run a provider SDK
bundled with this one inside the web view. When that page fails before the widget shows, they
report:

| Failure | `e.code` | `e.detail` |
|---|---|---|
| The provider SDK did not load (`sdk_unavailable`) | `PROVIDER_LOAD_FAILED` | `banxa_card_failed:sdk_unavailable` or `uphold_card_failed:sdk_unavailable` |
| Mounting the widget threw (`mount_failed`) | `PRESENTATION_FAILED` | `banxa_card_failed:mount_failed` or `uphold_card_failed:mount_failed` |
| Primer refused the Banxa client token (`checkout_init_failed`) | `PRESENTATION_FAILED` | `banxa_card_failed:checkout_init_failed` |

Releases before 0.8.0 reported these failures as their own codes, with no `e.detail`.

The card adapters also pass through provider-level codes, each with `attemptMayExist` true:

| `e.code` | From | `e.attemptMayExist` |
|---|---|---|
| `authorize_session_invalid`, `authorize_session_failed` | Uphold, moving from card capture to authorization | true |
| The widget's own error code, or `error` | Uphold and Banxa widget errors | true |
| Mercuryo's terminal status, such as `failed` or `rejected` | Mercuryo | true |

## Settlement — webhook, never the SDK

Neither `onPaymentSubmitted` nor `onStatusChange` with `COMPLETED` is settlement — both are
client-side UX signals. Mark the order paid only when your backend receives Meld's
`TRANSACTION_CRYPTO_COMPLETE` webhook. Show "processing", not "success", until then.

## Customer requirements, before the order

`Meld.ensureReady` runs what a customer still needs before you create an order with the providers you
offer: verify a contact, accept a provider's terms, give their details, complete Meld's KYC, or finish
a provider's hosted onboarding. Meld plans every step; the SDK runs the ones it can and asks your app
for what only you can supply. No provider names appear in this API, and your API key never reaches
the device.

**1. Your backend mints a session** with its API key:

```http
POST /crypto/onramp/requirements-sessions
Authorization: BASIC <your API key>
Content-Type: application/json

{"customerId": "W9kL817BBS7bNEwxAZaX4z",
 "serviceProviders": ["BANXA", "UPHOLD"],
 "order": {"paymentMethodType": "CREDIT_DEBIT_CARD", "sourceCurrencyCode": "USD", "sourceAmount": 250,
           "destinationCurrencyCode": "USDC", "destinationNetworkCode": "BASE", "countryCode": "US"},
 "readyWhen": "ALL"}
```

Pass the `201` response to the app unchanged. It carries a bearer for that customer and those
providers that lasts 30 minutes. A session names 1 to 5 providers; `order` is optional; `readyWhen` is
`ALL` (the default: every provider must be ready) or `ANY`. Requirements sessions are enabled per Meld
account, so ask Meld to enable yours.

Mint the session on the environment the app passes to `Meld.configure`. The session names its endpoint
relative to the API host, so the SDK runs it against the configured environment and cannot tell which
one minted it: a session from another environment is refused with 401 and, after the one
`renewSession`, the run ends `EXPIRED`.

**2. The app runs it:**

```kotlin
val session = MeldRequirementsSession.fromJson(sessionJson)

val handle = Meld.ensureReady(
    activity, // a ComponentActivity; destroying it cancels the run
    session,
    MeldReadyHandlers(
        collect = { request, answer ->
            // request.fields: key, label, fieldType, jsonSchema, allowedValues.
            // request.fieldErrors: what Meld refused in your last answer, and why.
            showForm(request) { values -> answer(values) } // answer(null) if you handled it yourself
        },
        confirmAgreement = { request, answer ->
            showTerms(request.serviceProvider, request.url) { accepted -> answer(accepted) }
        },
        renewSession = { answer -> mintAgain { json -> answer(json?.let(MeldRequirementsSession::fromJson)) } },
    ),
) { result ->
    when (result.status) {
        MeldReadyStatus.READY, MeldReadyStatus.UNDETERMINED -> createOrder()
        MeldReadyStatus.ACTION_REQUIRED -> show(result.pending)
        else -> report(result)
    }
}
```

| `result.status` | Means | Do |
|---|---|---|
| `READY` | Every provider is ready (with `readyWhen: ANY`, one is) | Create the order |
| `UNDETERMINED` | Meld could not tell | Create the order; creation has the final word |
| `PENDING` | Only reviews are left, and `waitTimeoutMs` passed | Wait for the `CUSTOMER_KYC_STATUS_CHANGE` webhook, or run `ensureReady` again later |
| `ACTION_REQUIRED` | A step is left that this run could not do: no handler for it, terms declined, a form or a contact code refused four times, or a page closed unfinished. `pending` is empty when nothing is left that an app can do and nothing is waited on, such as fields only the provider can supply; the providers' `open` steps name it | Show `result.pending` (or the providers' `open` steps when it is empty), then run `ensureReady` again |
| `BLOCKED` | A provider refused the customer | Offer another provider |
| `CANCELLED` | You called `handle.cancel()`, or the activity was destroyed | — |
| `EXPIRED` | The session was refused with 401 and `renewSession` gave no new one that works: it expired, or another environment minted it | Mint a new session on the configured environment |
| `FAILED` | `result.error.kind` is `http` (with `status` and `code`), `transport` or `protocol` | Try again later |

`result.providers` gives each provider's `status`, `eligible` and its `open` steps.
Each open step carries Meld's `reason` when there is one and, on a share the provider refused, its
`errorCodes` (such as Coinme's `PHONE_VERIFICATION_REQUIRED`; null when there are none). The SDK does
not act on them.
`result.continuation` lists the steps a provider declares to run inside `Meld.mount(order)` after
create, today only Stripe's identity check, so `READY` can still be followed by them. This SDK does
not present Stripe orders yet: `Meld.capabilities` reports them `unsupported`. Other providers' own
pages, such as Mercuryo's widget or Coinbase's payment page, are not listed: they open when the
order is mounted. Every status and step kind is an open set: treat a value you do not know as "not
ready yet".

- Every handler is called on the main thread. Answer through its callback once, from any thread; a
  second answer is ignored. `onResult` runs once, on the main thread, and no handler is called after
  it, even when `onProgress` cancelled the run.
- A step whose handler you leave out is never run: the run ends `ACTION_REQUIRED` with that step in
  `pending`. The SDK never accepts terms on its own.
- `onProgress` reports `READING`, `RUNNING`, `AWAITING_INPUT`, `PRESENTING` and `WAITING`.
- While only reviews are left, the SDK polls as often as Meld asks, between 1 and 60 seconds (every 4
  seconds when Meld does not say). `handle.refresh()` reads again at once: call it when your backend
  relays a `CUSTOMER_KYC_STATUS_CHANGE` webhook to the app. `MeldReadyOptions(waitTimeoutMs)` bounds
  the polling (10 minutes by default). You can keep the handle, say in a `ViewModel`; once the result
  arrives it holds neither your handlers nor the activity.
- Retries are bounded. A dropped connection is sent again with the same idempotency key after 1, 2 and
  4 seconds, then the run fails with `transport`. A request Meld is still running (425) is sent again
  each second, up to 5 times. A step whose outcome Meld could not confirm (503 `OUTCOME_UNKNOWN`) is
  read first. If it is still offered, a page refresh is sent again with a new key, at most 3 times per
  step; any other step may have been applied, so it is not sent again and the run ends
  `ACTION_REQUIRED` with it. A 401
  asks `renewSession` for a new session and carries on with it, sending a refused step again; a second
  401 before any request succeeds, or a 401 after 3 renewals in one run, ends it `EXPIRED`.
- Call `ensureReady` on the main thread.

**Hosted pages.** Meld's KYC, a provider's hosted onboarding or terms, and a provider's hosted form
open in a Custom Tab, or in the browser when no browser offers Custom Tabs. The SDK cannot close the
page, so it does not read while the page is open: the page counts as closed when your activity
resumes. When Meld offered the page as a link, such as a provider's hosted onboarding, the SDK then
refreshes the step once (a 409 there only means the step already moved on; after a 401 it sends the
refresh again on the renewed session). It then reads again, and that read decides whether the step
is done. If the step is still offered and nothing else changed, the SDK waits up to 30 seconds for
Meld to report it done, then ends the run `ACTION_REQUIRED` with that step; it does not open the
page again. A link that expires within a minute is refreshed before it opens. A page the run reaches
while your activity is in the background opens after the activity resumes, from a new read. On a
device with no browser, those steps end the run `ACTION_REQUIRED`. To present the page yourself, pass
`presentUrl` and call its callback once the page closes.

The SDK adds `androidx.browser` 1.8.0, `androidx.activity` 1.9.3 as an API dependency, and package
queries for the Custom Tabs service and for https browsers, so both resolve on Android 11 and later.

### Hosted verification after a refused order

When order creation is refused with a hosted verification page
(`serviceProviderDetails.hostedVerificationUrl`, as for a new US customer's `KYC_NOT_COMPLETED`),
open it, then create the order again with a new `externalOrderId`:

```kotlin
Meld.openHostedVerification(activity, url) { createOrder(externalOrderId = newExternalOrderId()) }
```

It opens the page as above. It throws `IllegalArgumentException` for a page that is not https, and
`ActivityNotFoundException` when no app on the device can open it.

## Mercuryo — prerequisites

- **KYC:** the customer needs an APPROVED Sumsub verification linked to their Meld customer before
  the order — Meld shares it at order creation so the widget skips its own KYC. Without it, order
  creation fails with `KYC_NOT_COMPLETED`.
- **Camera:** Mercuryo's in-widget KYC liveness needs the camera. The SDK declares the `CAMERA`
  permission, but your app must hold it at **runtime** before mounting — request it (e.g. with the
  Activity Result API) or the widget's camera grant is denied.
- **End-user IP:** create the order with the end user's public IP (`clientIpAddress`); Mercuryo
  binds the widget signature to it.

## Security

The SDK never sees card data — capture happens entirely in the provider's widget, loaded over
HTTPS in a `WebView`. The bridge that relays the widget's lifecycle events to your handlers is
scoped to the provider's origins, so a compromised or third-party subframe can't post fake events.

## Example app

A complete, runnable Compose demo is checked in at [`example/`](example/) — a styled checkout
card, a live quote, a requirements check with `Meld.ensureReady`, then the Mercuryo widget with a
status banner + event log (parity with the iOS and web demos). See
[example/README.md](example/README.md) for credentials and how to run it.

## Declared presentation support

Orders and quotes may carry `headlessPresentation` (`surface`, `protocol`, `version`).
`Meld.capabilities(order)` and `Meld.mount` select the same registered adapter by this
contract. Unknown or malformed declarations are unsupported and never use legacy routing.
For advisory support before creating an order:

```kotlin
val support = Meld.presentationCapabilities(
    MeldHeadlessPresentation("EMBEDDED_WIDGET", "MERCURYO_WIDGET", 1),
    "CREDIT_DEBIT_CARD",
)
```

Use the actual quote descriptor; the values above are illustrative. This checks SDK support,
not customer eligibility, device readiness or financial authorization. For device readiness, see
[Check the device before you quote](#check-the-device-before-you-quote) and
[Check readiness before you create the order](#check-readiness-before-you-create-the-order). The
registry currently supports version 1 card protocols `MERCURYO_WIDGET` and `UPHOLD_WIDGET` on `EMBEDDED_WIDGET`, and
`BANXA_CHECKOUT` on `VENDOR_SDK` — Banxa's card form is presented by Banxa's own SDK, and `surface`
names what the provider exposes — plus `GOOGLE_PAY` on `PROVIDER_HOSTED` with
`COINBASE_PAYMENT_LINK`. Provider identity does not select a declared protocol.

Apple Pay is not presented on Android; every Apple Pay protocol is declined. Do not create an
order for an unsupported surface; offer an explicitly supported alternative before create. For an
existing unsupported order, preserve its identity and request key rather than silently creating a
replacement.

Stored orders without the descriptor keep legacy adapters. Mercuryo/Uphold require registered
HTTPS widget origins (no URL credentials or nonstandard ports); Banxa keeps its provider/token
signature. Widget origin checks also apply when mounting a declared protocol. Arbitrary iframe
orders no longer default to Mercuryo. Integrators must not strip metadata to force fallback.

## Coinme card (`COINME_VAULT`)

A Coinme card order (`VENDOR_SDK / COINME_VAULT / 1`, `CREDIT_DEBIT_CARD`) runs Coinme's checkout in a
WebView inside `host`, the way Banxa's card checkout runs: the bundled Meld web SDK shows the
customer's saved Coinme cards and "Use a new card", Coinme's secure card fields (VGS iframes, so card
data never reaches this SDK or your app) and Coinme's Risk session. No Coinme library is added to your
app. `Meld.capabilities(order)` reports it only when the order declares both
`CREATE_CUSTOMER_AUTH_TOKEN` and `CREATE_PAYMENT_SESSION` payment actions and its Coinme environment
matches `Meld.configure` (`live` with `PRODUCTION`, `sandbox` otherwise). Configure
`MeldEnvironment.QA` for orders created on Meld's QA API.

- Give `host` a visible size. `onReady` fires once the device is tagged and the cards or the form are
  showing.
- The checkout's payment actions go through the SDK to the order's own endpoint with the order's
  bearer; your API key is never involved.
- `onPaymentSubmitted` fires once Coinme accepts the payment. A card that was just added is verified by
  Coinme for about a minute; the checkout shows "Verifying your card…" and keeps trying for up to three
  minutes, then reports `VERIFICATION_PENDING` (no payment was attempted).
- A declined card reports `PAYMENT_REJECTED`; an interrupted submission reports
  `PAYMENT_OUTCOME_UNKNOWN` (track the order, never pay it again). Settlement is still the webhook.

Google Play Data Safety: declare what Coinme's checkout collects, shared with Coinme (and its
processors VGS and Sardine), encrypted in transit:

| Data type | Collected for | Notes |
|---|---|---|
| Financial info: payment info | App functionality (payments) | Card number, expiry and CVC, captured by Coinme's secure fields |
| Personal info: name, address | App functionality, fraud prevention | Billing name, address and state |
| Device or other IDs | Fraud prevention, security | Device fingerprint and Risk session |
| App activity: app interactions | Fraud prevention, security | Typing and touch behaviour on the form |

Link Coinme's privacy policy from your app's privacy policy.

## Google Pay

Google Pay runs through the provider's own payment page (Coinbase, protocol
`COINBASE_PAYMENT_LINK` on `PROVIDER_HOSTED`). The SDK loads that page out of sight, presses its
Google Pay button once, and the customer sees only Google Pay's own sheet. Such an order reports
`embeddable = false`, `surface = "native-wallet"` and `requiresUserGesture = true`.

### What you need

The SDK cannot meet these for you:

- **Your own provider account.** Orders run on your own Coinbase project, with guest checkout and
  Google Pay enabled. The payment runs on your credentials, not Meld's. The one exception today is
  Coinbase's one-time-code check, which Meld signs with a Meld key.
- **Google approval, applied for by you.** Google lets an app run production Google Pay in a WebView
  only once its integration is published. Follow Google's
  [Publish your integration](https://developers.google.com/pay/api/android/guides/test-and-deploy/publish-your-integration)
  guide: submit your app in the [Google Pay & Wallet Console](https://pay.google.com/business/console),
  and once it is approved, publish it on Google Play. Without approval, Google Pay shows
  `OR_BIBED_11` (see [Troubleshooting](#troubleshooting)).
- **A release-signed build for production orders.** Google's production environment needs your app
  signed with its release key; the debug key does not work there. A debug build cannot run a
  production order, so test debug builds with `sandbox-` orders (see [Testing](#testing)).
- **Build:** `compileSdk 34` or later, Android Gradle plugin 8.1.1 or later, `minSdk 24`.
- **Device:** Android System WebView 137 or later and Google Play services 25.18.30 or later. On a
  WebView without Payment Request support, `Meld.capabilities` reports `unsupported`.
- **A physical phone.** Coinbase accepts only device-tokenized cards (`CRYPTOGRAM_3DS`), which an
  emulator cannot hold, so Google Pay never works on one.
- Do not intercept the WebView's requests with your own HTTP client.

### Testing

A `sandbox-` order runs Google Pay's TEST environment: the sheet says you will not be charged and no
money moves. TEST needs no Google approval and works in a debug build, but still needs a physical
phone. A production order runs Google Pay's production environment, which needs the approval above
and a build signed with your release key.

### Check the device before you quote

Ask whether this device can pay with a payment method at all before you request a quote, so you
can leave a method out of the request or the picker. No provider or quote row is needed:

```kotlin
Meld.paymentMethodReadiness(context, "GOOGLE_PAY") { readiness ->
    when (readiness) {
        MeldReadiness.Ready, is MeldReadiness.Unknown -> include("GOOGLE_PAY")
        is MeldReadiness.Unavailable -> leaveOut("GOOGLE_PAY") // readiness.code is PAYMENT_METHOD_UNAVAILABLE
    }
}
```

The answer arrives once, on the main thread, within 5 seconds.

| `paymentMethodType` | Answer on Android |
|---|---|
| `GOOGLE_PAY` | Google Pay's `isReadyToPay` for any Visa, Mastercard, American Express or Discover card the customer has added (`PAN_ONLY` or `CRYPTOGRAM_3DS`): `Ready` when there is one, `Unavailable` when there is none (`Unknown` in `SANDBOX`, see the environment note below), and `Unknown` when Google Play services is missing, out of date or slow |
| `APPLE_PAY` | `Unavailable`. Apple Pay is not presented on Android |
| Any other, such as `CREDIT_DEBIT_CARD` | `Unknown`. No device precondition decides it |

A provider may accept fewer cards than this check does. Coinbase takes only device-tokenized cards,
for example. Once you have the quote, ask again for each row.

### Check readiness before you create the order

Ask whether this device can pay with a quote row's method before you create an order, so a customer
who cannot pay is not offered Google Pay. Pass the row's `headlessPresentation` and
`paymentMethodType` (`POST /payments/crypto/quote?integrationMode=HEADLESS`):

```kotlin
val presentation = MeldHeadlessPresentation(surface, protocol, version) // the row's headlessPresentation

Meld.presentationReadiness(context, presentation, row.paymentMethodType) { readiness ->
    when (readiness) {
        MeldReadiness.Ready -> offer(row)
        is MeldReadiness.Unknown -> offer(row) // could not tell; the payment has the final word
        is MeldReadiness.Unavailable -> hide(row) // readiness.code is PAYMENT_METHOD_UNAVAILABLE
    }
}
```

The answer arrives once, on the main thread, within 5 seconds. It works for every row, not only
Google Pay.

- **`Ready`:** offer the method.
- **`Unavailable`:** hide the method. Either this SDK cannot present it here, or Google Pay reports
  no card on this device that the provider's request accepts. No order exists, so there is nothing
  to track.
- **`Unknown`:** the SDK could not tell. The method may have no device check (a card), Google Play
  services may be missing or out of date, no answer came in time, or, in `SANDBOX`, Google Pay found
  no card in production. You may offer the method.

Readiness is advisory. If you create the order anyway, the mounted payment still reports `onError`
with `PAYMENT_METHOD_UNAVAILABLE` when it cannot pay, and that is the final word. Card methods answer
`Unknown` wherever `presentationCapabilities` supports them, as on iOS and the web: nothing on the
device decides whether a card pays.

Google Pay readiness, at both levels, always asks Google Pay's production environment, because the
provider page's environment belongs to an order that does not exist yet. A `sandbox-` order runs
Google's TEST environment, which that answer does not cover, so in `SANDBOX` a production "no card"
is `Unknown`, not `Unavailable`. The check moves no money. When readiness answers `Unknown`, the SDK
logs the reason under the `MeldSDK` tag.

### Mount

- **Mount from the customer's tap** on your own pay button, with
  `Meld.mount(order, activity, handlers)`. Nothing is drawn into your views, and the payment ends
  only when that Activity is destroyed (a configuration change it does not handle itself included)
  or you unmount. `Meld.mount(order, host, handlers)` still works, but there `host` only locates the
  Activity and the payment ends when `host` leaves its window. Hold the handle until a terminal
  callback.
- **`onReady`** means the sheet was requested. A wallet this device or customer cannot pay with
  reports `onError` with `PAYMENT_METHOD_UNAVAILABLE` (no attempt exists; offer another method).
  Once the sheet may be up, an interrupted session reports `PAYMENT_OUTCOME_UNKNOWN`: track the
  order and never pay it again.
- Unmounting after `onPaymentSubmitted` keeps the page alive for up to 60 seconds so the provider
  can finish confirming. Settlement is still the webhook.

### What the SDK adds to your app

- The `org.chromium.intent.action.PAY`, `IS_READY_TO_PAY` and `UPDATE_PAYMENT_DETAILS` package
  queries, through which the page's Payment Request reaches Google Pay.
- Google Play services Wallet (`com.google.android.gms:play-services-wallet` 20.0.0), for readiness
  only. Your merged manifest gains the `com.google.android.gms.wallet.api.enabled` and
  `com.google.android.gms.version` meta-data and the non-exported
  `com.google.android.gms.common.api.GoogleApiActivity`. You can leave Wallet out:

  ```kotlin
  implementation("io.meld:meldsdk:<version>") {
      exclude(group = "com.google.android.gms", module = "play-services-wallet")
  }
  ```

  Google Pay orders still mount, and readiness answers `Unknown`. A minified build needs no rules of
  your own: the SDK's consumer R8 rules let Wallet be missing.
- Nothing from Google Maps. Wallet uses `play-services-maps` only for the `LatLng` of its loyalty,
  offer and gift card objects, which the SDK never builds, so the SDK excludes it, and your merged
  manifest gains none of Maps' entries, such as the OpenGL ES 2 `uses-feature` with
  `required="true"`. An app that uses Maps keeps its own dependency on it.

### Troubleshooting

| You see | Cause | Fix |
|---|---|---|
| Google Pay's sheet says "merchant is having trouble accepting your payment" with `OR_BIBED_11` | Your app is not approved for Google Pay production, and the order runs Google Pay's production environment | Get your app approved in the Google Pay & Wallet Console. To test before then, use a `sandbox-` order |
| A production order fails in a debug build | Google Pay's production environment does not accept the debug key | Run production orders from a build signed with your release key. In debug builds, use `sandbox-` orders |
| `onError` with `PAYMENT_METHOD_UNAVAILABLE` and `detail` `onramp_api.load_error:ERROR_CODE_GUEST_GOOGLE_PAY_NOT_SUPPORTED` | No device-tokenized card in Google Wallet that the provider accepts | Offer another method; check readiness before create next time |
| `Meld.capabilities(order).surface == "unsupported"` for a Google Pay order | The WebView lacks Payment Request or WebMessageListener support, or the order declares a protocol or version this build does not know | Update Android System WebView to 137 or later, or the SDK |
| `onError` with `PRESENTATION_FAILED` and `detail` `presentation_deadline` | The sheet did not open within 8 seconds of the press | Create a new order. If it repeats, check the device meets [What you need](#what-you-need) |
| Google Pay fails on an emulator | Emulators cannot hold a device-tokenized card | Use a physical phone |

## License

Proprietary. See [LICENSE](LICENSE).
