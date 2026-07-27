package io.github.ahrimjang.mail.api;

import io.github.ahrimjang.mail.common.PlanView;
import io.github.ahrimjang.mail.core.domain.Plan;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;

/**
 * 공개 요금제 목록 — 가입 전 방문자도 보는 페이지의 데이터라 인증이 없다
 * (SecurityConfig permitAll). 단일 출처는 Plan enum.
 */
@RestController
public class PlanController {

    @GetMapping("/api/plans")
    public List<PlanView> plans() {
        return Arrays.stream(Plan.values())
                .map(p -> new PlanView(p.name(), p.monthlyPriceKrw(), p.monthlySendLimit(),
                        p.contactLimit(), p.memberLimit(), p.sendRateCap()))
                .toList();
    }
}
