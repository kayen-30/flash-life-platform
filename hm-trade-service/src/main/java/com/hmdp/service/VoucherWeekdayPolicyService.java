package com.hmdp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.api.dto.VoucherSummaryDTO.WeekdayCheck;
import com.hmdp.entity.Voucher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.*;

/** 交易域离线发布的星期政策；在线请求只核验版本与事实，不把自由文本解析成权威规则。 */
@Service
public class VoucherWeekdayPolicyService {
    private final Map<Long, Policy> policies;

    /** 未审核样本保持DRAFT；确认后的数据随交易服务版本发布，缺失或变更一律未知。 */
    public VoucherWeekdayPolicyService(@Value("classpath:policies/voucher-weekdays.json") Resource resource) throws IOException {
        Catalog catalog;
        try (var stream = resource.getInputStream()) { catalog = new ObjectMapper().readValue(stream, Catalog.class); }
        if (catalog.catalogVersion() == null || catalog.catalogVersion().isBlank() || catalog.policies() == null) {
            throw new IllegalArgumentException("星期政策目录无有效版本");
        }
        Map<Long, Policy> checked = new HashMap<>();
        for (Policy policy : catalog.policies()) {
            if (policy.voucherId() == null || policy.voucherId() <= 0 || policy.shopId() == null || policy.shopId() <= 0
                    || policy.policyVersion() == null || policy.policyVersion().isBlank() || policy.confirmation() == null
                    || policy.rulesSha256() == null || !policy.rulesSha256().matches("[a-f0-9]{64}")
                    || policy.allowedWeekdays() == null || policy.allowedWeekdays().isEmpty()
                    || policy.allowedWeekdays().stream().anyMatch(day -> day == null || day < 1 || day > 7)
                    || new HashSet<>(policy.allowedWeekdays()).size() != policy.allowedWeekdays().size()) {
                throw new IllegalArgumentException("星期政策字段无效");
            }
            if (policy.confirmation() == Confirmation.CONFIRMED) {
                if (policy.confirmedBy() == null || policy.confirmedBy().isBlank() || policy.confirmedAt() == null) {
                    throw new IllegalArgumentException("已发布星期政策缺少确认记录");
                }
                OffsetDateTime.parse(policy.confirmedAt());
            }
            Policy immutable = new Policy(policy.voucherId(), policy.shopId(), policy.policyVersion(), policy.confirmation(),
                    policy.confirmedBy(), policy.confirmedAt(), List.copyOf(policy.allowedWeekdays()), policy.rulesSha256());
            if (checked.putIfAbsent(policy.voucherId(), immutable) != null) throw new IllegalArgumentException("同券存在多个当前政策");
        }
        policies = Map.copyOf(checked);
    }

    /** 星期是ISO周一1至周日7；结论只覆盖这一条件，不保证购买或最终核销。 */
    public WeekdayCheck evaluate(Voucher voucher, Integer useWeekday) {
        WeekdayCheck result = new WeekdayCheck();
        result.setRequestedWeekday(useWeekday);
        result.setVerdict("UNKNOWN");
        if (useWeekday == null || useWeekday < 1 || useWeekday > 7) {
            result.setReason(useWeekday == null ? "USE_WEEKDAY_REQUIRED" : "INVALID_USE_WEEKDAY"); return result;
        }
        Policy policy = policies.get(voucher.getId());
        if (policy != null) result.setPolicyVersion(policy.policyVersion());
        if (policy == null || policy.confirmation() != Confirmation.CONFIRMED) {
            result.setReason("WEEKDAY_POLICY_UNCONFIRMED"); return result;
        }
        if (!policy.shopId().equals(voucher.getShopId()) || voucher.getRules() == null
                || !policy.rulesSha256().equals(sha256(voucher.getRules()))) {
            result.setReason("WEEKDAY_POLICY_SOURCE_CHANGED"); return result;
        }
        result.setAllowedWeekdays(policy.allowedWeekdays());
        boolean allowed = policy.allowedWeekdays().contains(useWeekday);
        result.setVerdict(allowed ? "MATCH" : "CONFLICT");
        result.setReason(allowed ? "USE_WEEKDAY_ALLOWED" : "USE_WEEKDAY_EXCLUDED");
        return result;
    }

    static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    public enum Confirmation { DRAFT, CONFIRMED }
    public record Catalog(String catalogVersion, List<Policy> policies) { }
    public record Policy(Long voucherId, Long shopId, String policyVersion, Confirmation confirmation,
                         String confirmedBy, String confirmedAt, List<Integer> allowedWeekdays, String rulesSha256) { }
}
