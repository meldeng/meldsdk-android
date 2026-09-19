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
if (Meld.capabilities(order).embeddable) {
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

All callbacks are invoked on the main thread.

## Events

| Event | Fires when | Do |
|---|---|---|
| `onReady` | Widget document loaded | Hide spinner |
| `onPaymentSubmitted` | User finished the provider payment flow — **exactly once per mount** (UX hint only) | Unmount, show "processing" |
| `onStatusChange` | Order status changed; `e.status` is `PENDING` \| `COMPLETED` \| `FAILED` \| `CANCELLED` | React to status; `COMPLETED` = provider "order complete" (still not settlement) |
| `onCancel` | User cancelled | Show retry CTA |
| `onError` | Load failure, bad order, or terminal `FAILED` status | Show error; `e.recoverable` says retry vs. new order |

`onPaymentSubmitted` fires once and only once, however the provider signals it. Some send a
"payment finished" message and never a status; some report `COMPLETED` and never a finished
message; some send both, in either order. The SDK collapses that into a single callback, so you
do not need a `settledOnce` guard of your own. A terminal `FAILED`/`CANCELLED` status, a cancel,
or a non-recoverable error closes it, so a failure is never followed by a submission.

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
supports version 1 `EMBEDDED_WIDGET` card protocols `MERCURYO_WIDGET`, `UPHOLD_WIDGET`, and
`BANXA_CHECKOUT`. Provider identity does not select a declared protocol.

Android does not support native Apple Pay sheets. This build declines wallet-token, Stripe
native, hosted-link and vendor Apple Pay protocols. Do not create an order for an unsupported
surface; offer an explicitly supported alternative before create. For an existing unsupported
order, preserve its identity and request key rather than silently creating a replacement.

Stored orders without the descriptor keep legacy adapters. Mercuryo/Uphold require registered
HTTPS widget origins (no URL credentials or nonstandard ports); Banxa keeps its provider/token
signature. Widget origin checks also apply when mounting a declared protocol. Arbitrary iframe
orders no longer default to Mercuryo. Integrators must not strip metadata to force fallback.

These APIs require a coordinated Android SDK release and wrapper adoption. No SDK artifact is
published by this change. Browser/provider and physical-device acceptance remain separate gates.


### Shared error recovery

Every Android `MeldError` exposes `headlessError` with version 1, category `OUTCOME_UNKNOWN`,
recovery `READ_STATE`, and `automaticRetryAllowed: false`. The current Android adapters present
widgets; their callback errors cannot establish a financial outcome or read-only retry semantics.
Preserve the existing order and reconcile through your backend. The legacy `recoverable` flag
is only a presentation hint and never authorizes a replacement order or automatic payment retry.

This additive property preserves the existing constructor, copy and destructuring APIs. It uses
the same recovery vocabulary as shared headless HTTP responses and the browser/iOS SDKs.
