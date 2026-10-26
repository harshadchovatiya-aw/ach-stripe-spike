package com.anywhere.spike.achstripe.fullpayments.intent;

import com.anywhere.spike.achstripe.common.SpikeProperties;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.SetupIntentCreateParams;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * ACH (P1/P2): adds the us_bank_account options to platform Setup/Payment intents, only for business ids in
 * spike.ach.enabled-business-ids (P0 brand gating).
 *
 * <p>Uses putExtraParam with the form-encoded keys – the same technique jBilling_hosted_api StripeHelper uses for
 * acss_debit mandate options.
 */
@Component
public class AchIntentOptions {

    private static final String US_BANK_ACCOUNT = "payment_method_options[us_bank_account]";

    private final SpikeProperties properties;

    public AchIntentOptions(SpikeProperties properties) {
        this.properties = properties;
    }

    public boolean isEnabled(String businessId) {
        return properties.isAchEnabled(businessId);
    }

    /** P1 – setup intents: verification method + Financial Connections permissions. */
    public void apply(SetupIntentCreateParams.Builder builder) {
        builder.putExtraParam(US_BANK_ACCOUNT + "[verification_method]", verificationMethod());
        List<String> permissions = permissions();
        for (int i = 0; i < permissions.size(); i++) {
            builder.putExtraParam(US_BANK_ACCOUNT + "[financial_connections][permissions][" + i + "]", permissions.get(i));
        }
    }

    /**
     * P2 – payment intents. us_bank_account does not support manual capture, so the caller sets the top-level
     * capture_method=automatic; cards keep auth-then-capture through payment_method_options[card][capture_method].
     */
    public void apply(PaymentIntentCreateParams.Builder builder) {
        builder.putExtraParam("payment_method_options[card][capture_method]", "manual");
        builder.putExtraParam(US_BANK_ACCOUNT + "[verification_method]", verificationMethod());
        List<String> permissions = permissions();
        for (int i = 0; i < permissions.size(); i++) {
            builder.putExtraParam(US_BANK_ACCOUNT + "[financial_connections][permissions][" + i + "]", permissions.get(i));
        }
    }

    private String verificationMethod() {
        String method = properties.ach().verificationMethod();
        return method == null || method.isBlank() ? "instant" : method.trim().toLowerCase();
    }

    private List<String> permissions() {
        List<String> permissions = properties.ach().financialConnectionsPermissions();
        return permissions == null || permissions.isEmpty() ? List.of("payment_method") : permissions;
    }
}
