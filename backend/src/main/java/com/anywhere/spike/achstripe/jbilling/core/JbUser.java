package com.anywhere.spike.achstripe.jbilling.core;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;

/** Simulated jBilling user (UserWS) – just what the payment flows need. */
@Entity
@Table(name = "jb_user")
public class JbUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    /** "Account PIN" customer metafield – the key the UI and hosted API use. */
    @Column(unique = true, nullable = false)
    private String accountPin;

    private String brandId;
    private String email;
    private String firstName;
    private String lastName;
    private String postalCode;
    private String country;
    private String status = "Active";
    private BigDecimal owingBalance = BigDecimal.ZERO;

    protected JbUser() {}

    public JbUser(
            String accountPin,
            String brandId,
            String email,
            String firstName,
            String lastName,
            String postalCode,
            String country,
            BigDecimal owingBalance) {
        this.accountPin = accountPin;
        this.brandId = brandId;
        this.email = email;
        this.firstName = firstName;
        this.lastName = lastName;
        this.postalCode = postalCode;
        this.country = country;
        this.owingBalance = owingBalance;
    }

    public Integer getId() {
        return id;
    }

    public String getAccountPin() {
        return accountPin;
    }

    public String getBrandId() {
        return brandId;
    }

    public String getEmail() {
        return email;
    }

    public String getFirstName() {
        return firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public String getPostalCode() {
        return postalCode;
    }

    public String getCountry() {
        return country;
    }

    public String getStatus() {
        return status;
    }

    public BigDecimal getOwingBalance() {
        return owingBalance;
    }

    public void setOwingBalance(BigDecimal owingBalance) {
        this.owingBalance = owingBalance;
    }
}
