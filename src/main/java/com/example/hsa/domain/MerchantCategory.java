package com.example.hsa.domain;

/**
 * Simplified stand-in for card-network merchant category codes (MCCs).
 * Whether a category qualifies is decided in {@link QualifiedExpensePolicy}, not here.
 */
public enum MerchantCategory {
    PHARMACY,
    HOSPITAL,
    DOCTOR,
    DENTAL,
    VISION,
    RESTAURANT,
    ELECTRONICS,
    GROCERY,
    CLOTHING
}
