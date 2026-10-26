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
    intent: null,      // { kind: 'payment'|'setup', id, clientSecret }
  };

  const FLOWS = {
    saved: `<strong>Pay with saved card.</strong> UI → <code>POST /processPayment {instrumentId}</code> (hosted API)
      → jBilling processPayment → plugin → FullPayments <code>/api/braintree/processPayment/cc/{customerKey}/…</code>
      → off-session PaymentIntent (<code>confirm=true, off_session=true</code>) with the card saved on the Stripe customer.`,
    new: `<strong>Pay with a new card.</strong> UI → FullPayments <code>POST /api/stripe/v4/online-checkout/platform/payment-intents</code>
      (<code>capture_method=manual</code>) → Stripe Payment Element <code>confirmPayment</code> (card is only <em>authorized</em>) →
      <br>• <em>Save card:</em> <code>PUT /updatePaymentInstrument {nonce: pi_…}</code> → FullPayments <code>/api/braintree/createVaultWithNonce</code>
      (saves the card, parks the PI) → new jBilling instrument → jBilling payment → <code>/api/braintree/processPayment</code> <strong>captures</strong> the parked PI.
      <br>• <em>One-time:</em> <code>POST /processPayment {nonce: pi_…, createVaultFlag:false}</code> → single-use jBilling instrument →
      <code>/api/braintree/createTransaction/…/{pi_…}</code> <strong>captures</strong> the PI.`,
    setup: `<strong>Add / replace card (no payment).</strong> UI → FullPayments <code>POST /api/stripe/v4/platform/setup-intents</code>
      → Payment Element <code>confirmSetup</code> → <code>PUT /v1/updatePaymentInstrument {paymentMethodId, setupIntentId}</code>
      → hosted API reads the card via <code>GET /api/stripe/v4/platform/payment-methods/{id}</code>, replaces the jBilling instrument,
      and for a <em>new</em> customerKey registers it via <code>/api/braintree/createVaultWithNonce</code> with the <code>seti_…</code> id.
      For an existing customer the saved card is linked by the <code>payment_method.attached</code> webhook.`,
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
      <dt>customerKey (BT ID)</dt><dd>${customerKey ? `<code>${escapeHtml(customerKey)}</code>` : '<span class="empty">none yet – created on first saved card</span>'}</dd>`;
  }

  function renderInstrument() {
    const i = state.instrument;
    if (!i) {
      $("instrument").innerHTML = '<p class="empty">No payment instrument on file.</p>';
      return;
    }
    const rows = Object.entries(i).map(([k, v]) => `<dt>${escapeHtml(k)}</dt><dd>${escapeHtml(String(v))}</dd>`).join("");
    $("instrument").innerHTML = `<dl class="kv">${rows}</dl>`;
  }

  function renderPayments(payments) {
    if (!payments || !payments.length) {
      $("payments").innerHTML = '<p class="empty">No payments yet.</p>';
      return;
    }
    $("payments").innerHTML = `<div class="table-wrap"><table>
      <tr><th>#</th><th>When</th><th>Amount</th><th>Result</th><th>Transaction (Stripe)</th><th>Method / notes</th><th></th></tr>
      ${payments.map((p) => `<tr>
        <td>${p.id}</td>
        <td>${fmtTime(p.createdAt)}</td>
        <td>${p.refund ? "−" : ""}$${Number(p.amount).toFixed(2)}${p.refund ? ` <span class="badge neutral">refund of #${p.linkedPaymentId}</span>` : ""}</td>
        <td><span class="badge ${p.result === "Successful" ? "ok" : "bad"}">${escapeHtml(p.result)}</span>
            ${p.result !== "Successful" ? `<div class="hint">${escapeHtml(p.gatewayMessage || "")}</div>` : ""}</td>
        <td>${stripeLink(p.transactionId)}</td>
        <td>${escapeHtml(p.paymentMethod || "")}<div class="hint">${escapeHtml(p.notes || "")}</div></td>
        <td>${!p.refund && p.result === "Successful" ? `<button class="link" data-refund="${p.id}">Refund</button>` : ""}</td>
      </tr>`).join("")}
    </table></div>`;
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
        <tr><th>id</th><th>transactionId</th><th>responseCode / status</th><th>amount</th><th>customerKey</th><th>reference</th></tr>
        ${txs.map((t) => `<tr><td>${t.id}</td><td>${stripeLink(t.transactionId)}</td>
          <td>${badgeFor(t.responseCode || t.responseStatus)}</td><td>${t.amount ? "$" + escapeHtml(t.amount) : ""}</td>
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
    $("element-wrap").classList.add("hidden");
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
    step("Hosted API <code>POST /processPayment</code> with <code>instrumentId</code>");
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
    const key = customerKey();
    const qs = key ? `?customerKey=${encodeURIComponent(key)}` : "";
    step(`FullPayments <code>POST /api/stripe/v4/online-checkout/platform/payment-intents${escapeHtml(qs)}</code> (futureUsage ${save ? "OFF_SESSION" : "ON_SESSION"})`);
    const pi = await api("POST", `${CFG.FULLPAYMENTS_BASE_URL}/api/stripe/v4/online-checkout/platform/payment-intents${qs}`, {
      amountMoney: { amount: Math.round(Number(amt) * 100), currency: "USD" },
      futureUsage: save ? "OFF_SESSION" : "ON_SESSION",
    }, fpHeaders());
    state.intent = { kind: "payment", id: pi.id, clientSecret: pi.clientSecret, save, amount: amt };
    step(`Created PaymentIntent ${stripeLink(pi.id)} – enter card details and confirm`, "ok");
    mountElement(pi.publicKey, pi.clientSecret);
  }

  async function startSetup() {
    const key = customerKey();
    const qs = key ? `?customerKey=${encodeURIComponent(key)}` : "";
    step(`FullPayments <code>POST /api/stripe/v4/platform/setup-intents${escapeHtml(qs)}</code>${key ? "" : " (no customerKey → signup-style setup intent)"}`);
    const si = await api("POST", `${CFG.FULLPAYMENTS_BASE_URL}/api/stripe/v4/platform/setup-intents${qs}`, undefined, fpHeaders());
    state.intent = { kind: "setup", id: si.id, clientSecret: si.clientSecret };
    step(`Created SetupIntent <code>${escapeHtml(si.id)}</code> – enter card details and confirm`, "ok");
    mountElement(si.publicKey, si.clientSecret);
  }

  function mountElement(publicKey, clientSecret) {
    if (!state.stripe || state.stripeKey !== publicKey) {
      state.stripe = Stripe(publicKey);
      state.stripeKey = publicKey;
    }
    state.elements = state.stripe.elements({ clientSecret, appearance: { theme: "stripe" } });
    // ACH: once the AnswerForce Stripe account has ACH Direct Debit enabled and the intents allow it,
    // the same Payment Element shows a "US bank account" tab (Financial Connections + mandate text).
    const paymentElement = state.elements.create("payment", { layout: "tabs" });
    paymentElement.mount("#payment-element");
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
    try {
      if (state.intent.kind === "payment") {
        const result = await state.stripe.confirmPayment({
          elements: state.elements,
          redirect: "if_required",
          confirmParams: { return_url: window.location.href, payment_method_data: { billing_details: billing } },
        });
        logStripe("confirmPayment", result);
        if (result.error) throw new Error(result.error.message);
        const pi = result.paymentIntent;
        step(`Stripe <code>confirmPayment</code> → status <code>${escapeHtml(pi.status)}</code> (card authorized, not captured yet)`, "ok");
        // ACH: a bank payment comes back as "processing" (no capture possible) – the hosted API / facade must accept it.
        await afterPaymentConfirmed(pi);
      } else {
        const result = await state.stripe.confirmSetup({
          elements: state.elements,
          redirect: "if_required",
          confirmParams: { return_url: window.location.href, payment_method_data: { billing_details: billing } },
        });
        logStripe("confirmSetup", result);
        if (result.error) throw new Error(result.error.message);
        const si = result.setupIntent;
        step(`Stripe <code>confirmSetup</code> → status <code>${escapeHtml(si.status)}</code>, payment method <code>${escapeHtml(si.payment_method)}</code>`, "ok");
        await afterSetupConfirmed(si);
      }
    } catch (e) {
      step(`Failed: ${escapeHtml(e.message)}`, "bad");
    } finally {
      $("btn-confirm").disabled = false;
      resetIntent();
      await refreshAll();
    }
  }

  async function afterPaymentConfirmed(pi) {
    const a = state.account;
    const paymentDetails = { nonce: pi.id, firstName: a.firstName, lastName: a.lastName, zip: a.postalCode, country: a.country, email: a.email };
    if (state.intent.save) {
      step("Hosted API <code>PUT /updatePaymentInstrument</code> with <code>nonce = pi_…</code> (save card + pay)");
      const res = await api("PUT", `${CFG.HOSTED_API_BASE_URL}/updatePaymentInstrument`, {
        accountPin: a.accountPin, brandId: a.brandId, paymentType: "cc", amount: state.intent.amount,
        createVaultFlag: true, requestedBy: "spike-ui", paymentDetails,
      });
      finish(res);
    } else {
      step("Hosted API <code>POST /processPayment</code> with <code>nonce = pi_…</code>, <code>createVaultFlag=false</code> (one-time)");
      const res = await api("POST", `${CFG.HOSTED_API_BASE_URL}/processPayment`, {
        accountPin: a.accountPin, brandId: a.brandId, paymentType: "cc", paymentAmount: state.intent.amount,
        nonce: pi.id, createVaultFlag: false, requestedBy: "spike-ui",
      });
      finish(res);
    }
  }

  async function afterSetupConfirmed(si) {
    const a = state.account;
    step("Hosted API <code>PUT /v1/updatePaymentInstrument</code> with <code>paymentMethodId</code> + <code>setupIntentId</code>");
    const res = await api("PUT", `${CFG.HOSTED_API_BASE_URL}/v1/updatePaymentInstrument`, {
      accountPin: a.accountPin, brandId: a.brandId, paymentType: "cc", requestedBy: "spike-ui",
      paymentDetails: { paymentMethodId: si.payment_method, setupIntentId: si.id, firstName: a.firstName, lastName: a.lastName, zip: a.postalCode, country: a.country, email: a.email },
    });
    if (!res.success) throw new Error(res.message);
    step(`Instrument #${res.instrumentId} saved in jBilling (customerKey <code>${escapeHtml(res.customerKey)}</code>)`, "ok");
  }

  function finish(res) {
    if (res.success) {
      step(`${escapeHtml(res.message)} – ${stripeLink(res.transactionId)} · owing balance ${escapeHtml(res.owingBalance || "")}`, "ok");
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
    const cls = code === "COMPLETED" || code === "Settled" ? "ok" : code === "PROFILE" ? "neutral" : code === "FAILED" ? "bad" : "warn";
    return `<span class="badge ${cls}">${escapeHtml(code)}</span>`;
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
