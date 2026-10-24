package com.hmdp.controller;

import com.hmdp.entity.Voucher;
import com.hmdp.service.IVoucherService;
import com.hmdp.service.VoucherWeekdayPolicyService;
import org.springframework.core.io.ByteArrayResource;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.LocalDateTime;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class InternalVoucherControllerTest {
    private final LocalDateTime now = LocalDateTime.of(2026, 10, 19, 10, 0);

    @Test
    void missingFactsNeverBecomeAvailability() {
        assertThat(status(valid().setStock(null))).isEqualTo("UNKNOWN");
        assertThat(status(valid().setStock(-1))).isEqualTo("UNKNOWN");
        assertThat(status(valid().setStatus(null))).isEqualTo("UNKNOWN");
        assertThat(status(valid().setType(null))).isEqualTo("UNKNOWN");
        assertThat(status(valid().setType(0))).isEqualTo("UNKNOWN");
        assertThat(status(valid().setBeginTime(null))).isEqualTo("UNKNOWN");
        assertThat(status(valid().setEndTime(null))).isEqualTo("UNKNOWN");
        assertThat(status(valid().setBeginTime(now.plusDays(2)))).isEqualTo("UNKNOWN");
    }

    @Test
    void unavailableStatesAndActivityBoundariesFollowTradeRules() {
        assertThat(status(valid().setStatus(2))).isEqualTo("OFF_SHELF");
        assertThat(status(valid().setStock(0))).isEqualTo("SOLD_OUT");
        assertThat(status(valid().setEndTime(now.minusSeconds(1)))).isEqualTo("EXPIRED");
        assertThat(status(valid().setBeginTime(now.plusSeconds(1)))).isEqualTo("NOT_STARTED");
        assertThat(status(valid())).isEqualTo("IN_STOCK_AT_QUERY");
        assertThat(status(valid().setBeginTime(now).setEndTime(now))).isEqualTo("IN_STOCK_AT_QUERY");
    }

    @Test
    void internalSummaryPreservesFactsAndClassifiesUnknownInventory() throws Exception {
        IVoucherService service = mock(IVoucherService.class);
        Voucher voucher = valid().setId(10L).setStock(null).setPayValue(1234L).setActualValue(5000L);
        when(service.queryVoucherListOfShop(2L)).thenReturn(List.of(voucher));
        InternalVoucherController controller = new InternalVoucherController();
        ReflectionTestUtils.setField(controller, "voucherService", service);
        ReflectionTestUtils.setField(controller, "weekdayPolicies", new VoucherWeekdayPolicyService(new ByteArrayResource(
                "{\"catalogVersion\":\"empty-test\",\"policies\":[]}".getBytes(StandardCharsets.UTF_8))));
        var dto = controller.queryShopVouchers(2L, 3).get(0);
        assertThat(dto.getStatus()).isEqualTo(1);
        assertThat(dto.getType()).isEqualTo(1);
        assertThat(dto.getBeginTime()).isEqualTo(voucher.getBeginTime());
        assertThat(dto.getEndTime()).isEqualTo(voucher.getEndTime());
        assertThat(dto.getStock()).isNull();
        assertThat(dto.getPayValue()).isEqualTo(1234L);
        assertThat(dto.getAvailability()).isNotEqualTo("IN_STOCK_AT_QUERY");
        assertThat(dto.getWeekdayCheck().getVerdict()).isEqualTo("UNKNOWN");
        assertThat(dto.getWeekdayCheck().getReason()).isEqualTo("WEEKDAY_POLICY_UNCONFIRMED");
    }

    @Test
    void confirmedWeekdaysFollowTradePolicyAndChangedSourcesBecomeUnknown() throws Exception {
        Voucher voucher = valid().setId(10L).setShopId(2L).setRules("周一至周五可用");
        var policy = policies("test-v1", true, voucher.getRules(), List.of(1,2,3,4,5));
        assertThat(policy.evaluate(voucher, 1).getVerdict()).isEqualTo("MATCH");
        assertThat(policy.evaluate(voucher, 5).getReason()).isEqualTo("USE_WEEKDAY_ALLOWED");
        assertThat(policy.evaluate(voucher, 6).getVerdict()).isEqualTo("CONFLICT");
        assertThat(policy.evaluate(voucher, 7).getPolicyVersion()).isEqualTo("test-v1");
        assertThat(policy.evaluate(voucher, 0).getReason()).isEqualTo("INVALID_USE_WEEKDAY");
        assertThat(policy.evaluate(voucher, null).getReason()).isEqualTo("USE_WEEKDAY_REQUIRED");
        assertThat(policy.evaluate(voucher.setRules("改为周末可用"), 6).getReason()).isEqualTo("WEEKDAY_POLICY_SOURCE_CHANGED");
        assertThat(policy.evaluate(voucher.setRules("周一至周五可用").setShopId(3L), 1).getVerdict()).isEqualTo("UNKNOWN");
        assertThat(policy.evaluate(voucher.setShopId(2L).setId(11L), 1).getVerdict()).isEqualTo("UNKNOWN");
    }

    @Test
    void draftNeedsRealConfirmationAndNewVersionHasItsOwnRuleFingerprint() throws Exception {
        Voucher voucher = valid().setId(10L).setShopId(2L).setRules("周一至周五可用");
        assertThat(policies("draft-v1", false, voucher.getRules(), List.of(1,2,3,4,5)).evaluate(voucher, 3).getVerdict()).isEqualTo("UNKNOWN");
        var old = policies("test-v1", true, voucher.getRules(), List.of(1,2,3,4,5));
        voucher.setRules("周一至周日均可用");
        var revised = policies("test-v2", true, voucher.getRules(), List.of(1,2,3,4,5,6,7));
        assertThat(old.evaluate(voucher, 6).getVerdict()).isEqualTo("UNKNOWN");
        assertThat(revised.evaluate(voucher, 6).getVerdict()).isEqualTo("MATCH");
        assertThat(revised.evaluate(voucher, 6).getPolicyVersion()).isEqualTo("test-v2");
        assertThatThrownBy(() -> policies("bad", true, "text", List.of(0))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policies("bad", true, "text", List.of())).isInstanceOf(IllegalArgumentException.class);
    }

    /** 单元测试的发布者明确是测试夹具，不冒充已得到商家或用户审核。 */
    private VoucherWeekdayPolicyService policies(String version, boolean confirmed, String rules, List<Integer> days) throws Exception {
        var entry = new VoucherWeekdayPolicyService.Policy(10L, 2L, version,
                confirmed ? VoucherWeekdayPolicyService.Confirmation.CONFIRMED : VoucherWeekdayPolicyService.Confirmation.DRAFT,
                confirmed ? "unit-test-fixture" : null, confirmed ? "2026-10-20T10:00:00+08:00" : null,
                days, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(rules.getBytes(StandardCharsets.UTF_8))));
        byte[] json = new ObjectMapper().writeValueAsBytes(new VoucherWeekdayPolicyService.Catalog("test", List.of(entry)));
        return new VoucherWeekdayPolicyService(new ByteArrayResource(json));
    }

    private String status(Voucher voucher) { return InternalVoucherController.availability(voucher, now); }
    private Voucher valid() {
        return new Voucher().setStatus(1).setType(1).setStock(5)
                .setBeginTime(now.minusDays(1)).setEndTime(now.plusDays(1));
    }
}
