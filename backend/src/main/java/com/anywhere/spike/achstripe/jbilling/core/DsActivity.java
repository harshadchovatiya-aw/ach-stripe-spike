package com.anywhere.spike.achstripe.jbilling.core;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Stand-in for DS (our CRM): account notes (DSDraft.writeNoteUnderAccount) and tasks (DSDraft.createTaskInDS).
 * ACH: a failed ACH payment will create a TASK here, like TransactionSettlementDeclinedHandler does for Braintree.
 */
@Entity
@Table(name = "ds_activity")
public class DsActivity {

    public static final String NOTE = "NOTE";
    public static final String TASK = "TASK";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    private String accountPin;
    private String kind;
    private String title;

    @Column(length = 4000)
    private String text;

    private Instant createdAt = Instant.now();

    protected DsActivity() {}

    public DsActivity(String accountPin, String kind, String title, String text) {
        this.accountPin = accountPin;
        this.kind = kind;
        this.title = title;
        this.text = text;
    }

    public Integer getId() {
        return id;
    }

    public String getAccountPin() {
        return accountPin;
    }

    public String getKind() {
        return kind;
    }

    public String getTitle() {
        return title;
    }

    public String getText() {
        return text;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
