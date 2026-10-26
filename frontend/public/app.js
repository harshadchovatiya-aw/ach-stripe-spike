/* global Stripe */
(() => {
  "use strict";

  const CFG = window.SPIKE_CONFIG;
  const $ = (id) => document.getElementById(id);

  const state = {
    config: null,
    accounts: [],
    account: null,     // jBilling user
    brand: null,       // brand config for the selected account
    instrument: null,  // from GET /getPaymentInstruments
    flow: "saved",
    stripe: null,
    stripeKey: null,
    elements: null,
    intent: null,      // { kind: 'payment'|'setup', id, clientSecret, ... }
    pmType: "card",    // ACH: payment method type selected in the Payment Element ('card' | 'us_bank_account')
    autoRefresh: null, // ACH: interval id while waiting for settlement webhooks
  };

  const FLOWS = {
    saved: `<strong>Pay with saved card / bank account.</strong> UI → <code>POST /processPayment {instrumentId}</code> (hosted API)
      → jBilling processPayment → plugin → FullPayments <code>/api/braintree/processPayment/cc/{customerKey}/…</code>
      → off-session PaymentIntent (<code>confirm=true, off_session=true</code>) with the card saved on the Stripe customer.
      <br><em>ACH:</em> the request goes out with <code>paymentType=ach</code> → <code>/processPayment/ach/…</code>; the PaymentIntent
      (<code>payment_method_types=[us_bank_account]</code>, existing mandate) comes back <code>processing</code> – jBilling records the payment
      with settlement status <strong>PENDING</strong> until the <code>payment_intent.succeeded</code> / <code>payment_failed</code> webhook.`,
    new: `<strong>Pay with a new card or bank account.</strong> UI → FullPayments <code>POST /api/stripe/v4/online-checkout/platform/payment-intents</code>
      (<code>capture_method=manual</code>) → Stripe Payment Element <code>confirmPayment</code> (card is only <em>authorized</em>) →
      <br>• <em>Save card:</em> <code>PUT /updatePaymentInstrument {nonce: pi_…}</code> → FullPayments <code>/api/braintree/createVaultWithNonce</code>
      (saves the card, parks the PI) → new jBilling instrument → jBilling payment → <code>/api/braintree/processPayment</code> <strong>captures</strong> the parked PI.
      <br>• <em>One-time:</em> <code>POST /processPayment {nonce: pi_…, createVaultFlag:false}</code> → single-use jBilling instrument →
      <code>/api/braintree/createTransaction/…/{pi_…}</code> <strong>captures</strong> the PI.
      <br><em>ACH (US bank account tab):</em> when ACH is enabled for the brand the PI is created with automatic capture and
      <code>payment_method_options[card][capture_method]=manual</code> (cards are still only authorized) plus
      <code>us_bank_account</code> options; a Stripe customer is created up front for the new customerKey. After
      <code>confirmPayment</code> the bank PI is already <code>processing</code> – the facade does <strong>not</strong> capture it,
      jBilling records the payment as <strong>PENDING</strong> settlement (<code>paymentType=ach</code>). With microdeposits the PI
      stays <code>requires_action</code> until the customer verifies the account.`,
    setup: `<strong>Add / replace card or bank account (no payment).</strong> UI → FullPayments <code>POST /api/stripe/v4/platform/setup-intents</code>
      → Payment Element <code>confirmSetup</code> → <code>PUT /v1/updatePaymentInstrument {paymentMethodId, setupIntentId}</code>
      → hosted API reads the card via <code>GET /api/stripe/v4/platform/payment-methods/{id}</code>, replaces the jBilling instrument,
      and for a <em>new</em> customerKey registers it via <code>/api/braintree/createVaultWithNonce</code> with the <code>seti_…</code> id.
      For an existing customer the saved card is linked by the <code>payment_method.attached</code> webhook.
      <br><em>ACH:</em> same calls with <code>paymentType=ach</code>; the hosted API maps <code>bankAccountDetails</code> and stores a
      <code>stripe-achId</code> instrument (<code>ach.*</code> metafields). Stripe stores the mandate on the payment method.`,
    billing: `<strong>Billing run (recurring charge).</strong> Simulates jBilling charging the owing balance with the instrument on file:
      plugin → FullPayments <code>/api/braintree/processPayment/cc/{customerKey}/…</code> → off-session PaymentIntent.`,
  };

  // ------------------------------------------------------------------ API helper with call log

  async function api(method, url, body, headers = {}) {
    const started = performance.now();
    const opts = { method, headers: { ...headers } };
    if (body !== undefined) {
      opts.headers["Content-Type"] = "application/json";
      opts.body = JSON.stringify(body);
    }
    let status = 0;
    let data = null;
    try {
      const res = await fetch(url, opts);
      status = res.status;
      const text = await res.text();
      try { data = text ? JSON.parse(text) : null; } catch { data = text; }
      if (!res.ok) {
        throw new Error((data && data.message) || `HTTP ${res.status}`);
      }
      return data;
    } finally {
      logCall(method, url, headers, body, status, data, Math.round(performance.now() - started));
    }
  }

  function logCall(method, url, headers, body, status, data, ms) {
    if (state.quiet) return;
    const el = document.createElement("details");
    el.className = "log-entry";
    const ok = status >= 200 && status < 300 && !(data && data.success === false) && !(data && data.status === false);
    el.innerHTML = `<summary><span class="badge ${ok ? "ok" : "bad"}">${status || "ERR"}</span>
      <span class="method">${method}</span> ${escapeHtml(url)} <span class="hint">${ms} ms</span></summary>
      ${Object.keys(headers).length ? `<div class="hint">headers</div><pre>${escapeHtml(JSON.stringify(headers, null, 2))}</pre>` : ""}
      ${body !== undefined ? `<div class="hint">request</div><pre>${escapeHtml(JSON.stringify(body, null, 2))}</pre>` : ""}
      <div class="hint">response</div><pre>${escapeHtml(typeof data === "string" ? data : JSON.stringify(data, null, 2))}</pre>`;
    $("log").prepend(el);
  }

  function logStripe(name, result) {
    logCall("STRIPE", `stripe.${name}() (browser → Stripe)`, {}, undefined, result.error ? 402 : 200,
      result.error ? { error: result.error } : result, 0);
  }

  // ------------------------------------------------------------------ steps

  function resetSteps() { $("steps").innerHTML = ""; }
  function step(text, cls = "") {
    const li = document.createElement("li");
    li.className = cls;
    li.innerHTML = text;
    $("steps").appendChild(li);
  }

  // ------------------------------------------------------------------ init

  async function init() {
    bindEvents();
    selectFlow("saved");
    try {
      state.config = await api("GET", `${CFG.SPIKE_BASE_URL}/config`);
      renderChips();
      await loadAccounts();
    } catch (e) {
      $("status-chips").innerHTML = `<span class="badge bad">Backend not reachable: ${escapeHtml(e.message)}</span>`;
    }
    refreshSide();
  }

  function renderChips() {
    const chips = [];
    for (const b of state.config.brands) {
      chips.push(`<span class="badge ${b.stripeKeysConfigured ? "ok" : "bad"}">${escapeHtml(b.name)}: Stripe keys ${b.stripeKeysConfigured ? "set" : "missing"}</span>`);
      chips.push(`<span class="badge ${b.achEnabled ? "ok" : "neutral"}">${escapeHtml(b.name)}: ACH ${b.achEnabled ? "via Stripe" : "via " + escapeHtml(b.achProvider || "Braintree")}</span>`);
    }
    if (state.config.ach) {
      chips.push(`<span class="badge neutral">ACH verification: ${escapeHtml(state.config.ach.verificationMethod)}</span>`);
      chips.push(`<span class="badge neutral">On ACH failure: ${state.config.ach.reverseJbillingPaymentOnFailure ? "reverse jBilling payment + DS task" : "DS task only"}</span>`);
    }
    chips.push(`<span class="badge ${state.config.webhookSecretConfigured ? "ok" : "warn"}">Webhook secret ${state.config.webhookSecretConfigured ? "configured" : "not configured"}</span>`);
    $("status-chips").innerHTML = chips.join("");
  }

  async function loadAccounts(selectPin) {
    state.accounts = await api("GET", `${CFG.SPIKE_BASE_URL}/accounts`);
    const sel = $("account-select");
    sel.innerHTML = state.accounts.map((a) => `<option value="${a.accountPin}">${a.accountPin} – ${escapeHtml(a.firstName)} ${escapeHtml(a.lastName)}</option>`).join("");
    const pin = selectPin || (state.account && state.account.accountPin) || (state.accounts[0] && state.accounts[0].accountPin);
    if (pin) {
      sel.value = pin;
      await selectAccount(pin);
    }
  }

  async function selectAccount(pin) {
    state.account = state.accounts.find((a) => a.accountPin === pin);
    state.brand = state.config.brands.find((b) => b.brandId === state.account.brandId);
    $("ach-hints").classList.toggle("hidden", !achEnabled());
    await refreshAccount();
  }

  async function refreshAccount() {
    if (!state.account) return;
    const overview = await api("GET", `${CFG.SPIKE_BASE_URL}/accounts/${state.account.accountPin}/overview`);
    state.account = overview.user;
    const res = await api("GET", `${CFG.HOSTED_API_BASE_URL}/getPaymentInstruments?accountPin=${encodeURIComponent(state.account.accountPin)}&brandId=${encodeURIComponent(state.account.brandId)}`);
    state.instrument = (res.instruments || [])[0] || null;
    renderAccount();
    renderInstrument();
    renderPayments(overview.payments);
    syncAutoRefresh(hasPendingAch(overview.payments));
    renderDs(overview.dsActivity);
    if (!state.intent) $("amount").value = Number(state.account.owingBalance) > 0 ? Number(state.account.owingBalance).toFixed(2) : "10.00";
  }

  async function refreshSide() {
    try {
      const fp = await api("GET", `${CFG.SPIKE_BASE_URL}/fullpayments`);
      renderFullPayments(fp);
      const events = await api("GET", `${CFG.SPIKE_BASE_URL}/webhook-events`);
      renderWebhooks(events);
    } catch (e) { /* shown in log */ }
  }

  async function refreshAll() {
    await refreshAccount();
    await refreshSide();
  }

  // ------------------------------------------------------------------ rendering

  function renderAccount() {
    const a = state.account;
    const customerKey = state.instrument ? state.instrument["BT ID"] : null;
    $("account-details").innerHTML = `
      <dt>Account PIN</dt><dd>${escapeHtml(a.accountPin)}</dd>
      <dt>jBilling user id</dt><dd>${a.id}</dd>
      <dt>Brand</dt><dd>${escapeHtml(state.brand ? state.brand.name : a.brandId)} <span class="hint">${escapeHtml(a.brandId)}</span></dd>
      <dt>Stripe business id</dt><dd><code>${escapeHtml(state.brand ? state.brand.stripeBusinessId : "")}</code></dd>
      <dt>Name / email</dt><dd>${escapeHtml(a.firstName)} ${escapeHtml(a.lastName)} · ${escapeHtml(a.email)}</dd>
      <dt>Owing balance</dt><dd><strong>${state.brand ? state.brand.currencySymbol : "$"}${Number(a.owingBalance).toFixed(2)}</strong></dd>
      <dt>customerKey (BT ID)</dt><dd>${customerKey ? `<code>${escapeHtml(customerKey)}</code>` : '<span class="empty">none yet – created on first saved payment method</span>'}</dd>`;
  }

  function renderInstrument() {
    const i = state.instrument;
    if (!i) {
      $("instrument").innerHTML = '<p class="empty">No payment instrument on file.</p>';
      return;
    }
    const rows = Object.entries(i).map(([k, v]) => `<dt>${escapeHtml(k)}</dt><dd>${escapeHtml(String(v))}</dd>`).join("");
    const summary = i.type === "ach"
      ? `<span class="badge ok">ACH</span> ${escapeHtml(i["ach.bank.name"] || "Bank account")} ${escapeHtml(i["ach.account.number"] || "")}`
      : `<span class="badge neutral">Card</span> ${escapeHtml(i["cc.type"] || "")} ${escapeHtml(i["cc.number"] || "")}`;
    $("instrument").innerHTML = `<p>${summary}</p><dl class="kv">${rows}</dl>`;
  }

  function renderPayments(payments) {
    if (!payments || !payments.length) {
      $("payments").innerHTML = '<p class="empty">No payments yet.</p>';
      return;
    }
    $("payments").innerHTML = `<div class="table-wrap"><table>
      <tr><th>#</th><th>When</th><th>Amount</th><th>Result</th><th>Settlement</th><th>Transaction (Stripe)</th><th>Method / notes</th><th></th></tr>
      ${payments.map((p) => `<tr>
        <td>${p.id}</td>
        <td>${fmtTime(p.createdAt)}</td>
        <td>${p.refund ? "−" : ""}$${Number(p.amount).toFixed(2)}${p.refund ? ` <span class="badge neutral">refund of #${p.linkedPaymentId}</span>` : ""}</td>
        <td><span class="badge ${p.result === "Successful" ? "ok" : "bad"}">${escapeHtml(p.result)}</span>
            ${p.result !== "Successful" ? `<div class="hint">${escapeHtml(p.gatewayMessage || "")}</div>` : ""}</td>
        <td>${p.settlementStatus ? badgeFor(p.settlementStatus) : `<span class="hint">${p.paymentMethodType === "card" ? "card" : ""}</span>`}
            ${p.settlementStatus && p.gatewayMessage && p.settlementStatus !== "PENDING" ? `<div class="hint">${escapeHtml(p.gatewayMessage)}</div>` : ""}</td>
        <td>${stripeLink(p.transactionId)}</td>
        <td>${p.paymentMethodType === "ach" ? '<span class="badge ok">ACH</span> ' : ""}${escapeHtml(p.paymentMethod || "")}<div class="hint">${escapeHtml(p.notes || "")}</div></td>
        <td>${refundable(p) ? `<button class="link" data-refund="${p.id}">Refund</button>` : ""}</td>
      </tr>`).join("")}
    </table></div>`;
  }

  // ACH: a pending (not settled) or returned ACH payment cannot be refunded
  function refundable(p) {
    return !p.refund && p.result === "Successful" && (!p.settlementStatus || p.settlementStatus === "SETTLED");
  }

  function hasPendingAch(payments) {
    return (payments || []).some((p) => p.settlementStatus === "PENDING");
  }

  function renderFullPayments(fp) {
    const customers = fp.platformCustomers || [];
    const txs = fp.transactions || [];
    $("fullpayments").innerHTML = `
      <h3>StripePlatformCustomer</h3>
      ${customers.length ? `<div class="table-wrap"><table>
        <tr><th>externalId (customerKey)</th><th>merchantId (accountPin)</th><th>Stripe customer</th><th>businessId</th><th>paymentMethodIds</th></tr>
        ${customers.map((c) => `<tr><td><code>${escapeHtml(c.externalId)}</code></td><td>${escapeHtml(c.merchantId || "")}</td>
          <td>${stripeCustomerLink(c.stripeCustomerId)}</td><td>${escapeHtml(c.businessId)}</td>
          <td>${(c.paymentMethodIds || []).map((x) => `<code>${escapeHtml(x)}</code>`).join("<br>") || '<span class="empty">none</span>'}</td></tr>`).join("")}
      </table></div>` : '<p class="empty">None.</p>'}
      <h3>TransactionJDO</h3>
      ${txs.length ? `<div class="table-wrap"><table>
        <tr><th>id</th><th>transactionId</th><th>responseCode / status</th><th>type</th><th>amount</th><th>customerKey</th><th>reference</th></tr>
        ${txs.map((t) => `<tr><td>${t.id}</td><td>${stripeLink(t.transactionId)}</td>
          <td>${badgeFor(t.responseCode || t.responseStatus)}${t.failureCode || t.failureMessage ? `<div class="hint">${escapeHtml(t.failureCode || "")} ${escapeHtml(t.failureMessage || "")}</div>` : ""}</td>
          <td>${escapeHtml(t.paymentMethodType || "")}</td><td>${t.amount ? "$" + escapeHtml(t.amount) : ""}</td>
          <td>${t.customerKey ? `<code>${escapeHtml(t.customerKey)}</code>` : ""}</td><td>${stripeLink(t.referenceTransaction)}</td></tr>`).join("")}
      </table></div>` : '<p class="empty">None.</p>'}
      <h3>Caches</h3>
      <pre>${escapeHtml(JSON.stringify(fp.caches, null, 2))}</pre>`;
  }

  function renderWebhooks(events) {
    if (!events || !events.length) {
      $("webhooks").innerHTML = `<p class="empty">No webhook events yet. Run the stripe-cli container (see README) to forward events.</p>`;
      return;
    }
    $("webhooks").innerHTML = `<div class="table-wrap"><table>
      <tr><th>When</th><th>Type</th><th>Object</th><th>Outcome</th></tr>
      ${events.map((e) => `<tr><td>${fmtTime(e.receivedAt)}</td><td><code>${escapeHtml(e.type)}</code></td>
        <td>${e.objectId ? `<code>${escapeHtml(e.objectId)}</code>` : ""}</td><td>${escapeHtml(e.outcome)}</td></tr>`).join("")}
    </table></div>`;
  }

  function renderDs(items) {
    if (!items || !items.length) {
      $("ds").innerHTML = '<p class="empty">No DS activity.</p>';
      return;
    }
    $("ds").innerHTML = items.map((d) => `<details class="log-entry"><summary>
      <span class="badge ${d.kind === "TASK" ? "warn" : "neutral"}">${d.kind}</span> ${escapeHtml(d.title)} <span class="hint">${fmtTime(d.createdAt)}</span>
      </summary><pre>${escapeHtml(d.text)}</pre></details>`).join("");
  }

  // ------------------------------------------------------------------ flows

  function selectFlow(flow) {
    state.flow = flow;
    document.querySelectorAll(".tab").forEach((t) => t.classList.toggle("active", t.dataset.flow === flow));
    $("flow-description").innerHTML = FLOWS[flow];
    $("save-row").classList.toggle("hidden", flow !== "new");
    $("ach-hints").classList.toggle("hidden", !achEnabled());
    $("amount-row").classList.toggle("hidden", flow === "setup");
    resetIntent();
    resetSteps();
  }

  function resetIntent() {
    if (state.elements) {
      const el = state.elements.getElement("payment");
      if (el) el.destroy();
    }
    state.elements = null;
    state.intent = null;
    state.pmType = "card";
    $("element-wrap").classList.add("hidden");
    $("verify-wrap").classList.add("hidden");
    $("btn-continue").classList.add("hidden");
    $("btn-confirm").classList.add("hidden");
    $("btn-cancel").classList.add("hidden");
    $("btn-start").classList.remove("hidden");
    $("btn-start").disabled = false;
    $("amount").disabled = false;
    $("save-card").disabled = false;
  }

  function amount() {
    const v = Number($("amount").value);
    if (!(v > 0)) throw new Error("Enter an amount");
    return v.toFixed(2);
  }

  function customerKey() {
    return state.instrument ? state.instrument["BT ID"] : null;
  }

  function achEnabled() {
    return !!(state.brand && state.brand.achEnabled);
  }

  // ACH: the hosted API paymentType ('cc' | 'ach') for the method selected in the Payment Element
  function paymentType() {
    return state.pmType === "us_bank_account" ? "ach" : "cc";
  }

  // ACH: a bank PaymentIntent needs a Stripe customer before confirmation (mandate + saving the bank account), so for a
  // brand-new jBilling customer the UI generates the customerKey up front; FullPayments creates the Stripe customer
  // for it, and the hosted API stores the same key as "BT ID" (paymentDetails.customerKey).
  function customerKeyForPaymentIntent() {
    const existing = customerKey();
    if (existing || !achEnabled()) return { key: existing, generated: false };
    const key = (window.crypto && crypto.randomUUID) ? crypto.randomUUID()
      : "ck-" + Date.now().toString(36) + "-" + Math.random().toString(36).slice(2);
    return { key, generated: true };
  }

  function fpHeaders() {
    return { "X-Business-ID": state.brand.stripeBusinessId };
  }

  async function start() {
    resetSteps();
    $("btn-start").disabled = true;
    try {
      if (state.flow === "saved") await paySaved();
      else if (state.flow === "billing") await billingRun();
      else if (state.flow === "new") await startNewCard();
      else if (state.flow === "setup") await startSetup();
    } catch (e) {
      step(`Failed: ${escapeHtml(e.message)}`, "bad");
      resetIntent();
    } finally {
      if (state.flow === "saved" || state.flow === "billing") $("btn-start").disabled = false;
      await refreshAll();
    }
  }

  async function paySaved() {
    if (!state.instrument) throw new Error("No payment instrument on file – add a card first");
    step(`Hosted API <code>POST /processPayment</code> with <code>instrumentId</code> (<code>paymentType=${escapeHtml(state.instrument.type || "cc")}</code>)`);
    const res = await api("POST", `${CFG.HOSTED_API_BASE_URL}/processPayment`, {
      accountPin: state.account.accountPin,
      brandId: state.account.brandId,
      paymentType: state.instrument.type || "cc",
      paymentAmount: amount(),
      instrumentId: state.instrument.id,
      requestedBy: "spike-ui",
    });
    finish(res);
  }

  async function billingRun() {
    step("Spike <code>POST /spike/accounts/{pin}/billing-run</code> (simulated jBilling billing process)");
    const res = await api("POST", `${CFG.SPIKE_BASE_URL}/accounts/${state.account.accountPin}/billing-run`, { amount: amount() });
    const ok = res.payment && res.payment.result === "Successful";
    step(ok ? `jBilling payment #${res.payment.id} recorded – ${stripeLink(res.payment.transactionId)}`
      : `Charge failed: ${escapeHtml(res.authorization && res.authorization.responseMessage)}`, ok ? "ok" : "bad");
  }

  async function startNewCard() {
    const save = $("save-card").checked;
    const amt = amount();
    const { key, generated } = customerKeyForPaymentIntent();
    const qs = key ? `?customerKey=${encodeURIComponent(key)}` : "";
    // ACH: a one-time payment must not save the bank account → NONE (no setup_future_usage). Cards keep ON_SESSION.
    const futureUsage = save ? "OFF_SESSION" : achEnabled() ? "NONE" : "ON_SESSION";
    if (generated) step(`New customer – UI generated customerKey <code>${escapeHtml(key)}</code> (FullPayments creates the Stripe customer up front)`);
    step(`FullPayments <code>POST /api/stripe/v4/online-checkout/platform/payment-intents${escapeHtml(qs)}</code> (futureUsage ${futureUsage})`);
    const a = state.account;
    const pi = await api("POST", `${CFG.FULLPAYMENTS_BASE_URL}/api/stripe/v4/online-checkout/platform/payment-intents${qs}`, {
      amountMoney: { amount: Math.round(Number(amt) * 100), currency: "USD" },
      futureUsage,
      // ACH: routes later webhooks (settlement, bank return, dispute) to the jBilling account
      metadata: { accountPin: a.accountPin, brandId: a.brandId, jbillingUserId: String(a.id) },
    }, fpHeaders());
    state.intent = { kind: "payment", id: pi.id, clientSecret: pi.clientSecret, save, amount: amt, customerKey: generated ? key : null };
    step(`Created PaymentIntent ${stripeLink(pi.id)}${pi.achEnabled ? " (card + US bank account)" : ""} – enter card or bank details and confirm`, "ok");
    mountElement(pi.publicKey, pi.clientSecret);
  }

  async function startSetup() {
    const key = customerKey();
    const qs = key ? `?customerKey=${encodeURIComponent(key)}` : "";
    step(`FullPayments <code>POST /api/stripe/v4/platform/setup-intents${escapeHtml(qs)}</code>${key ? "" : " (no customerKey → signup-style setup intent)"}`);
    const si = await api("POST", `${CFG.FULLPAYMENTS_BASE_URL}/api/stripe/v4/platform/setup-intents${qs}`, undefined, fpHeaders());
    state.intent = { kind: "setup", id: si.id, clientSecret: si.clientSecret };
    step(`Created SetupIntent <code>${escapeHtml(si.id)}</code>${si.achEnabled ? " (card + US bank account)" : ""} – enter card or bank details and confirm`, "ok");
    mountElement(si.publicKey, si.clientSecret);
  }

  function mountElement(publicKey, clientSecret) {
    if (!state.stripe || state.stripeKey !== publicKey) {
      state.stripe = Stripe(publicKey);
      state.stripeKey = publicKey;
    }
    state.elements = state.stripe.elements({ clientSecret, appearance: { theme: "stripe" } });
    // ACH: with ACH Direct Debit enabled on the Stripe account and the intent allowing us_bank_account, the same
    // Payment Element shows a "US bank account" tab (Financial Connections, manual entry fallback, mandate text).
    const a = state.account;
    const paymentElement = state.elements.create("payment", {
      layout: "tabs",
      defaultValues: { billingDetails: { name: `${a.firstName} ${a.lastName}`, email: a.email } },
    });
    // ACH: remember which tab the customer picked – decides paymentType cc/ach for the hosted API
    paymentElement.on("change", (event) => {
      if (event.value && event.value.type) {
        state.pmType = event.value.type;
        $("selected-type").textContent = state.pmType === "us_bank_account" ? "US bank account (ACH)" : state.pmType;
      }
    });
    paymentElement.mount("#payment-element");
    $("selected-type").textContent = "card";
    $("element-wrap").classList.remove("hidden");
    $("btn-start").classList.add("hidden");
    $("btn-confirm").classList.remove("hidden");
    $("btn-cancel").classList.remove("hidden");
    $("amount").disabled = true;
    $("save-card").disabled = true;
  }

  async function confirm() {
    $("btn-confirm").disabled = true;
    const a = state.account;
    const billing = { name: `${a.firstName} ${a.lastName}`, email: a.email, address: { postal_code: a.postalCode, country: a.country } };
    let keepIntent = false;
    try {
      if (state.intent.kind === "payment") {
        const result = await state.stripe.confirmPayment({
          elements: state.elements,
          redirect: "if_required",
          confirmParams: { return_url: window.location.href, payment_method_data: { billing_details: billing } },
        });
        logStripe("confirmPayment", result);
        if (result.error) throw new Error(result.error.message);
        keepIntent = await handlePaymentIntent(result.paymentIntent);
      } else {
        const result = await state.stripe.confirmSetup({
          elements: state.elements,
          redirect: "if_required",
          confirmParams: { return_url: window.location.href, payment_method_data: { billing_details: billing } },
        });
        logStripe("confirmSetup", result);
        if (result.error) throw new Error(result.error.message);
        keepIntent = await handleSetupIntent(result.setupIntent);
      }
    } catch (e) {
      step(`Failed: ${escapeHtml(e.message)}`, "bad");
    } finally {
      $("btn-confirm").disabled = false;
      if (!keepIntent) resetIntent();
      await refreshAll();
    }
  }

  /**
   * Card: requires_capture (authorized). ACH: processing (debit submitted – Financial Connections) or requires_action
   * (manual entry → microdeposits). Returns true when we have to wait for the customer to verify the account.
   */
  async function handlePaymentIntent(pi) {
    const isBank = state.pmType === "us_bank_account" || (pi.payment_method_types || []).length === 1 && pi.payment_method_types[0] === "us_bank_account";
    if (isBank) state.pmType = "us_bank_account";
    if (pi.status === "requires_action" && pi.next_action && pi.next_action.type === "verify_with_microdeposits") {
      showMicrodeposits(pi.next_action.verify_with_microdeposits, "PaymentIntent");
      return true;
    }
    if (pi.status === "requires_capture") {
      step(`Stripe <code>confirmPayment</code> → status <code>requires_capture</code> (card authorized, not captured yet)`, "ok");
    } else if (pi.status === "processing") {
      step(`Stripe <code>confirmPayment</code> → status <code>processing</code> (ACH debit submitted – nothing to capture, settles later)`, "ok");
    } else if (pi.status === "succeeded") {
      step(`Stripe <code>confirmPayment</code> → status <code>succeeded</code>`, "ok");
    } else {
      throw new Error(`Unexpected PaymentIntent status ${pi.status}`);
    }
    await afterPaymentConfirmed(pi);
    return false;
  }

  async function handleSetupIntent(si) {
    if (si.status === "requires_action" && si.next_action && si.next_action.type === "verify_with_microdeposits") {
      state.pmType = "us_bank_account";
      showMicrodeposits(si.next_action.verify_with_microdeposits, "SetupIntent");
      return true;
    }
    if (si.status !== "succeeded") throw new Error(`Unexpected SetupIntent status ${si.status}`);
    step(`Stripe <code>confirmSetup</code> → status <code>succeeded</code>, payment method <code>${escapeHtml(si.payment_method)}</code>`, "ok");
    await afterSetupConfirmed(si);
    return false;
  }

  // ACH: manual account entry → Stripe sends microdeposits; the customer verifies on Stripe's hosted page.
  function showMicrodeposits(v, kind) {
    step(`Stripe → <code>requires_action</code> / <code>verify_with_microdeposits</code> (${escapeHtml(v.microdeposit_type || "")}).
      Verify the bank account on Stripe's hosted page, then click <em>Continue after verification</em>.`, "warn");
    $("verify-link").href = v.hosted_verification_url;
    $("verify-wrap").classList.remove("hidden");
    $("element-wrap").classList.add("hidden");
    $("btn-confirm").classList.add("hidden");
    $("btn-continue").classList.remove("hidden");
    state.intent.awaitingVerification = kind;
  }

  async function continueAfterVerification() {
    $("btn-continue").disabled = true;
    let keepIntent = true;
    try {
      if (state.intent.kind === "payment") {
        const result = await state.stripe.retrievePaymentIntent(state.intent.clientSecret);
        logStripe("retrievePaymentIntent", result);
        if (result.error) throw new Error(result.error.message);
        const pi = result.paymentIntent;
        if (pi.status === "requires_action") {
          step("Still <code>requires_action</code> – the bank account is not verified yet", "warn");
          return;
        }
        keepIntent = false;
        $("verify-wrap").classList.add("hidden");
        await handlePaymentIntent(pi);
      } else {
        const result = await state.stripe.retrieveSetupIntent(state.intent.clientSecret);
        logStripe("retrieveSetupIntent", result);
        if (result.error) throw new Error(result.error.message);
        const si = result.setupIntent;
        if (si.status === "requires_action") {
          step("Still <code>requires_action</code> – the bank account is not verified yet", "warn");
          return;
        }
        keepIntent = false;
        $("verify-wrap").classList.add("hidden");
        await handleSetupIntent(si);
      }
    } catch (e) {
      keepIntent = false;
      step(`Failed: ${escapeHtml(e.message)}`, "bad");
    } finally {
      $("btn-continue").disabled = false;
      if (!keepIntent) resetIntent();
      await refreshAll();
    }
  }

  async function afterPaymentConfirmed(pi) {
    const a = state.account;
    const type = paymentType();
    const paymentDetails = { nonce: pi.id, firstName: a.firstName, lastName: a.lastName, zip: a.postalCode, country: a.country, email: a.email };
    if (state.intent.customerKey) paymentDetails.customerKey = state.intent.customerKey; // ACH: UI-generated key
    if (state.intent.save) {
      step(`Hosted API <code>PUT /updatePaymentInstrument</code> with <code>nonce = pi_…</code>, <code>paymentType=${type}</code> (save + pay)`);
      const res = await api("PUT", `${CFG.HOSTED_API_BASE_URL}/updatePaymentInstrument`, {
        accountPin: a.accountPin, brandId: a.brandId, paymentType: type, amount: state.intent.amount,
        createVaultFlag: true, requestedBy: "spike-ui", paymentDetails,
      });
      finish(res);
    } else {
      step(`Hosted API <code>POST /processPayment</code> with <code>nonce = pi_…</code>, <code>createVaultFlag=false</code>, <code>paymentType=${type}</code> (one-time)`);
      const res = await api("POST", `${CFG.HOSTED_API_BASE_URL}/processPayment`, {
        accountPin: a.accountPin, brandId: a.brandId, paymentType: type, paymentAmount: state.intent.amount,
        nonce: pi.id, createVaultFlag: false, requestedBy: "spike-ui",
      });
      finish(res);
    }
  }

  async function afterSetupConfirmed(si) {
    const a = state.account;
    const type = paymentType();
    step(`Hosted API <code>PUT /v1/updatePaymentInstrument</code> with <code>paymentMethodId</code> + <code>setupIntentId</code>, <code>paymentType=${type}</code>`);
    const res = await api("PUT", `${CFG.HOSTED_API_BASE_URL}/v1/updatePaymentInstrument`, {
      accountPin: a.accountPin, brandId: a.brandId, paymentType: type, requestedBy: "spike-ui",
      paymentDetails: { paymentMethodId: si.payment_method, setupIntentId: si.id, firstName: a.firstName, lastName: a.lastName, zip: a.postalCode, country: a.country, email: a.email },
    });
    if (!res.success) throw new Error(res.message);
    step(`Instrument #${res.instrumentId} saved in jBilling (customerKey <code>${escapeHtml(res.customerKey)}</code>)`, "ok");
  }

  function finish(res) {
    if (res.success) {
      step(`${escapeHtml(res.message)} – ${stripeLink(res.transactionId)} · owing balance ${escapeHtml(res.owingBalance || "")}`, "ok");
      if (res.paymentStatus === "PENDING") {
        step(`ACH payment is <strong>PENDING</strong> settlement – watch the webhooks panel for <code>payment_intent.succeeded</code> /
          <code>payment_intent.payment_failed</code> (auto-refresh is on while a payment is pending).`, "warn");
      }
    } else {
      throw new Error(res.message || "Failed");
    }
  }

  async function refund(paymentId) {
    resetSteps();
    step(`Spike <code>POST /spike/payments/${paymentId}/refund</code> → jBilling → FullPayments <code>/api/braintree/refundTransaction</code>`);
    try {
      const res = await api("POST", `${CFG.SPIKE_BASE_URL}/payments/${paymentId}/refund`, {});
      const ok = res.payment && res.payment.result === "Successful";
      step(ok ? `Refund recorded – <code>${escapeHtml(res.payment.transactionId || "")}</code>` : `Refund failed: ${escapeHtml(res.authorization.responseMessage)}`, ok ? "ok" : "bad");
    } catch (e) {
      step(`Failed: ${escapeHtml(e.message)}`, "bad");
    }
    await refreshAll();
  }

  async function newAccount() {
    const brandId = state.config.brands[0].brandId;
    const acc = await api("POST", `${CFG.SPIKE_BASE_URL}/accounts`, { brandId, owingBalance: 49.0 });
    await loadAccounts(acc.accountPin);
    await refreshSide();
  }

  // ------------------------------------------------------------------ events & utils

  function bindEvents() {
    $("tabs").addEventListener("click", (e) => { if (e.target.dataset.flow) selectFlow(e.target.dataset.flow); });
    $("btn-start").addEventListener("click", start);
    $("btn-confirm").addEventListener("click", confirm);
    $("btn-continue").addEventListener("click", continueAfterVerification);
    $("auto-refresh").addEventListener("change", () => syncAutoRefresh(false));
    $("btn-cancel").addEventListener("click", () => { resetIntent(); step("Cancelled", "bad"); });
    $("btn-refresh").addEventListener("click", refreshAll);
    $("btn-new-account").addEventListener("click", newAccount);
    $("btn-clear-log").addEventListener("click", () => { $("log").innerHTML = ""; });
    $("account-select").addEventListener("change", (e) => { selectFlow(state.flow); selectAccount(e.target.value); });
    $("payments").addEventListener("click", (e) => { if (e.target.dataset.refund) refund(e.target.dataset.refund); });
  }

  function stripeLink(id) {
    if (!id) return "";
    const path = id.startsWith("pi_") ? "payments" : id.startsWith("re_") ? "refunds" : null;
    return path ? `<a href="https://dashboard.stripe.com/test/${path}/${encodeURIComponent(id)}" target="_blank" rel="noopener"><code>${escapeHtml(id)}</code></a>` : `<code>${escapeHtml(id)}</code>`;
  }

  function stripeCustomerLink(id) {
    return `<a href="https://dashboard.stripe.com/test/customers/${encodeURIComponent(id)}" target="_blank" rel="noopener"><code>${escapeHtml(id)}</code></a>`;
  }

  function badgeFor(code) {
    if (!code) return "";
    const cls = ["COMPLETED", "Settled", "SETTLED"].includes(code) ? "ok"
      : code === "PROFILE" ? "neutral"
      : ["FAILED", "REVERSED", "failed", "canceled"].includes(code) ? "bad" : "warn";
    return `<span class="badge ${cls}">${escapeHtml(code)}</span>`;
  }

  // ACH: poll while a payment waits for its settlement webhook (or when the checkbox is ticked)
  function syncAutoRefresh(pending) {
    const on = $("auto-refresh").checked || pending;
    if (on && !state.autoRefresh) {
      state.autoRefresh = setInterval(async () => {
        if (state.intent) return;
        state.quiet = true; // don't flood the API call log while polling
        try { await refreshAll(); } finally { state.quiet = false; }
      }, 5000);
    } else if (!on && state.autoRefresh) {
      clearInterval(state.autoRefresh);
      state.autoRefresh = null;
    }
  }

  function fmtTime(t) {
    if (!t) return "";
    const d = new Date(t);
    return isNaN(d) ? escapeHtml(String(t)) : d.toLocaleTimeString();
  }

  function escapeHtml(s) {
    return String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
  }

  init();
})();
