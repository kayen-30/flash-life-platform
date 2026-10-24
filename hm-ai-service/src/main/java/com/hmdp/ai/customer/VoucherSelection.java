package com.hmdp.ai.customer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.agent.CustomerAgent;
import com.hmdp.ai.security.AiPrivacySanitizer;
import com.hmdp.api.dto.VoucherSummaryDTO;
import lombok.extern.slf4j.Slf4j;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Pattern;

/** 选券使用交易域确认的星期结论；其他文字规则仍是待确认的模型建议。 */
@Slf4j
public final class VoucherSelection {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter OBSERVATION_TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss XXX")
            .withZone(ZoneId.of("Asia/Shanghai"));
    private static final Pattern BUY_BUDGET = Pattern.compile(
            "(?:买券|购券|券购买|购买优惠券|优惠券购买|优惠券|券)预算\\s*(?:为|是|不超过|最多|[:：])?\\s*(\\d+(?:\\.\\d{1,2})?)\\s*元");
    private static final Pattern USAGE_QUOTE = Pattern.compile("周|星期|礼拜|工作日|节假日|午|晚|核销|使用|用券|人数|每人|同桌|一桌|叠加|找零|预约|堂食|外卖|到店|用餐");
    private static final Pattern PEOPLE = Pattern.compile("(?:\\d+|[一二两三四五六七八九十]+)\\s*(?:人|位|名)|人数|每人|人均|成人|儿童|大人|小孩|单人|双人|多人|几人");
    private final String message;
    private final Clock clock;
    private final Map<Long, Voucher> vouchers = new LinkedHashMap<>();
    private final Set<Long> inspected = new HashSet<>();
    private boolean loaded;
    private boolean limited;
    private CustomerTask.Constraints constraints;
    private Instant observedAt;

    VoucherSelection(String message) { this(message, Clock.systemUTC()); }
    VoucherSelection(String message, Clock clock) { this.message = message == null ? "" : message; this.clock = clock; }
    public void constraints(CustomerTask.Constraints constraints) { this.constraints = constraints; }
    public Integer useWeekday() { return constraints == null ? null : constraints.weekday(); }
    public List<Long> voucherIds() { return List.copyOf(vouchers.keySet()); }
    public boolean hasSnapshot() { return loaded; }
    public boolean limited() { return limited; }
    public Instant observedAt() { return observedAt; }

    /** 改店或重新查询立即丢弃旧快照，禁止跨店或历史券混入选择。 */
    public void clear() { vouchers.clear(); inspected.clear(); loaded = false; limited = false; observedAt = null; }

    /** 快照复制金额、状态和脱敏规则，不保留可被后续修改的远程DTO引用。 */
    public void capture(List<VoucherSummaryDTO> results, AiPrivacySanitizer privacy) {
        clear();
        if (results == null) return;
        Map<Long, Voucher> prepared = new LinkedHashMap<>();
        for (VoucherSummaryDTO dto : results.stream().limit(5).toList()) {
            if (dto == null || dto.getId() == null || prepared.containsKey(dto.getId())) throw invalid();
            String rules = privacy.sanitizeForModel(Objects.toString(dto.getRules(), "")).strip();
            boolean complete = rules.codePointCount(0, rules.length()) <= 1000;
            prepared.put(dto.getId(), new Voucher(dto.getId(), dto.getPayValue(), dto.getActualValue(),
                    dto.getStock(), dto.getAvailability(), privacy.limit(rules, 1000), complete, snapshot(dto.getWeekdayCheck())));
        }
        // 只有完整批次校验成功才记录获取时点，失败不能留下部分券或伪造的有效快照时间。
        Instant capturedAt = clock.instant();
        vouchers.putAll(prepared);
        limited = results.size() > 5;
        observedAt = capturedAt;
        loaded = true;
    }

    /** 完整规则仅从已查询的本店快照读取；此工具不接受任意券ID远程扩查。 */
    public String inspect(List<Long> ids) {
        if (!loaded || ids == null || ids.isEmpty() || ids.size() > 5 || ids.stream().anyMatch(Objects::isNull)
                || new HashSet<>(ids).size() != ids.size() || !vouchers.keySet().containsAll(ids)) throw invalid();
        inspected.addAll(ids);
        Long budget = budgetFen();
        // 明确给出两个事实维度，模型不需要把价格和库存再解释成商家使用条件。
        var rows = ids.stream().map(vouchers::get).map(voucher -> {
            com.fasterxml.jackson.databind.node.ObjectNode row = JSON.valueToTree(voucher);
            row.put("purchaseCheck", purchasable(voucher) ? "IN_STOCK_AT_QUERY" : "UNAVAILABLE_OR_UNKNOWN");
            row.put("budgetCheck", budget == null ? "UNSPECIFIED" : voucher.payValue() == null ? "UNKNOWN"
                    : voucher.payValue() <= budget ? "WITHIN_BUDGET" : "OVER_BUDGET");
            return row;
        }).toList();
        try { return JSON.writeValueAsString(rows); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("券规则序列化失败", exception); }
    }

    private static WeekdayDecision snapshot(VoucherSummaryDTO.WeekdayCheck check) {
        return check == null ? null : new WeekdayDecision(check.getRequestedWeekday(), check.getVerdict(), check.getReason(),
                check.getPolicyVersion(), check.getAllowedWeekdays() == null ? List.of() : List.copyOf(check.getAllowedWeekdays()));
    }

    /** 有明确星期时只消费交易域结论；草稿、旧券、规则变更及未检查的快照均保持未知。 */
    public CustomerAgent.Selection weekdayDecision() {
        if (!loaded || useWeekday() == null) return null;
        List<CustomerAgent.ConditionCheck> checks = new ArrayList<>();
        for (Voucher voucher : vouchers.values()) {
            WeekdayDecision day = voucher.weekdayCheck();
            boolean trusted = day != null && Objects.equals(useWeekday(), day.requestedWeekday())
                    && day.policyVersion() != null && !day.policyVersion().isBlank() && !day.allowedWeekdays().isEmpty()
                    && (("MATCH".equals(day.verdict()) && "USE_WEEKDAY_ALLOWED".equals(day.reason()))
                    || ("CONFLICT".equals(day.verdict()) && "USE_WEEKDAY_EXCLUDED".equals(day.reason())))
                    && voucher.rulesComplete() && inspected.contains(voucher.id());
            String verdict = trusted ? day.verdict() : "UNKNOWN";
            // 首版政策只覆盖星期；支持星期不能冒充已确认人数、叠加等其他条件。
            if ("MATCH".equals(verdict) && !constraints.usageQuotes().isEmpty()) verdict = "UNKNOWN";
            checks.add(new CustomerAgent.ConditionCheck(voucher.id(), verdict, trusted ? voucher.rules() : "", constraints.dayQuote()));
        }
        return new CustomerAgent.Selection(candidate(checks), List.copyOf(checks));
    }

    /** 模型不能改写已有星期结论；金额、预算、库存及其余引用仍经过同一门禁。 */
    public CustomerAgent.Selection validated(CustomerAgent.Selection selection) {
        CustomerAgent.Selection controlled = weekdayDecision();
        if (controlled != null) selection = controlled;
        if (!loaded || selection == null || selection.checks() == null || selection.checks().size() != vouchers.size()) throw invalid("selection_shape");
        Map<Long, CustomerAgent.ConditionCheck> checks = new LinkedHashMap<>();
        for (CustomerAgent.ConditionCheck check : selection.checks()) {
            if (check == null || check.voucherId() == null || checks.put(check.voucherId(), check) != null
                    || !vouchers.containsKey(check.voucherId())
                    || !Set.of("MATCH", "CONFLICT", "UNKNOWN").contains(Objects.toString(check.verdict(), ""))) throw invalid("check_id_or_verdict");
            Voucher voucher = vouchers.get(check.voucherId());
            String rule = Objects.toString(check.ruleQuote(), "");
            String user = Objects.toString(check.userQuote(), "");
            // 引用存在性不等于语义蕴含；展示时明确这仍是条件核对建议。
            if (!rule.isEmpty() && (!inspected.contains(voucher.id()) || !voucher.rules().contains(rule))) throw invalid("rule_quote_mismatch");
            if (!user.isEmpty() && !message.contains(user)
                    && (constraints == null || constraints.evidence().stream().noneMatch(value -> value.contains(user)))) throw invalid("user_quote_mismatch");
            if (rule.length() > 1000 || user.length() > 200) throw invalid("quote_length");
            if (!"UNKNOWN".equals(check.verdict()) && (!inspected.contains(voucher.id()) || !voucher.rulesComplete()
                    || rule.isBlank() || user.isBlank())) throw invalid("missing_complete_rule_or_quote");
            if (Objects.equals(selection.candidateVoucherId(), check.voucherId()) && "CONFLICT".equals(check.verdict())) {
                throw invalid("candidate_conflict");
            }
            boolean peopleCondition = PEOPLE.matcher(user).find();
            // 只能降为未知，不能凭预算或“逐张核对”等指令生成使用条件结论；原始引用先经过上面的严格检查。
            if (!"UNKNOWN".equals(check.verdict()) && !USAGE_QUOTE.matcher(user).find() && !peopleCondition) {
                checks.put(check.voucherId(), new CustomerAgent.ConditionCheck(check.voucherId(), "UNKNOWN", "", ""));
                logNormalization(check.voucherId(), "non_usage_quote");
            } else if (!"UNKNOWN".equals(check.verdict()) && peopleCondition && !PEOPLE.matcher(rule).find()) {
                // 引用存在不代表涉及同一条件；星期或每桌限券规则不能支撑人数结论。
                checks.put(check.voucherId(), new CustomerAgent.ConditionCheck(check.voucherId(), "UNKNOWN", rule, user));
                logNormalization(check.voucherId(), "people_rule_evidence_missing");
            }
        }
        Long budget = budgetFen();
        Long candidate = selection.candidateVoucherId();
        if (candidate != null) {
            Voucher voucher = vouchers.get(candidate);
            if (voucher == null || !inspected.contains(candidate)) throw invalid("candidate_not_inspected");
            if (!purchasable(voucher)) throw invalid("candidate_unavailable");
            if (!voucher.rulesComplete()) throw invalid("candidate_rules_incomplete");
            if (budget != null && voucher.payValue() > budget) throw invalid("candidate_over_budget");
            if ("CONFLICT".equals(checks.get(candidate).verdict())) throw invalid("candidate_conflict");
        }
        Long ranked = candidate(checks.values());
        if (!Objects.equals(candidate, ranked)) logNormalization(candidate, "candidate_ranked_by_code");
        return new CustomerAgent.Selection(ranked, List.copyOf(checks.values()));
    }

    /** 金额排序只消费本轮已检查的快照；模型候选不能决定目标、打破同额顺序或放宽未知策略。 */
    private Long candidate(Collection<CustomerAgent.ConditionCheck> checks) {
        if (constraints != null && (constraints.selectionGoal() == CustomerTask.SelectionGoal.UNSUPPORTED
                || constraints.unknownPolicy() == CustomerTask.UnknownPolicy.UNSUPPORTED)) throw invalid("selection_constraints_unresolved");
        boolean hasConditions = constraints != null && (useWeekday() != null || !constraints.usageQuotes().isEmpty());
        if (!hasConditions) hasConditions = checks.stream().map(CustomerAgent.ConditionCheck::userQuote)
                .filter(Objects::nonNull).anyMatch(value -> USAGE_QUOTE.matcher(value).find() || PEOPLE.matcher(value).find());
        // 只有预算、排序或候选偏好时仍先询问使用条件，不凭空制造可推荐资格。
        if (!hasConditions) return null;
        boolean allowUnknown = allowsUnknownCandidates();
        Set<Long> eligible = checks.stream().filter(check -> "MATCH".equals(check.verdict())
                || (allowUnknown && "UNKNOWN".equals(check.verdict())))
                .map(CustomerAgent.ConditionCheck::voucherId).collect(java.util.stream.Collectors.toSet());
        Long budget = budgetFen();
        Comparator<Voucher> order = constraints != null && constraints.selectionGoal() == CustomerTask.SelectionGoal.HIGHEST_DEDUCTION
                ? Comparator.comparingLong((Voucher v) -> v.actualValue()).reversed().thenComparingLong(Voucher::payValue).thenComparing(Voucher::id)
                : Comparator.comparingLong((Voucher v) -> v.payValue()).thenComparing(Voucher::id);
        return vouchers.values().stream().filter(this::purchasable)
                .filter(voucher -> inspected.contains(voucher.id()) && voucher.rulesComplete() && eligible.contains(voucher.id()))
                .filter(voucher -> budget == null || voucher.payValue() <= budget)
                .min(order).map(Voucher::id).orElse(null);
    }

    /** 没有提出使用条件时仅交付真实价格库存，不让模型编造用于核对的条件或引用。 */
    public CustomerAgent.Selection unspecifiedConditions() {
        if (!loaded) return null;
        return new CustomerAgent.Selection(null, vouchers.keySet().stream()
                .map(id -> new CustomerAgent.ConditionCheck(id, "UNKNOWN", "", "")).toList());
    }

    /** 展示经过验证的同一结构，HTTP字段和正文不保留被降级的模型结论。 */
    public String render(CustomerAgent.Selection selection) {
        selection = validated(selection);
        Map<Long, CustomerAgent.ConditionCheck> checks = new LinkedHashMap<>();
        selection.checks().forEach(check -> checks.put(check.voucherId(), check));
        Long budget = budgetFen();
        Long candidate = selection.candidateVoucherId();
        List<String> lines = new ArrayList<>();
        lines.add("数据获取时间：" + OBSERVATION_TIME.format(observedAt) + "（本轮查询结果）。");
        lines.add("选券范围：本次查询展示的" + vouchers.size() + "张券" + (limited ? "（未覆盖全部券）" : "") + "。");
        lines.add(budget == null ? "买券预算：未识别明确金额，未核验预算；用餐预算不等于买券预算。"
                : "买券预算：" + money(budget) + "；只核对券的支付金额。");
        if (constraints != null && constraints.unknownPolicy() != CustomerTask.UnknownPolicy.DEFAULT) {
            lines.add("未知条件候选：" + (constraints.unknownPolicy() == CustomerTask.UnknownPolicy.EXCLUDE ? "不参与选择" : "允许作为待确认建议")
                    + "；你的要求『" + constraints.unknownPolicyQuote() + "』"
                    + (message.contains(constraints.unknownPolicyQuote()) ? "。" : "（已确认前轮偏好）。"));
        }
        // 已核对后没有候选与适用条件未知分别说明，避免把完成核对误写成缺用户输入。
        String noCandidate = selection.checks().stream().anyMatch(check -> "UNKNOWN".equals(check.verdict()))
                ? "暂不选择，需补充条件或确认限制。" : "暂不选择，本次已核对条件下没有可建议的候选券，原因见下方逐券核对。";
        lines.add(candidate == null ? "候选建议：" + noCandidate
                : "候选建议：优先核对优惠券ID：" + candidate + "，支付金额：" + money(vouchers.get(candidate).payValue())
                    + "，抵扣金额：" + money(vouchers.get(candidate).actualValue()) + "。这是待确认的候选。");
        for (Voucher voucher : vouchers.values()) {
            CustomerAgent.ConditionCheck check = checks.get(voucher.id());
            String status = !purchasable(voucher) ? "状态或金额不足以支持购买"
                    : budget != null && voucher.payValue() > budget ? "超过买券预算" : "金额及查询时库存已核对";
            String verdict = switch (check.verdict()) {
                case "MATCH" -> useWeekday() == null ? "模型判断支持该条件" : "交易域确认该星期适用";
                case "CONFLICT" -> useWeekday() == null ? "模型判断存在冲突" : "交易域确认该星期不适用";
                default -> "适用条件待确认";
            };
            String policy = "";
            if (useWeekday() != null) {
                WeekdayDecision day = voucher.weekdayCheck();
                policy = day == null ? "；缺少已确认的星期政策" : "；星期核对："
                        + (!Objects.equals(useWeekday(), day.requestedWeekday()) ? "查询条件不一致，待重新核实"
                        : "USE_WEEKDAY_ALLOWED".equals(day.reason()) ? "星期符合" : "USE_WEEKDAY_EXCLUDED".equals(day.reason()) ? "星期冲突"
                        : "WEEKDAY_POLICY_SOURCE_CHANGED".equals(day.reason()) ? "原规则已变更，需重新确认" : "政策尚未确认")
                        + (day.policyVersion() == null ? "" : "（参考版本：" + day.policyVersion() + "）")
                        + (!constraints.usageQuotes().isEmpty() ? "，其他使用条件待确认" : "");
            }
            lines.add("条件核对券ID：" + voucher.id() + "，" + status + "，" + verdict
                    + (check.userQuote() == null || check.userQuote().isBlank() ? "" : "；用户条件『" + check.userQuote() + "』"
                        + (!message.contains(check.userQuote()) ? "（已确认前轮条件）" : ""))
                    + (check.ruleQuote() == null || check.ruleQuote().isBlank() ? "" : "；商家原文『" + check.ruleQuote() + "』")
                    + policy + (!voucher.rulesComplete() ? "；规则超出读取上限，不能确认完整适用条件" : "")
                    + "；选择结果：" + selectionReason(voucher, check, candidate, budget));
        }
        if (useWeekday() != null) lines.add("候选按本次展示范围内、已核对的星期条件与买券预算选择"
                + (constraints.selectionGoal() == CustomerTask.SelectionGoal.HIGHEST_DEDUCTION ? "抵扣金额最多的券，同额时优先支付更少" : "支付金额最少的券")
                + "；星期政策不覆盖其他使用限制，也不保证购买或核销成功。");
        else lines.add("候选按本次展示范围内、已读取完整规则且金额和查询时库存符合要求的券选择"
                + (constraints != null && constraints.selectionGoal() == CustomerTask.SelectionGoal.HIGHEST_DEDUCTION
                ? "抵扣金额最多的券，同额时优先支付更少" : "支付金额最少的券")
                + "；商家规则的解释仍需确认，活动时间不代表核销有效期，不保证可核销或购买成功，最终以商家规则和提交订单校验为准。");
        return String.join("\n", lines);
    }

    /** 解释代码已经采用的筛选和排序，不让模型补写未入选理由。 */
    private String selectionReason(Voucher voucher, CustomerAgent.ConditionCheck check, Long candidate, Long budget) {
        if (Objects.equals(voucher.id(), candidate)) return "本次建议候选";
        if (!purchasable(voucher)) return voucher.stock() != null && voucher.stock() <= 0 ? "未入选，已售罄" : "未入选，购买状态或金额无法确认";
        if (budget != null && voucher.payValue() > budget) return "未入选，超过买券预算";
        if (!inspected.contains(voucher.id()) || !voucher.rulesComplete()) return "未入选，完整规则尚未核实";
        if ("CONFLICT".equals(check.verdict())) return useWeekday() != null ? "未入选，星期条件不符" : "未入选，条件核对显示冲突";
        if ("UNKNOWN".equals(check.verdict()) && !allowsUnknownCandidates()) return "未入选，使用条件尚未确认";
        if (candidate == null) return "缺少可确认的使用条件，暂不推荐";
        Voucher chosen = vouchers.get(candidate);
        if (constraints != null && constraints.selectionGoal() == CustomerTask.SelectionGoal.HIGHEST_DEDUCTION) {
            if (voucher.actualValue() < chosen.actualValue()) return "未入选，抵扣金额低于本次候选";
            if (voucher.payValue() > chosen.payValue()) return "未入选，同额抵扣时支付金额较高";
        } else if (voucher.payValue() > chosen.payValue()) return "未入选，支付金额高于本次候选";
        return "未入选，与候选金额相同，按固定顺序选择";
    }

    private boolean allowsUnknownCandidates() {
        CustomerTask.UnknownPolicy policy = constraints == null ? CustomerTask.UnknownPolicy.DEFAULT : constraints.unknownPolicy();
        return policy == CustomerTask.UnknownPolicy.ALLOW_PENDING || (policy == CustomerTask.UnknownPolicy.DEFAULT && useWeekday() == null);
    }

    private Long budgetFen() {
        if (constraints != null) return constraints.buyBudgetFen();
        var match = BUY_BUDGET.matcher(message);
        if (!match.find()) return null;
        try {
            long amount = new BigDecimal(match.group(1)).movePointRight(2).longValueExact();
            // 多个预算存在歧义时不擅自挑一个，交给澄清流程。
            return match.find() ? null : amount;
        } catch (ArithmeticException invalid) { return null; }
    }

    /** 归一化原因与本轮请求关联，不把无法支撑条件的引用当作资格结论。 */
    private static void logNormalization(Long voucherId, String reason) {
        var budget = com.hmdp.ai.execution.AiExecutionBudget.current();
        log.info("AI_SELECTION_CHECK_NORMALIZED requestId={}, voucherId={}, reason={}",
                budget == null ? "none" : budget.getRequestId(), voucherId, reason);
    }

    private boolean purchasable(Voucher voucher) {
        return "IN_STOCK_AT_QUERY".equals(voucher.availability()) && voucher.stock() != null && voucher.stock() > 0
                && voucher.payValue() != null && voucher.payValue() >= 0
                && voucher.actualValue() != null && voucher.actualValue() > 0;
    }

    private static String money(Long fen) { return BigDecimal.valueOf(fen, 2).toPlainString() + "元"; }
    private static AiBusinessFacts.EvidenceUnavailableException invalid() { return new AiBusinessFacts.EvidenceUnavailableException(); }
    private static AiBusinessFacts.EvidenceUnavailableException invalid(String reason) { return new AiBusinessFacts.EvidenceUnavailableException(reason); }

    public record Voucher(Long id, Long payValue, Long actualValue, Integer stock, String availability,
                          String rules, boolean rulesComplete, WeekdayDecision weekdayCheck) { }
    public record WeekdayDecision(Integer requestedWeekday, String verdict, String reason, String policyVersion,
                                  List<Integer> allowedWeekdays) { }
}
