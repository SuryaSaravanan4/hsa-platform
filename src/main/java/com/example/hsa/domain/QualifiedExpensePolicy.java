package com.example.hsa.domain;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.EnumSet;
import java.util.Set;

/**
 * The single place that decides which merchant categories count as qualified medical expenses.
 * To add or remove a qualified category, change {@link #QUALIFIED} and nothing else.
 */
@ApplicationScoped
public class QualifiedExpensePolicy {

    private static final Set<MerchantCategory> QUALIFIED = EnumSet.of(
            MerchantCategory.PHARMACY,
            MerchantCategory.HOSPITAL,
            MerchantCategory.DOCTOR,
            MerchantCategory.DENTAL,
            MerchantCategory.VISION);

    public boolean isQualified(MerchantCategory category) {
        return category != null && QUALIFIED.contains(category);
    }
}
