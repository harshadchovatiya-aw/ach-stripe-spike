// Where the UI sends requests. In Docker, nginx proxies /backend/* to the Spring Boot container.
// The real UI calls two different services; in the spike both live in the same backend.
window.SPIKE_CONFIG = {
  FULLPAYMENTS_BASE_URL: "/backend",   // FullPayments: /api/stripe/v4/...
  HOSTED_API_BASE_URL: "/backend",     // jBilling hosted API: /processPayment, /updatePaymentInstrument, ...
  SPIKE_BASE_URL: "/backend/spike",    // spike-only helper endpoints
};
