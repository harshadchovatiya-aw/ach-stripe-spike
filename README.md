# ACH Stripe spike – card flow replica

A small, self-contained app that replicates **how Stripe card payments work today** for subscription billing on
the Stripe **platform** account (AnswerForce US), so we can see the flow end to end and then extend the same code to
**ACH Direct Debit** before touching the real `fullpayments` and `jBilling_hosted_api` codebases.

The design documents this spike follows (Confluence, Billing space → *ACH Payment with Stripe*):

- [ACH Payments with Stripe – End-to-End Implementation Plan](https://anywhereworks.atlassian.net/wiki/spaces/Billing/pages/2567143490)
- [Moving ACH payments from Braintree to Stripe (via FullPayments) – impact analysis](https://anywhereworks.atlassian.net/wiki/spaces/Billing/pages/2567602263)
- [ACH Direct Debit via Stripe in fullpayments – Analysis & Required Changes](https://anywhereworks.atlassian.net/wiki/spaces/Billing/pages/2567798807)

---

## What is inside

```
ACH_Stripe/
├── backend/            Spring Boot 3.5.8 · Java 21 · stripe-java 31.4.1 (same versions as fullpayments) · H2 in-memory DB
│   └── com.anywhere.spike.achstripe
│       ├── fullpayments/   copy of the FullPayments Stripe *platform* flow
│       │   ├── intent/         POST /api/stripe/v4/platform/setup-intents
│       │   │                   POST/PATCH /api/stripe/v4/online-checkout/platform/payment-intents
│       │   ├── paymentmethod/  GET  /api/stripe/v4/platform/payment-methods/{id}
│       │   ├── customer/       StripePlatformCustomer + PATCH /api/stripe/v4/platform/customers
│       │   ├── facade/         "Braintree facade" /api/braintree/* (StripeBraintreePaymentHelper, TransactionJDO)
│       │   └── webhook/        POST /webhook/stripe/platform/v2026-01-28 (payment_method.attached)
│       ├── jbilling/
│       │   ├── hostedapi/      copy of the jBilling hosted API endpoints the UI calls:
│       │   │                   POST /processPayment · PUT /updatePaymentInstrument
│       │   │                   PUT /v1/updatePaymentInstrument · GET /getPaymentInstruments
│       │   └── core/           a tiny simulated jBilling core: users, payment instruments, payments,
│       │                       the payment-task plugin that calls the FullPayments facade, DS notes/tasks
│       └── spike/              spike-only helpers (test accounts, billing run, refunds, state views)
├── frontend/           static HTML/JS + Stripe.js Payment Element, served by nginx (proxies /backend → backend)
└── docker-compose.yml  backend + frontend + stripe-cli (forwards Stripe webhooks to the backend)
```

Both "systems" live in one Spring Boot app but in separate packages, and **the jBilling part calls the FullPayments
part over HTTP** (like production), so the boundaries stay realistic and can be split later.

```
 Browser (Stripe.js Payment Element)
   │  1. create intent ─────────────────────────────► FullPayments  /api/stripe/v4/...platform...
   │  2. confirmPayment / confirmSetup ─────────────► Stripe
   │  3. pay / save instrument ─────────────────────► jBilling hosted API  /processPayment, /updatePaymentInstrument, ...
   │                                                     │
   │                                                     ├─► FullPayments /api/stripe/v4/platform/payment-methods, /customers
   │                                                     └─► jBilling core ─► plugin ─► FullPayments /api/braintree/*  ─► Stripe
   │                                                                                     (capture / off-session charge)
   └── Stripe ── webhooks ──► stripe-cli ──► FullPayments /webhook/stripe/platform/v2026-01-28
```

---

## Run it with Docker

### Prerequisites

- Docker Desktop (or Docker Engine with the compose plugin).
- A Stripe account in **test mode**. Its keys play the role of the *AnswerForce US platform* Stripe account
  (Dashboard → Developers → API keys).

### Steps

1. Create your env file:

   ```bash
   cd ACH_Stripe
   cp .env.example .env
   ```

   Then fill in `STRIPE_SECRET_KEY` (`sk_test_…`) and `STRIPE_PUBLISHABLE_KEY` (`pk_test_…`).

2. Build and start everything:

   ```bash
   docker compose up --build
   ```

   The first build downloads Maven dependencies, so it takes a few minutes.

3. Open the UI at **http://localhost:3000**.

   - The two chips at the top should say *Stripe keys set* and *Webhook secret configured*.
   - A test account `AF-100001` (AnswerForce US, owing $49.00) is created automatically.

4. Stop with `Ctrl+C`, or `docker compose down`. All data is in memory, so it is reset on every start.

| URL | What |
|---|---|
| http://localhost:3000 | Spike UI |
| http://localhost:8080 | Backend (FullPayments + jBilling hosted API endpoints) |
| http://localhost:8080/h2-console | H2 console – JDBC URL `jdbc:h2:mem:spike`, user `sa`, empty password |

### Webhooks

The `stripe-cli` container runs `stripe listen` with your secret key and forwards **all** test-mode events to
`http://backend:8080/webhook/stripe/platform/v2026-01-28`. It writes the signing secret to a shared volume, and the
backend reads it from there, so no manual step is needed.

Webhooks matter because, exactly like production, a card saved for an **existing** Stripe customer is only linked
to `StripePlatformCustomer.paymentMethodIds` by the `payment_method.attached` handler.

If the container doesn't work in your environment (for example a corporate proxy), use the Stripe CLI on your Mac
instead:

1. Run:

   ```bash
   stripe listen --forward-to http://localhost:8080/webhook/stripe/platform/v2026-01-28
   ```

2. Copy the printed `whsec_…` value into `STRIPE_WEBHOOK_SECRET` in `.env`.
3. Run `docker compose up --build backend frontend`.

---

## Try the card flows (and verify them in Stripe)

Use the Stripe test cards shown in the UI:

| Card | Behaviour |
|---|---|
| `4242 4242 4242 4242` | Succeeds |
| `4000 0025 0000 3155` | Asks for 3-D Secure |
| `4000 0000 0000 9995` | Declined |

Every call (UI → backend → Stripe.js) appears in the **API call log** panel. The panels on the right show what each
system stored: jBilling payments, `StripePlatformCustomer`, `TransactionJDO`, the caches, webhooks and DS notes.

| # | UI tab | What happens (same as production) | Verify in Stripe Dashboard (test mode) |
|---|---|---|---|
| 1 | **Add / replace card** (first time) | `POST setup-intents` (no customerKey) → `confirmSetup` → `PUT /v1/updatePaymentInstrument` → hosted API reads the card (`GET payment-methods/{id}`), creates the jBilling instrument (`btcchostedpaymentId`, `BT ID` = new customerKey, `Provider=STRIPE`) → `createVaultWithNonce(seti_…)` creates the Stripe customer with the card attached → `PATCH platform/customers` stores the accountPin | **Customers**: new customer with metadata `jBillingCustomerKey` and the card attached. **Setup Intents**: succeeded. |
| 2 | **Pay with saved card** | `POST /processPayment {instrumentId}` → jBilling → plugin → `/api/braintree/processPayment/cc/{customerKey}/…` → off-session PaymentIntent (`confirm=true, off_session=true`) → `TransactionJDO` COMPLETED → jBilling payment Successful, owing balance reduced | **Payments**: a succeeded payment on the customer. |
| 3 | **Pay with new card**, *Save card* ticked | `POST payment-intents` (`capture_method=manual`, `setup_future_usage=off_session`) → `confirmPayment` → status `requires_capture` → `PUT /updatePaymentInstrument {nonce: pi_…}` → `createVaultWithNonce(pi_…)` saves the card on the customer and parks the PI → new jBilling instrument → jBilling payment → `/api/braintree/processPayment` **captures** the parked PI | **Payments**: shows *Uncaptured* right after confirm, then *Succeeded*. The card replaces the previous one on the customer. |
| 4 | **Pay with new card**, *Save card* not ticked | `POST payment-intents` → `confirmPayment` → `POST /processPayment {nonce: pi_…, createVaultFlag:false}` → single-use jBilling instrument → `/api/braintree/createTransaction/…/{pi_…}` **captures** the PI | **Payments**: succeeded payment. Note that today's platform PI always sets `setup_future_usage` (see "Known differences"). |
| 5 | **Billing run (recurring)** | Simulated jBilling billing process charges the owing balance with the instrument on file → same path as #2 | **Payments**: succeeded off-session payment. |
| 6 | **Refund** (link in jBilling payments) | jBilling → `/api/braintree/refundTransaction/{businessId}/{pi_…}/{amount}` → Stripe refund → `TransactionJDO` with `responseStatus=Settled` | **Payments → the payment**: refunded. |

The transaction ids in the UI link straight to the Stripe Dashboard (test mode).

---

## Mapping to the real code

| Spike class | Real class |
|---|---|
| `fullpayments.intent.CreateSetupIntentCommandHandler` | fullpayments `stripe/…/setup/CreateSetupIntentCommandHandler` |
| `fullpayments.intent.PlatformPaymentIntentHandlers` | fullpayments `CreatePlatformPaymentIntentHandler`, `UpdatePlatformPaymentIntentHandler` |
| `fullpayments.paymentmethod.PaymentMethodController` | fullpayments `GetPaymentMethodByIdQueryHandler` |
| `fullpayments.customer.*` | fullpayments `StripePlatformCustomer`, `StripePlatformCustomerService` |
| `fullpayments.intent.PlatformIntentCache` | Redis caches `platformPaymentIntentCache`, `platformSetupIntentCache`, `pendingCapturePaymentIntentCache` |
| `fullpayments.facade.*` | fullpayments legacy `com.acti.braintree.Helper.*` (`StripeBraintreePaymentHelper`, `CapturePaymentIntentCommandHandler`, `ProcessPaymentIntentCommandHandler`, `ProcessSetupIntentCommandHandler`, `ProcessPaymentCommandHandler`, `RefundSubscriptionPaymentCommandHandler`, `CardInfo`, `VaultingHelper`, `TransactionJDO`) |
| `fullpayments.webhook.*` | fullpayments `StripeWebhookHandler` (platform endpoint) + `UpdatePlatformCustomerPaymentMethodOnPaymentMethodAttachedHandler` |
| `jbilling.hostedapi.HostedApiService` | jBilling_hosted_api `UserUtilities.processPayment / updatePaymentInstrument / updatePaymentInstrumentAndPayment / getPaymentInstruments` |
| `jbilling.hostedapi.PaymentHelper` | jBilling_hosted_api `PaymentHelper.getPaymentMethodDetails / createVaultIdByNonce`, `UserUtilities.updateCustomerKeyInFullPayments` |
| `jbilling.core.*` | jBilling core (simulated): `JbillingAPI`, payment-task plugin, `DSDraft` |

### Known differences from production

- No authentication between services, and no idempotency / rate-limit handling.
- No Braintree: wherever the real code falls back to `BraintreeHelper`, the spike returns an error message.
- Caches are in memory with no TTL.
- jBilling core is a minimal simulation (one active instrument per user, no invoices).
- `/processPayment` with a nonce and `createVaultFlag=true` uses the legacy `processOTPaymentWithoutVault` path in
  the real code. The spike uses the `createVaultFlag=false` path for it.
- The platform PaymentIntent always sets `setup_future_usage` (ON_SESSION or OFF_SESSION), exactly like
  `CreatePlatformPaymentIntentHandler` today. ACH needs a `NONE` option for true one-time payments (P2).
- One Stripe test account and one brand (AnswerForce US) are configured in `application.yml`.

---

## Extending it to ACH (next step)

Every place that needs an ACH change has a comment starting with `ACH` that refers to the item numbers in the
Confluence pages. To list them:

```bash
grep -rn "ACH" backend/src frontend/public
```

Suggested order (matches the implementation plan):

1. **Stripe Dashboard (test mode):** enable *ACH Direct Debit* and complete the *Financial Connections* setup.
2. **FullPayments intents (P1, P2):**
   - Setup intent: add `payment_method_options.us_bank_account` (`verification_method=instant`,
     `financial_connections.permissions=[payment_method]`).
   - Payment intent: set top-level `capture_method=automatic` with `payment_method_options.card.capture_method=manual`.
   - Add `futureUsage=NONE`.
   - Create the customer up front.
   - Add PI metadata (accountPin, customerKey, brandId).
3. **Payment-method API (P3):** return `bankAccountDetails`.
4. **Facade (P4–P6, P9, P10):**
   - Don't capture `us_bank_account` PIs; record `PENDING`.
   - Return bank info instead of the fake `CardInfo`.
   - Off-session ACH charges with the mandate.
   - Block refunds while processing.
5. **Webhook (P7, P8):**
   - Handle `payment_intent.succeeded` / `payment_failed`, `charge.dispute.created`, `mandate.updated`, `refund.*`.
   - Update `TransactionJDO`.
   - Notify the hosted API, which creates a DS **TASK** (the `DsActivity` table and UI panel already exist).
6. **Hosted API:**
   - `ach` branches next to the `cc` ones (`verifyAndVaultStripeACH`).
   - New `stripe-achId` instrument type with `Provider=STRIPE`, bank name and last4.
   - `getPaymentTypeKeyForJBillingPayment("ach")` → `stripe-achId`.
   - New `POST /stripe/ach/payment-status` callback.
7. **UI:**
   - After the intents allow ACH, the Payment Element shows a *US bank account* tab automatically.
   - Handle `processing` ("payment submitted, 3–5 business days").
   - Later, handle `requires_action` for microdeposits.

Test bank accounts and Financial Connections test institutions are listed in Stripe's ACH testing docs.

---

## Running without Docker (optional)

```bash
# backend (Java 21 + Maven)
cd backend
STRIPE_SECRET_KEY=sk_test_... STRIPE_PUBLISHABLE_KEY=pk_test_... STRIPE_WEBHOOK_SECRET=whsec_... mvn spring-boot:run

# frontend: serve frontend/public with any static server and point config.js at http://localhost:8080
```

## Troubleshooting

| Symptom | Fix |
|---|---|
| Chip says *Stripe keys missing* | Check `.env`, then run `docker compose up --build` again |
| *Webhook secret not configured* or no webhook events | Check the `stripe-cli` container logs (`docker compose logs stripe-cli`), or use your own `stripe listen` (see above) |
| "Not a Stripe customer/transaction – the real code would fall back to Braintree" on *Pay with saved card* | The Stripe customer has no linked payment method yet (the `payment_method.attached` webhook didn't arrive). Check webhooks, then add the card again |
| "The amount requested is not equal to the amount of the payment intent" | The facade checks that jBilling's amount equals the authorized PI amount (same as production). Don't change the amount after starting |
