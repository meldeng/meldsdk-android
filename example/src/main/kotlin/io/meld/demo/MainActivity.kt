package io.meld.demo

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import io.meld.sdk.Meld
import io.meld.sdk.MeldEventHandlers
import io.meld.sdk.MeldOrder
import io.meld.sdk.MeldReadiness
import io.meld.sdk.MeldStatus
import io.meld.sdk.MeldWidgetHandle
import io.meld.sdk.requirements.MeldAgreementRequest
import io.meld.sdk.requirements.MeldCollectRequest
import io.meld.sdk.requirements.MeldReadyHandle
import io.meld.sdk.requirements.MeldReadyHandlers
import io.meld.sdk.requirements.MeldReadyResult
import io.meld.sdk.requirements.MeldReadyStatus
import io.meld.sdk.requirements.MeldRequirementsSession
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

// A minimal example app for MeldSDK.
//
// Where to look:
//   • CheckoutScreen   — the checkout UI and the SDK touchpoints (configure / capabilities)
//   • checkRequirements — Meld.ensureReady before the order: your UI collects, Meld plans the steps
//   • WidgetScreen     — the actual integration: Meld.mount(...) + event handling
//   • OrderService.kt  — POC-only: creates the order by calling Meld directly. In a real app your
//                        backend does this so the API key never ships in the app.
// Anything tagged "demo-only" (status banner, event log, auto-close) is just for this example.

private val Bg = Color(0xFF2B2B28)
private val Card = Color(0xFFF1F0EC)
private val Panel = Color(0xFFE6E5DF)
private val Green = Color(0xFF3E6650)
private val Ink = Color(0xFF15191F)
private val SubInk = Color(0xFF6B7280)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // KYC inside the widget needs the camera; ask up front so WebChromeClient can grant it.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 0)
        }
        setContent { App() }
    }
}

@Composable
private fun App() {
    val events = remember { EventLogState() }
    var presented by remember { mutableStateOf<MeldOrder?>(null) }

    Surface(modifier = Modifier.fillMaxSize(), color = Bg) {
        val order = presented
        if (order == null) {
            CheckoutScreen(events) { presented = it }
        } else {
            WidgetScreen(order, events) { presented = null }
        }
    }
}

// MARK: - Checkout

@Composable
private fun CheckoutScreen(events: EventLogState, onPresent: (MeldOrder) -> Unit) {
    val orders = remember { OrderService() }
    val scope = rememberCoroutineScope()

    var wallet by remember { mutableStateOf(DemoConfig.DEFAULT_WALLET) }
    var customerId by remember { mutableStateOf("") }
    var clientIP by remember { mutableStateOf<String?>(null) }
    var receiveText by remember { mutableStateOf("…") }
    var quoteNote by remember { mutableStateOf("fetching live quotes…") }
    var errorText by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }
    val providers = remember { mutableStateListOf<DemoQuote>() }
    var selectedProvider by remember { mutableStateOf<String?>(null) }
    var paymentMethod by remember { mutableStateOf(PaymentMethods.first()) }
    var readinessText by remember { mutableStateOf("") }
    var requirementsText by remember { mutableStateOf("") }
    var asking by remember { mutableStateOf<DemoAsk?>(null) }
    var requirements by remember { mutableStateOf<MeldReadyHandle?>(null) }
    val context = LocalContext.current
    val selectedPresentation = providers.firstOrNull { it.serviceProvider == selectedProvider }?.headlessPresentation

    val needsCustomerField = DemoConfig.meldCustomerId.isEmpty()
    val buyDisabled = creating || wallet.isBlank() || selectedProvider == null

    // Pick a provider: highlight it and update the headline receive amount + note.
    fun select(quote: DemoQuote) {
        selectedProvider = quote.serviceProvider
        quote.destinationAmount?.let { receiveText = "≈ ${format(it)}" }
        quoteNote = "via ${quote.serviceProvider}" +
            (quote.totalFee?.let { " — total fees ${format(it)} ${DemoConfig.SOURCE_CURRENCY}" } ?: "")
    }

    // On appear and per payment method: configure the SDK and fetch live quotes (one per provider) to choose from.
    LaunchedEffect(paymentMethod) {
        Meld.configure(DemoConfig.environment) // SDK: one-time setup, matching the order's backend
        providers.clear()
        selectedProvider = null
        if (DemoConfig.meldApiKey.isEmpty()) {
            receiveText = "≈ —"
            quoteNote = "MELD_API_KEY not set"
            errorText = "MELD_API_KEY is empty. Fill local.properties (see README)."
            return@LaunchedEffect
        }
        clientIP = orders.publicIP()
        try {
            val fetched = orders.quotes(paymentMethod.type).sortedByDescending { it.destinationAmount ?: 0.0 }
            providers.clear()
            providers.addAll(fetched)
            val best = fetched.firstOrNull()
            if (best != null) select(best) else { receiveText = "≈ —"; quoteNote = "no providers for this corridor" }
        } catch (e: Exception) {
            receiveText = "≈ —"
            quoteNote = "quote failed: ${e.message}"
        }
    }

    // SDK: before creating the order, ask whether this device can pay with the quoted method.
    LaunchedEffect(selectedPresentation, paymentMethod) {
        readinessText = ""
        val presentation = selectedPresentation ?: return@LaunchedEffect
        val readiness = suspendCancellableCoroutine { continuation ->
            Meld.presentationReadiness(context, presentation, paymentMethod.type) { continuation.resume(it) }
        }
        readinessText = describe(readiness)
    }

    DisposableEffect(Unit) { onDispose { requirements?.cancel() } }

    // SDK: run the customer's requirements before the order. Your backend mints the session with its API
    // key; the SDK runs Meld's steps, asks your UI for fields and terms, and opens hosted KYC in a Custom Tab.
    fun checkRequirements() {
        val activity = context.activity() as? ComponentActivity ?: return
        val customer = if (needsCustomerField) customerId.trim() else DemoConfig.meldCustomerId
        val provider = selectedProvider
        if (customer.isEmpty() || provider == null) {
            requirementsText = "Pick a provider and a Meld customer ID."
            return
        }
        requirements?.cancel()
        requirementsText = "minting a requirements session…"
        scope.launch {
            try {
                val session = MeldRequirementsSession.fromJson(orders.mintRequirementsSession(provider, paymentMethod.type, customer))
                requirements = Meld.ensureReady(
                    activity,
                    session,
                    MeldReadyHandlers(
                        onProgress = { requirementsText = "requirements: ${it.state.name.lowercase()}…" },
                        collect = { request, answer -> asking = DemoAsk.Collect(request, answer) },
                        confirmAgreement = { request, answer -> asking = DemoAsk.Agree(request, answer) },
                    ),
                ) { result ->
                    requirements = null
                    asking = null
                    requirementsText = describe(result)
                }
            } catch (e: Exception) {
                requirementsText = "requirements failed: ${e.message}"
            }
        }
    }

    asking?.let { ask -> AskDialog(ask, onCancel = { requirements?.cancel() }) { asking = null } }

    fun buy() {
        errorText = ""
        creating = true
        scope.launch {
            try {
                if (DemoConfig.meldApiKey.isEmpty()) {
                    errorText = "Set MELD_API_KEY in local.properties (see README)."
                    return@launch
                }
                val customer = if (needsCustomerField) customerId.trim() else DemoConfig.meldCustomerId
                if (customer.isEmpty()) {
                    errorText = "Set a Meld customer ID."
                    return@launch
                }
                val provider = selectedProvider
                if (provider == null) {
                    errorText = "Pick a provider."
                    return@launch
                }
                val orderJSON = orders.createOrder(provider, paymentMethod.type, customer, wallet.trim(), clientIP)
                val order = MeldOrder.fromJson(orderJSON) // SDK: decode the order
                // SDK: can this build present it? Embedded widgets render in the screen below; a wallet the
                // SDK presents itself (embeddable = false) shows only its own sheet, mounted from this tap.
                if (Meld.capabilities(order).surface == "unsupported") {
                    errorText = "This SDK build cannot present the order on this device."
                    return@launch
                }
                events.clear()
                onPresent(order)
            } catch (e: Exception) {
                errorText = e.message ?: "order creation failed"
            } finally {
                creating = false
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Card, RoundedCornerShape(18.dp))
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Buy ${DemoConfig.DESTINATION_CURRENCY}", color = Ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)

            AmountPanel("You pay", DemoConfig.SOURCE_AMOUNT, DemoConfig.SOURCE_CURRENCY)
            AmountPanel("You receive", receiveText, DemoConfig.DESTINATION_CURRENCY, note = quoteNote)

            FieldLabel("Choose a provider")
            if (providers.isEmpty()) {
                Text(quoteNote, color = SubInk, fontSize = 14.sp)
            } else {
                providers.forEach { quote ->
                    ProviderRow(quote, selected = quote.serviceProvider == selectedProvider) { select(quote) }
                }
            }

            FieldLabel("Wallet Address")
            DemoField(wallet, { wallet = it })
            if (needsCustomerField) {
                FieldLabel("Meld Customer ID")
                DemoField(customerId, { customerId = it }, placeholder = "customer with APPROVED KYC")
            }
            FieldLabel("Payment Method")
            PaymentMethods.forEach { method ->
                ChoiceRow(method.label, selected = method == paymentMethod) { paymentMethod = method }
            }
            if (readinessText.isNotEmpty()) {
                Text(readinessText, color = SubInk, fontSize = 13.sp)
            }

            TextButton(onClick = { checkRequirements() }, enabled = selectedProvider != null) {
                Text("Check requirements", color = Green, fontWeight = FontWeight.SemiBold)
            }
            if (requirementsText.isNotEmpty()) {
                Text(requirementsText, color = SubInk, fontSize = 13.sp)
            }

            Button(
                onClick = { buy() },
                enabled = !buyDisabled,
                colors = ButtonDefaults.buttonColors(containerColor = Green, disabledContainerColor = Color(0xFFDCDAD4)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().height(54.dp),
            ) {
                Text(
                    if (creating) "Creating order…" else "Buy ${DemoConfig.DESTINATION_CURRENCY}",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                )
            }

            if (errorText.isNotEmpty()) {
                Text(errorText, color = Color(0xFFB3261E), fontSize = 13.sp)
            }
            Text("Powered by Meld.io", color = SubInk, fontSize = 13.sp, modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}

@Composable
private fun AmountPanel(label: String, amount: String, currency: String, note: String? = null, footer: String? = null) {
    Column(
        Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(12.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, color = SubInk, fontSize = 15.sp)
                Text(amount, color = Ink, fontSize = 32.sp, fontWeight = FontWeight.Bold)
            }
            Text(currency, color = Ink, fontWeight = FontWeight.Bold)
        }
        note?.let { Text(it, color = SubInk, fontSize = 13.sp) }
        footer?.let { Text(it, color = Ink, fontSize = 13.sp) }
    }
}

@Composable
private fun ProviderRow(quote: DemoQuote, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) Color(0xFFEAF1EC) else Color(0xFFF7F6F2), RoundedCornerShape(12.dp))
            .border(1.5.dp, if (selected) Green else Color(0xFFD8D7D1), RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(18.dp)
                .border(2.dp, if (selected) Green else Color(0xFFB9B8B2), CircleShape)
                .padding(4.dp)
                .background(if (selected) Green else Color.Transparent, CircleShape),
        )
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(quote.serviceProvider, color = Ink, fontWeight = FontWeight.SemiBold)
            quote.kycMode?.let { Text("KYC $it", color = SubInk, fontSize = 12.sp) }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                "≈ ${quote.destinationAmount?.let { format(it) } ?: "—"} ${DemoConfig.DESTINATION_CURRENCY}",
                color = Ink,
                fontWeight = FontWeight.Bold,
            )
            quote.totalFee?.let {
                Text("fee ${format(it)} ${DemoConfig.SOURCE_CURRENCY}", color = SubInk, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) Color(0xFFEAF1EC) else Color(0xFFF7F6F2), RoundedCornerShape(12.dp))
            .border(1.5.dp, if (selected) Green else Color(0xFFD8D7D1), RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(14.dp),
    ) { Text(label, color = Ink, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal) }
}

private data class PaymentMethod(val type: String, val label: String)

// Catalog ids, passed through to quote and order creation as-is.
private val PaymentMethods = listOf(
    PaymentMethod("CREDIT_DEBIT_CARD", "Credit or debit card"),
    PaymentMethod("GOOGLE_PAY", "Google Pay"),
)

@Composable
private fun FieldLabel(text: String) = Text(text, color = Ink, fontSize = 16.sp)

@Composable
private fun DemoField(value: String, onChange: (String) -> Unit, placeholder: String = "") {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        placeholder = { Text(placeholder, color = SubInk) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth(),
    )
}

// MARK: - Widget screen (the actual SDK integration)

@Composable
private fun WidgetScreen(order: MeldOrder, events: EventLogState, onClose: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().background(Ink).padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onClose) { Text("Back", color = Color.White) }
            Text("Checkout", color = Color.White, fontWeight = FontWeight.Bold)
        }

        // SDK: mount an embedded widget into a container view, and a surface that draws into no view of
        // yours (a wallet sheet) for the life of the Activity. Pass your event handlers; unmount on dispose.
        AndroidView(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            factory = { ctx ->
                val container = FrameLayout(ctx)
                try {
                    container.tag = if (Meld.capabilities(order).embeddable) {
                        Meld.mount(order, container, events.handlers(onClose))
                    } else {
                        Meld.mount(order, checkNotNull(ctx.activity()), events.handlers(onClose))
                    }
                } catch (e: Exception) {
                    events.record("mount failed: ${e.message}")
                }
                container
            },
            onRelease = { container ->
                (container.tag as? MeldWidgetHandle)?.unmount() // SDK: tear down
            },
        )

        // demo-only: status banner + event log
        StatusBanner(events.status)
        EventLogView(events.lines)
    }
}

@Composable
private fun StatusBanner(status: MeldStatus?) {
    status ?: return
    val (title, color) = when (status) {
        MeldStatus.PENDING -> "Processing payment…" to Color(0xFF888888)
        MeldStatus.COMPLETED -> "Order complete — settlement via webhook" to Color(0xFF2E7D32)
        MeldStatus.FAILED -> "Order failed" to Color(0xFFC62828)
        MeldStatus.CANCELLED -> "Order cancelled" to Color(0xFF888888)
    }
    Text(
        title,
        color = color,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.fillMaxWidth().background(color.copy(alpha = 0.12f)).padding(12.dp),
    )
}

@Composable
private fun EventLogView(lines: SnapshotStateList<String>) {
    Column(
        Modifier
            .fillMaxWidth()
            .height(140.dp)
            .background(Color(0xFF0A1221))
            .verticalScroll(rememberScrollState())
            .padding(8.dp),
    ) {
        if (lines.isEmpty()) {
            Text("waiting for events…", color = Color(0xFF9AA0A8), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        } else {
            lines.forEach { Text(it, color = Color(0xFFD9D9D9), fontSize = 11.sp, fontFamily = FontFamily.Monospace) }
        }
    }
}

// MARK: - demo-only event log + handlers

private class EventLogState {
    val lines: SnapshotStateList<String> = mutableStateListOf()
    var status by mutableStateOf<MeldStatus?>(null)
    private var closed = false
    private val main = Handler(Looper.getMainLooper())

    fun record(line: String) {
        lines.add(line)
    }

    fun clear() {
        lines.clear()
        status = null
        closed = false
    }

    // SDK: react to the widget's lifecycle. Here we log each event, drive the status banner, and
    // (for the demo) close the screen on a terminal outcome.
    fun handlers(onClose: () -> Unit) = MeldEventHandlers(
        onReady = { record("onReady") },
        onPaymentSubmitted = {
            record("onPaymentSubmitted (UX hint, not settled)")
            // demo: the widget's job ends at submit — settlement is confirmed server-side via webhook, and
            // providers (e.g. Uphold) leave their own "processing" screen up with no further widget event.
            // Dismiss the web view here rather than stranding the user on it. A real app would then show
            // its OWN settling/success UX driven by its backend.
            finish("payment submitted — settling server-side", onClose)
        },
        onStatusChange = { e ->
            status = e.status
            record("onStatusChange: ${e.status.raw} (${e.providerStatus ?: "-"})")
            if (e.status == MeldStatus.COMPLETED) finish("completed", onClose)
            if (e.status == MeldStatus.FAILED) finish("failed", onClose)
        },
        onCancel = {
            status = MeldStatus.CANCELLED
            record("onCancel")
            finish("cancelled", onClose)
        },
        onError = { e ->
            status = MeldStatus.FAILED
            record("onError [${e.code}] ${e.message}")
            finish("error", onClose)
        },
    )

    // demo-only: close the screen once, shortly after a terminal event, so the outcome shows.
    private fun finish(reason: String, onClose: () -> Unit) {
        if (closed) return
        closed = true
        record("→ closing widget ($reason)")
        main.postDelayed({ onClose() }, 1500)
    }
}

private fun Context.activity(): Activity? {
    var context: Context? = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}

// MARK: - demo-only requirements UI

private sealed class DemoAsk {
    class Collect(val request: MeldCollectRequest, val answer: (Map<String, String>?) -> Unit) : DemoAsk()
    class Agree(val request: MeldAgreementRequest, val answer: (Boolean) -> Unit) : DemoAsk()
}

// SDK: answer each request through its callback. The SDK never accepts terms on its own.
@Composable
private fun AskDialog(ask: DemoAsk, onCancel: () -> Unit, onDone: () -> Unit) {
    when (ask) {
        is DemoAsk.Collect -> {
            val values = remember(ask) { mutableStateMapOf<String, String>() }
            AlertDialog(
                onDismissRequest = { onCancel(); onDone() },
                title = { Text("Details for ${ask.request.serviceProviders.joinToString()}") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ask.request.fields.forEach { field ->
                            val error = ask.request.fieldErrors?.firstOrNull { it.key == field.key }
                            OutlinedTextField(
                                value = values[field.key] ?: "",
                                onValueChange = { values[field.key] = it },
                                label = { Text(field.label ?: field.key) },
                                isError = error != null,
                                supportingText = error?.let { { Text(it.reason) } },
                                singleLine = true,
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        ask.answer(ask.request.fields.associate { it.key to (values[it.key] ?: "").trim() })
                        onDone()
                    }) { Text("Submit") }
                },
                dismissButton = { TextButton(onClick = { onCancel(); onDone() }) { Text("Cancel") } },
            )
        }
        is DemoAsk.Agree -> AlertDialog(
            onDismissRequest = { ask.answer(false); onDone() },
            title = { Text("${ask.request.serviceProvider} terms") },
            text = { Text("Accept ${ask.request.serviceProvider}'s terms${ask.request.url?.let { " at $it" } ?: ""}?") },
            confirmButton = { TextButton(onClick = { ask.answer(true); onDone() }) { Text("Accept") } },
            dismissButton = { TextButton(onClick = { ask.answer(false); onDone() }) { Text("Decline") } },
        )
    }
}

private fun describe(result: MeldReadyResult): String = when (result.status) {
    MeldReadyStatus.READY, MeldReadyStatus.UNDETERMINED -> "requirements: ${result.status} — create the order"
    // SDK: pending is empty when nothing here can run and nothing is waited on; the providers' open steps name it.
    MeldReadyStatus.ACTION_REQUIRED -> "requirements: action required (" +
        result.pending.orEmpty().map { it.code }.ifEmpty { result.providers.flatMap { it.open }.map { it.code } }.joinToString() + ")"
    MeldReadyStatus.FAILED -> "requirements: failed ${listOfNotNull(result.error?.kind?.raw, result.error?.status, result.error?.code).joinToString(" ")}"
    else -> "requirements: ${result.status}"
}

private fun describe(readiness: MeldReadiness): String = when (readiness) {
    MeldReadiness.Ready -> "Ready on this device"
    is MeldReadiness.Unavailable -> "Unavailable here, hide it: ${readiness.reason}"
    is MeldReadiness.Unknown -> "Could not tell, may offer: ${readiness.reason}"
}

/** Trim trailing zeros so amounts read cleanly (0.00021400 -> 0.000214). */
private fun format(value: Double): String {
    var s = String.format("%.8f", value)
    while (s.contains(".") && (s.endsWith("0") || s.endsWith("."))) s = s.dropLast(1)
    return s
}
