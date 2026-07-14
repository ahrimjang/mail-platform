package io.github.ahrimjang.mail.core.service;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AbVariantAssignerTest {

    @Test
    void assign_isDeterministicPerEmailAndCaseInsensitive() {
        for (int i = 0; i < 100; i++) {
            String email = "user" + i + "@example.com";
            String first = AbVariantAssigner.assign(email, 50);
            // Same email always lands on the same variant — retries can't flip anyone.
            assertThat(AbVariantAssigner.assign(email, 50)).isEqualTo(first);
            assertThat(AbVariantAssigner.assign(email.toUpperCase(), 50)).isEqualTo(first);
        }
    }

    @Test
    void assign_atFiftySplit_dividesRecipientsRoughlyInHalf() {
        int b = 0;
        for (int i = 0; i < 1000; i++) {
            if ("B".equals(AbVariantAssigner.assign("user" + i + "@example.com", 50))) {
                b++;
            }
        }
        // Hash buckets are not perfectly uniform — accept a sane band around 50%.
        assertThat(b).isBetween(400, 600);
    }

    @Test
    void assign_returnsOnlyAOrBAcrossTheFullSplitRange() {
        Set<String> seen = new HashSet<>();
        for (int split = 1; split <= 99; split += 7) {
            for (int i = 0; i < 50; i++) {
                seen.add(AbVariantAssigner.assign("user" + i + "@example.com", split));
            }
        }
        assertThat(seen).containsExactlyInAnyOrder("A", "B");
    }

    @Test
    void assignWithHoldout_isDeterministicPerEmail() {
        for (int i = 0; i < 100; i++) {
            String email = "user" + i + "@example.com";
            String first = AbVariantAssigner.assignWithHoldout(email, 20, 50);
            // Same email always lands on the same bucket — retries can't flip anyone.
            assertThat(AbVariantAssigner.assignWithHoldout(email, 20, 50)).isEqualTo(first);
            assertThat(AbVariantAssigner.assignWithHoldout(email.toUpperCase(), 20, 50)).isEqualTo(first);
        }
    }

    @Test
    void assignWithHoldout_atTwentyPercentTest_holdsBackRoughlyEightyPercent() {
        int held = 0;
        int a = 0;
        int b = 0;
        for (int i = 0; i < 1000; i++) {
            String variant = AbVariantAssigner.assignWithHoldout("user" + i + "@example.com", 20, 50);
            if (variant == null) {
                held++;
            } else if ("A".equals(variant)) {
                a++;
            } else {
                b++;
            }
        }
        // Hash buckets are not perfectly uniform — accept a sane band around 80/20.
        assertThat(held).isBetween(700, 900);
        assertThat(a + b).isBetween(100, 300);
        assertThat(a).isPositive();
        assertThat(b).isPositive();
    }

    @Test
    void assignWithHoldout_atNinetyPercentTest_producesBothVariants() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            String variant = AbVariantAssigner.assignWithHoldout("user" + i + "@example.com", 90, 50);
            if (variant != null) {
                seen.add(variant);
            }
        }
        assertThat(seen).containsExactlyInAnyOrder("A", "B");
    }
}
