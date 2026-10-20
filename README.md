# MeldSDK (Android)

Embed a crypto on/off-ramp provider widget (Mercuryo card) in your Android app. The native
counterpart of [meldsdk-ios](https://github.com/meldeng/meldsdk-ios); same public API shape as the
web SDK (`@meldcrypto/sdk`).

MeldSDK mounts a payment provider's widget into a view you own and relays its lifecycle events,
with one uniform call: `Meld.mount(order, host, handlers)`. It never renders or transports card
data — the provider's widget does, loaded over HTTPS in a `WebView`.

## Install

Add the dependency (published to Maven Central as `io.meld:meldsdk`):

```kotlin
// app/build.gradle.kts
dependencies {
    implementation("io.meld:meldsdk:0.1.1")
}
```

`mavenCentral()` is in the default repositories of new Android projects; add it if yours doesn't
have it. Requires `minSdk 24`. The library declares `INTERNET` and `CAMERA` permissions (camera is
used for in-widget KYC document/selfie capture).

## Usage

```kotlin
Meld.configure(MeldEnvironment.SANDBOX)

val order = MeldOrder.fromJson(backendOrderJson) // POST /crypto/order/headless, passed through

// Guard before mounting.
if (Meld.capabilities(order).surface != "unsupported") {
    val handle = Meld.mount(
        order,
        host, // a ViewGroup you own
        MeldEventHandlers(
            onReady = { orderId -> /* widget document loaded */ },
            onPaymentSubmitted = { orderId -> /* UX hint — user finished the flow, NOT settlement */ },
            onStatusChange = { e -> /* code against e.status (normalized), not e.providerStatus */ },
            onCancel = { orderId -> },
            onError = { e -> /* e.code, e.message, e.recoverable */ },
        ),
    )

    // On teardown (navigation, dismissal):
    handle.unmount()
}
```

`embeddable` only indicates whether a visible host is needed; native sheets can be supported with
`embeddable == false`.

All callbacks are invoked on the main thread.

## Events

| Event | Fires when | Do |
|---|---|---|
| `onReady` | Widget document loaded | Hide spinner |
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

`MeldErrorCode` names the normalized error codes. `e.attemptMayExist` is false only for codes that
say no payment attempt exists (`PAYMENT_METHOD_UNAVAILABLE`, `PRESENTATION_FAILED`,
`PAYMENT_REJECTED`, `ORDER_STATE_CHANGED`). When it is true — `PAYMENT_OUTCOME_UNKNOWN`, or any code
this build does not know — track the existing order and never pay it again.

`status` is normalized across providers — code against it, not the raw provider string (in
`e.providerStatus`). A terminal `FAILED` also fires `onError`, and a `CANCELLED` also fires
`onCancel`. Every callback also receives the `orderId`.

## Settlement — webhook, never the SDK

Neither `onPaymentSubmitted` nor `onStatusChange` with `COMPLETED` is settlement — both are
client-side UX signals. Mark the order paid only when your backend receives Meld's
`TRANSACTION_CRYPTO_COMPLETE` webhook. Show "processing", not "success", until then.

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
card, a live quote, then the Mercuryo widget with a status banner + event log (parity with the
iOS and web demos). See [example/README.md](example/README.md) for credentials and how to run it.

## License

Proprietary. See [LICENSE](LICENSE).

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
not customer eligibility, device readiness or financial authorization. The registry currently
supports version 1 card protocols `MERCURYO_WIDGET` and `UPHOLD_WIDGET` on `EMBEDDED_WIDGET`, and
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

These APIs require a coordinated Android SDK release and wrapper adoption. No SDK artifact is
published by this change. Browser/provider and physical-device acceptance remain separate gates.

## Provider-hosted wallets (Google Pay)

Some providers present a wallet from their own payment link. The SDK loads that page out of sight,
presses its pay button once, and the customer sees only the wallet's own sheet. Such an order
reports `embeddable = false`, `surface = "native-wallet"` and `requiresUserGesture = true`:

- **Mount from the customer's tap** on your own pay button. `host` only locates the Activity and
  nothing is drawn into it, but the payment ends if it leaves its window, so pass a view that stays
  on screen. Hold the handle until a terminal callback.
- **`onReady`** means the sheet was requested. A wallet this device or customer cannot pay with
  reports `onError` with `PAYMENT_METHOD_UNAVAILABLE` (no attempt exists; offer another method).
  Once the sheet may be up, an interrupted session reports `PAYMENT_OUTCOME_UNKNOWN`: track the
  order and never pay it again.
- Unmounting after `onPaymentSubmitted` keeps the page alive for up to 60 seconds so the provider
  can finish confirming. Settlement is still the webhook.

`native-wallet` names no payment method. iOS reports its Apple Pay sheets as `native-applepay`, so
guard on `surface != "unsupported"` rather than on either value.

Device and app requirements, which the SDK cannot meet for you:

- Android System WebView 137 or later and Google Play services 25.18.30 or later. On a WebView
  without Payment Request support, `Meld.capabilities` reports `unsupported`.
- Your app must be approved for Google Pay in the Google Pay & Wallet Console and published on
  Google Play before Google Pay works in production.
- Do not intercept the WebView's requests with your own HTTP client. The SDK's manifest adds the
  `org.chromium.intent.action.PAY`, `IS_READY_TO_PAY` and `UPDATE_PAYMENT_DETAILS` package queries
  to your app.
