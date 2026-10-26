# ACH Stripe spike – card + ACH Direct Debit

A small, self-contained app that replicates **how Stripe card payments work today** for subscription billing on
the Stripe **platform** account (AnswerForce US) and, on this branch (`ach-payment-stripe`), **extends it with ACH
Direct Debit** (`us_bank_account`) exactly as the implementation plan proposes – before touching the real
`fullpayments` and `jBilling_hosted_api` codebases.

| Branch | Content |
|---|---|
| `card-payment-stripe` | Replica of today's card flow only |
| `ach-payment-stripe` | Card flow + ACH via Stripe (this README) |

Scope (same as the plan): ACH only for the answering brand **AnswerForce US** on the Stripe **platform** account –
no Setmore, no connected accounts.

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
│       │   └── webhook/        POST /webhook/stripe/platform/v2026-01-28 (payment_method.attached + ACH events)
│       │                       AchWebhookEventHandler, HostedApiNotifier (→ hosted API ACH callback)
│       ├── jbilling/
│       │   ├── hostedapi/      copy of the jBilling hosted API endpoints the UI calls:
│       │   │                   POST /processPayment · PUT /updatePaymentInstrument
│       │   │                   PUT /v1/updatePaymentInstrument · GET /getPaymentInstruments
│       │   │                   POST /stripe/ach/payment-status (new – ACH settlement / failure callback)
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
                                                   │  (ACH: succeeded / payment_failed / dispute / mandate / refund)
                                                   └─► jBilling hosted API /stripe/ach/payment-status
                                                          └─► jBilling payment SETTLED / FAILED (/ REVERSED) + DS TASK
```

---

## Run it with Docker

### Prerequisites

- Docker Desktop (or Docker Engine with the compose plugin).
- A Stripe account in **test mode**. Its keys play the role of the *AnswerForce US platform* Stripe account
  (Dashboard → Developers → API keys).
- For ACH, in the same test-mode account:
  1. **Settings → Payment methods**: turn on **ACH Direct Debit**.
  2. **Settings → Financial Connections**: complete the setup (needed for instant bank verification).

  Without these the Payment Element only shows the card tab and intent creation may fail with
  "payment method type us_bank_account is invalid".

### Steps

1. Create your env file:

   ```bash
   cd ACH_Stripe
   cp .env.example .env
   ```

   Then fill in `STRIPE_SECRET_KEY` (`sk_test_…`) and `STRIPE_PUBLISHABLE_KEY` (`pk_test_…`).
   The ACH settings are optional (see [ACH configuration](#ach-configuration)).

2. Build and start everything:

   ```bash
   docker compose up --build
   ```

   The first build downloads Maven dependencies, so it takes a few minutes.

3. Open the UI at **http://localhost:3000**.

   - The chips at the top should say *Stripe keys set*, *ACH via Stripe* and *Webhook secret configured*.
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
to `StripePlatformCustomer.paymentMethodIds` by the `payment_method.attached` handler. For ACH they are essential:
settlement, bank returns, disputes and revoked mandates only reach jBilling through webhooks.

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
| 1 | **Add / replace payment method** → Card (first time) | `POST setup-intents` (no customerKey) → `confirmSetup` → `PUT /v1/updatePaymentInstrument` → hosted API reads the card (`GET payment-methods/{id}`), creates the jBilling instrument (`btcchostedpaymentId`, `BT ID` = new customerKey, `Provider=STRIPE`) → `createVaultWithNonce(seti_…)` creates the Stripe customer with the card attached → `PATCH platform/customers` stores the accountPin | **Customers**: new customer with metadata `jBillingCustomerKey` and the card attached. **Setup Intents**: succeeded. |
| 2 | **Pay with saved method** (card on file) | `POST /processPayment {instrumentId}` → jBilling → plugin → `/api/braintree/processPayment/cc/{customerKey}/…` → off-session PaymentIntent (`confirm=true, off_session=true`) → `TransactionJDO` COMPLETED → jBilling payment Successful, owing balance reduced | **Payments**: a succeeded payment on the customer. |
| 3 | **Pay with new card / bank** → Card, *Save* ticked | `POST payment-intents` (`capture_method=manual`, `setup_future_usage=off_session`) → `confirmPayment` → status `requires_capture` → `PUT /updatePaymentInstrument {nonce: pi_…}` → `createVaultWithNonce(pi_…)` saves the card on the customer and parks the PI → new jBilling instrument → jBilling payment → `/api/braintree/processPayment` **captures** the parked PI | **Payments**: shows *Uncaptured* right after confirm, then *Succeeded*. The card replaces the previous one on the customer. |
| 4 | **Pay with new card / bank** → Card, *Save* not ticked | `POST payment-intents` → `confirmPayment` → `POST /processPayment {nonce: pi_…, createVaultFlag:false}` → single-use jBilling instrument → `/api/braintree/createTransaction/…/{pi_…}` **captures** the PI | **Payments**: succeeded payment. Note that today's platform PI always sets `setup_future_usage` (see "Known differences"). |
| 5 | **Billing run (recurring)** | Simulated jBilling billing process charges the owing balance with the instrument on file → same path as #2 | **Payments**: succeeded off-session payment. |
| 6 | **Refund** (link in jBilling payments) | jBilling → `/api/braintree/refundTransaction/{businessId}/{pi_…}/{amount}` → Stripe refund → `TransactionJDO` with `responseStatus=Settled` | **Payments → the payment**: refunded. |

The transaction ids in the UI link straight to the Stripe Dashboard (test mode).

---

## Try the ACH flows

Select the AnswerForce US account, open one of the tabs below and choose the **US bank account** tab in the Stripe
Payment Element. The UI tracks the selected tab (Payment Element `change` event) and sends `paymentType=ach`
instead of `cc` to the hosted API.

### ACH test data

**Instant verification (Financial Connections):** in the bank search pick **Test Institution** and a test account
(for example *Success*).

**Manual entry + microdeposits** (only with `ACH_VERIFICATION_METHOD=automatic`): routing number `110000000` and one
of these account numbers. The confirm returns `requires_action`; the UI shows a link to Stripe's hosted
verification page. Enter amounts `32` and `45` (or descriptor code `SM11AA`) there, then click
**Continue after verification**.

| Account number | Outcome | What you should see |
|---|---|---|
| `000123456789` | Succeeds | jBilling payment `PENDING` → `SETTLED`, DS note |
| `000222222227` | Fails – insufficient funds | `PENDING` → `FAILED` (or `REVERSED`), DS **TASK** "ACH Payment Settlement Declined – …" |
| `000111111113` | Fails – account closed | same as above |
| `000111111116` | Fails – no account | same as above |
| `000333333335` | Fails – debit not authorized | same as above |
| `000555555559` | Succeeds, then disputed | `SETTLED`, then DS **TASK** "ACH Payment Disputed – …" |
| `000000000009` | Stays processing | `PENDING` forever (refund blocked) |

Source: Stripe docs → *Accept an ACH Direct Debit payment* → *Test your integration*. Test-mode ACH payments settle
within seconds (live: about 4 business days), so the webhooks arrive almost right away. While a payment is
`PENDING`, the jBilling payments panel refreshes every 5 seconds.

### ACH flows

| # | UI tab | What happens | Verify in Stripe Dashboard |
|---|---|---|---|
| A1 | **Add / replace payment method** → US bank account | `POST setup-intents` now adds `payment_method_options.us_bank_account` (verification method + Financial Connections permissions) → `confirmSetup` (mandate accepted in the Payment Element) → `PUT /v1/updatePaymentInstrument {paymentType: ach}` → hosted API reads `bankAccountDetails` (`GET payment-methods/{id}`) and saves a `stripe-achId` instrument (`ach.bank.name`, `ach.account.number` masked, `ach.routing.number`, `Provider=STRIPE`, `BT ID`=customerKey) | **Customers**: bank account attached, with a mandate. |
| A2 | **Pay with saved method** (ACH instrument on file) | `POST /processPayment {instrumentId, paymentType: ach}` → plugin → `/api/braintree/processPayment/ach/{customerKey}/…` → off-session PI with `payment_method_types=[us_bank_account]` → status `processing` → `TransactionJDO` **PENDING** → jBilling payment Successful with settlement **PENDING**; owing balance reduced (Braintree parity) | **Payments**: *Pending*, then *Succeeded* (or *Failed*). |
| A3 | **Pay with new card / bank**, *Save* ticked | UI generates a customerKey for a new customer → `POST payment-intents?customerKey=…` (`futureUsage=OFF_SESSION`, metadata accountPin/brandId/jbillingUserId; FullPayments creates the Stripe customer up front; top-level automatic capture, cards still `manual` via `payment_method_options[card][capture_method]`) → `confirmPayment` → `processing` → `PUT /updatePaymentInstrument {nonce: pi_…, paymentType: ach, paymentDetails.customerKey}` → `createVaultWithNonce` returns `bankInfo` → `stripe-achId` instrument → jBilling payment → facade sees `processing` and **does not capture** → PENDING | **Payments**: *Pending* → *Succeeded*. Bank account saved on the customer. |
| A4 | **Pay with new card / bank**, *Save* not ticked | `futureUsage=NONE` (no `setup_future_usage`, so the bank account is not saved) → `confirmPayment` → `processing` → `POST /processPayment {nonce: pi_…, createVaultFlag:false, paymentType: ach}` → single-use ACH instrument → `/api/braintree/createTransaction` → no capture → PENDING | **Payments**: *Pending* → *Succeeded*. |
| A5 | **Billing run (recurring)** with an ACH instrument | Same path as A2 | as A2 |
| A6 | **Refund** | Blocked while settlement is `PENDING` (jBilling and the facade both refuse – Stripe can't refund a processing PI) and for `FAILED`/`REVERSED`; allowed once `SETTLED`. ACH refunds are asynchronous: `refund.updated`/`refund.failed` update `TransactionJDO`, and a failed refund creates a DS task | **Payments → the payment**: refund *Pending* → *Succeeded*. |

### What happens after the payment (webhooks)

| Stripe event | FullPayments (`AchWebhookEventHandler`) | Hosted API (`AchPaymentStatusService`) |
|---|---|---|
| `payment_intent.processing` | logged | – |
| `payment_intent.succeeded` | `TransactionJDO` PENDING → COMPLETED, notify `PAYMENT_SUCCEEDED` | jBilling payment settlement `SETTLED` + DS note |
| `payment_intent.payment_failed` | `TransactionJDO` → FAILED + failure code/message, notify `PAYMENT_FAILED` | settlement `FAILED` + DS **TASK** (CC_DISPUTE type, `cbsEscalations.assignee` – parity with Braintree `TRANSACTION_SETTLEMENT_DECLINED`). With `ACH_REVERSE_JBILLING_PAYMENT_ON_FAILURE=true` it also records a reversal and restores the owing balance (`REVERSED`) |
| `charge.dispute.created` | notify `DISPUTE_CREATED` | DS **TASK** |
| `mandate.updated` (inactive) | notify `MANDATE_INACTIVE` | DS **TASK** – the bank account can't be debited any more |
| `refund.updated` / `refund.failed` | update the refund `TransactionJDO`; notify `REFUND_FAILED` on failure | DS **TASK** |

The hosted API callback is idempotent per payment intent + event. The account is found from the PI metadata
`accountPin`, falling back to `StripePlatformCustomer.merchantId`.

### ACH configuration

| Env var (`.env`) | Default | Meaning |
|---|---|---|
| `ACH_VERIFICATION_METHOD` | `automatic` | `automatic` = Financial Connections with fallback to manual entry + microdeposits. `instant` = Financial Connections only (the recommended phase-1 production setting – no microdeposit waiting). |
| `ACH_REVERSE_JBILLING_PAYMENT_ON_FAILURE` | `false` | `false` = phase 1 / Braintree parity: a bank return only creates a DS task. `true` = phase 2: the jBilling payment is also reversed. |
| `HOSTED_API_BASE_URL` | `http://localhost:8080` | Where FullPayments sends the ACH status callback (the same backend in the spike). |

Gating, in `application.yml`:

- `spike.ach.enabled-business-ids` is the FullPayments side: only these Stripe accounts get ACH options on their
  intents.
- `spike.brands.<brandId>.ach-provider: STRIPE` is the hosted-API side (`{brandId}.ach.provider`): every other brand
  keeps Braintree ACH.

### What changed compared with `card-payment-stripe`

| Area | Change |
|---|---|
| FullPayments intents | `AchIntentOptions` (new); setup + payment intents add `us_bank_account` options when the business is ACH-enabled; payment intents use automatic capture with card-level manual capture; `futureUsage=NONE`; customer created up front for a new customerKey; PI metadata; responses carry `achEnabled` |
| FullPayments payment-method API | `GET payment-methods/{id}` returns `bankAccountDetails` for `us_bank_account` |
| FullPayments facade | status-based capture (only `requires_capture` is captured; `processing`/`succeeded` accepted; a PI that already failed is rejected); `TransactionJDO` `PENDING` + `paymentMethodType`, `failureCode`, `failureMessage`; `BankInfo` instead of `CardInfo` for bank accounts; off-session ACH charges; refunds blocked while processing |
| FullPayments webhook | ACH events (table above) + `HostedApiNotifier` |
| Hosted API | `ach` branches in all four endpoints, `stripe-achId` instruments with `ach.*` metafields, `paymentStatus` PENDING/COMPLETED in responses, `achProvider` in `getPaymentInstruments`, new `POST /stripe/ach/payment-status` |
| jBilling core (simulated) | `JbPayment.settlementStatus` (PENDING / SETTLED / FAILED / REVERSED) and `paymentMethodType`; mark settled / failed / reversed; refunds blocked until settled |
| UI | tabs renamed (card / bank); selected Payment Element type → `paymentType`; customerKey generated for new customers; PI metadata; `NONE` for one-time; handling for `processing` and microdeposit `requires_action` (hosted verification link + *Continue after verification*); settlement badges, ACH instrument display, ACH test data, auto-refresh while pending; `/spike/config` shows the ACH settings |
| Docker | `ACH_VERIFICATION_METHOD`, `ACH_REVERSE_JBILLING_PAYMENT_ON_FAILURE`, `HOSTED_API_BASE_URL` passed to the backend; `.env.example` updated. No new containers – `stripe-cli` already forwards all events |

### ACH limitations / things to verify

- **Webhook race:** if the bank return arrives *before* jBilling records the payment, the `payment_failed` webhook finds
  no `TransactionJDO` and is ignored. In that case the facade sees the failed PI when jBilling submits it and rejects
  the payment, so nothing is recorded. A settlement that arrives early is picked up the same way (`succeeded` →
  COMPLETED, not PENDING).
- **One-time ACH payment for a new customer:** the UI-generated customerKey creates a Stripe customer and a
  `StripePlatformCustomer` that jBilling never stores (no instrument is saved).
- **Signup-style setup intent (no customerKey) with a bank account:** the bank payment method is attached to the new
  customer afterwards in `createVaultWithNonce`. Confirm in Stripe test mode that this works for your account
  (Financial Connections-verified accounts); otherwise create the customer up front, as the PI flow does.
- No real Braintree ACH path: brands with `ach-provider: BRAINTREE` get an error for `paymentType=ach`.

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
| `fullpayments.webhook.*` | fullpayments `StripeWebhookHandler` (platform endpoint) + `UpdatePlatformCustomerPaymentMethodOnPaymentMethodAttachedHandler`; `AchWebhookEventHandler` / `HostedApiNotifier` are **new** (ACH) |
| `fullpayments.intent.AchIntentOptions` | **new** (ACH) – intent options for `us_bank_account` |
| `fullpayments.facade.BankInfo` | **new** (ACH) – bank details next to `CardInfo` |
| `jbilling.hostedapi.AchPaymentStatusController` / `AchPaymentStatusService` | **new** (ACH) – like `TransactionSettlementDeclinedHandler` for Braintree |
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
- Card one-time payments still use `setup_future_usage=on_session` when ACH is not enabled for the brand (today's
  behaviour); for ACH-enabled brands the UI sends `NONE`.
- One Stripe test account and one brand (AnswerForce US) are configured in `application.yml`.

---

## Finding the ACH changes in the code

Every ACH change has a comment starting with `ACH` that refers to the item numbers (P0–P10) in the Confluence pages:

```bash
grep -rn "ACH" backend/src frontend/public
```

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
| No *US bank account* tab in the Payment Element | Enable ACH Direct Debit (and Financial Connections) in the Stripe Dashboard, test mode. Check the chip says *ACH via Stripe* |
| "payment method type us_bank_account is invalid" when creating an intent | Same: ACH Direct Debit is not enabled on the Stripe account |
| ACH payment stays `PENDING` | Webhooks aren't arriving – check `docker compose logs stripe-cli` and the webhooks panel. Account `000000000009` stays processing on purpose |
| "cannot be charged in status requires_action" | The bank account still needs microdeposit verification – use the hosted verification link, then *Continue after verification* |
| No DS task after a failed ACH payment | Check the backend log for `HostedApiNotifier`; `HOSTED_API_BASE_URL` must point at the backend |
