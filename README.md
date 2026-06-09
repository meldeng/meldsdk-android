# MeldSDK (Android)

Embed a crypto on/off-ramp provider widget (Mercuryo card) in your Android app. The native
counterpart of [meldsdk-ios](https://github.com/meldeng/meldsdk-ios); same public API shape as the
web SDK (`@meldcrypto/sdk`).

MeldSDK mounts a payment provider's widget into a view you own and relays its lifecycle events,
with one uniform call: `Meld.mount(order, host, handlers)`. It never renders or transports card
data — the provider's widget does, loaded over HTTPS in a `WebView`.

## Install

Maven coordinates `io.meld:meldsdk` (published via JitPack from a tag):

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

// app/build.gradle.kts
dependencies {
    implementation("io.meld:meldsdk:0.1.0")
}
```

Requires `minSdk 24`. The library declares `INTERNET` and `CAMERA` permissions (camera is used
for in-widget KYC document/selfie capture).

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

Settlement is the Meld webhook, never a client event. `onStatusChange` with `COMPLETED` is the
provider's "order complete", not settlement.

All callbacks are invoked on the main thread.

## Architecture

The SDK is a container manager + event relay; everything provider-specific lives behind a
`MeldAdapter` in the registry. The order's `paymentMethodType` / `renderMode` select the adapter,
so supporting a new provider is a new adapter, never a change to the public API. Mercuryo loads its
signed widget URL in a `WebView` through a generic, provider-neutral `WebViewHost`, which:

- injects a bridge at document start (`WebViewCompat.addDocumentStartJavaScript`) that forwards the
  widget's `window` messages to native;
- **filters those messages by origin** against the provider allowlist, so a malicious or
  compromised subframe can't post fake lifecycle events;
- grants the camera for KYC and dispatches all events on the main thread.

## Example app

A complete, runnable Compose demo is checked in at [`example/`](example/) — a styled checkout
card, a live quote, then the Mercuryo widget with a status banner + event log (parity with the
iOS and web demos). See [example/README.md](example/README.md) for credentials and how to run it.

## License

Proprietary. See [LICENSE](LICENSE).
