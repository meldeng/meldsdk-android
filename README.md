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
| `onPaymentSubmitted` | User finished the provider payment flow (UX hint only) | Show "processing" |
| `onStatusChange` | Order status changed; `e.status` is `PENDING` \| `COMPLETED` \| `FAILED` \| `CANCELLED` | React to status; `COMPLETED` = provider "order complete" (still not settlement) |
| `onCancel` | User cancelled | Show retry CTA |
| `onError` | Load failure, bad order, or terminal `FAILED` status | Show error; `e.recoverable` says retry vs. new order |

`status` is normalized across providers — code against it, not the raw provider string (in
`e.providerStatus`). A terminal `FAILED` also fires `onError`, and a `CANCELLED` also fires
`onCancel`. Every callback also receives the `orderId`.

## Settlement — webhook, never the SDK

Neither `onPaymentSubmitted` nor `onStatusChange` with `COMPLETED` is settlement — both are
client-side UX signals. Mark the order paid only when your backend receives Meld's
`TRANSACTION_STATUS_CHANGED` webhook. Show "processing", not "success", until then.

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
