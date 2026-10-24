package com.hmdp.ai.customer;

import com.hmdp.ai.agent.CustomerAgent;
import com.hmdp.ai.agent.tool.ToolRetryHandler;
import com.hmdp.ai.knowledge.AiKnowledgeRetrievalResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 本轮真实查询结果单独保存，优惠券事实不经过模型改写后再展示。 */
@lombok.extern.slf4j.Slf4j
public final class AiBusinessFacts implements AutoCloseable {
    private static final ThreadLocal<AiBusinessFacts> CURRENT = new ThreadLocal<>();
    private final AiBusinessFacts previous;
    private final Map<String, String> observations = new LinkedHashMap<>();
    private boolean voucherQueried;
    private boolean verifiedVouchers;
    private final VoucherSelection selection;
    private CustomerTask task;
    private final Map<CustomerTask.Capability, CustomerTask.Step> coverage = new LinkedHashMap<>();
    private String inspection;
    private final List<String> notes = new ArrayList<>();
    private FaqAnswers faqAnswers;

    private AiBusinessFacts(String message) {
        selection = new VoucherSelection(message);
        previous = CURRENT.get();
        CURRENT.set(this);
    }

    public static AiBusinessFacts open() { return open(""); }
    public static AiBusinessFacts open(String message) { return new AiBusinessFacts(message); }

    public static VoucherSelection selection() {
        AiBusinessFacts facts = CURRENT.get();
        return facts == null ? null : facts.selection;
    }

    public void task(CustomerTask task) {
        this.task = task;
        selection.constraints(task.constraints());
        faqAnswers = null;
        coverage.remove(CustomerTask.Capability.FAQ);
    }
    public boolean hasFaqTask() { return task != null && task.capabilities().contains(CustomerTask.Capability.FAQ); }

    /** 发布或权限变化时撤销整份FAQ证据，保留独立的实时查询和选券结果。 */
    public CustomerAgent.Answer withoutFaqEvidence(CustomerAgent.Answer generated, String reason) {
        if (!hasFaqTask()) throw new EvidenceUnavailableException("faq_scope_missing");
        faqAnswers(FaqAnswers.unresolved(task.faqQuestions(), CustomerTask.Status.UNKNOWN, reason));
        return answer(new CustomerAgent.Answer("", List.of(), generated.kind(), generated.selection()), null);
    }
    /** 只接受与本轮原文子问题绑定的已校验FAQ结果，模型kind和来源ID不能直接授予完成状态。 */
    public void faqAnswers(FaqAnswers answers) {
        if (task == null || !task.capabilities().contains(CustomerTask.Capability.FAQ)
                || (!task.faqQuestions().isEmpty() && !task.faqQuestions().equals(answers.questions()))
                || (task.faqQuestions().isEmpty() && (!answers.sources().isEmpty() || !answers.questions().equals(List.of(""))))) {
            throw new EvidenceUnavailableException("faq_scope_mismatch");
        }
        faqAnswers = answers;
        taskStatus(CustomerTask.Capability.FAQ, answers.status(), answers.reason());
    }

    public static List<FaqAnswers.Item> faqItems() {
        AiBusinessFacts facts = CURRENT.get();
        return facts == null || facts.faqAnswers == null ? List.of() : facts.faqAnswers.items();
    }
    public String toolContext() {
        return String.join("\n\n", observations.values()) + (inspection == null ? "" : "\n完整规则与代码预算检查：\n" + inspection);
    }
    public void note(String message) {
        notes.add(message);
        if (task != null && !task.missing().isEmpty() && task.selects()) {
            coverage.put(CustomerTask.Capability.SELECTION, new CustomerTask.Step(CustomerTask.Capability.SELECTION,
                    CustomerTask.Status.NEEDS_CLARIFICATION, task.missing().get(0)));
        }
    }

    public boolean shopBlocked() {
        CustomerTask.Step lookup = coverage.get(CustomerTask.Capability.SHOP);
        CustomerTask.Status status = lookup != null && lookup.status() == CustomerTask.Status.FAILED
                ? CustomerTask.Status.FAILED : CustomerTask.Status.NEEDS_CLARIFICATION;
        String reason = lookup == null ? "SHOP_REQUIRED" : lookup.reason();
        if (task != null) for (CustomerTask.Capability capability : task.capabilities()) {
            if (java.util.Set.of(CustomerTask.Capability.SHOP, CustomerTask.Capability.VOUCHERS,
                    CustomerTask.Capability.SELECTION, CustomerTask.Capability.SHOP_BLOGS).contains(capability)) {
                coverage.put(capability, new CustomerTask.Step(capability, status, reason));
            }
        }
        return status == CustomerTask.Status.FAILED;
    }

    /** 状态来自实际工具分支，不从模型声称“已完成”或最终文案推测。 */
    public static void toolStatus(String tool, CustomerTask.Status status, String reason) {
        AiBusinessFacts facts = CURRENT.get();
        if (facts == null) return;
        CustomerTask.Capability capability = capability(tool);
        if (capability != null) facts.coverage.put(capability, new CustomerTask.Step(capability, status, reason));
        if (capability == CustomerTask.Capability.VOUCHERS && facts.task != null && facts.task.selects()
                && status != CustomerTask.Status.COMPLETED) {
            // 选券依赖本轮查券；查询失败不能误报为模型尚未给出建议。
            facts.taskStatus(CustomerTask.Capability.SELECTION, status, reason);
        }
    }

    /** 子任务的模型失败独立记录，不覆盖其他已核实的能力。 */
    public void taskStatus(CustomerTask.Capability capability, CustomerTask.Status status, String reason) {
        coverage.put(capability, new CustomerTask.Step(capability, status, reason));
    }

    private static CustomerTask.Capability capability(String tool) {
        return switch (tool) {
            case "query_shop_by_name", "query_shop_by_id" -> CustomerTask.Capability.SHOP;
            case "query_shop_vouchers" -> CustomerTask.Capability.VOUCHERS;
            case "query_shop_blogs" -> CustomerTask.Capability.SHOP_BLOGS;
            case "query_hot_blogs" -> CustomerTask.Capability.HOT_BLOGS;
            default -> null;
        };
    }

    public static CustomerTask.Outcome outcome(CustomerAgent.Answer answer, com.hmdp.ai.execution.AiExecutionBudget budget) {
        AiBusinessFacts facts = CURRENT.get();
        if (facts == null) return null;
        List<CustomerTask.Capability> requested = facts.task == null ? List.copyOf(facts.coverage.keySet()) : facts.task.capabilities();
        if (requested.isEmpty()) requested = List.of(CustomerTask.Capability.UNKNOWN);
        List<CustomerTask.Step> steps = new ArrayList<>();
        for (CustomerTask.Capability capability : requested) {
            CustomerTask.Step step = facts.coverage.get(capability);
            if (step == null) {
                CustomerTask.Status status = answer.kind() == CustomerAgent.Kind.CLARIFY ? CustomerTask.Status.NEEDS_CLARIFICATION
                        : capability == CustomerTask.Capability.OUT_OF_SCOPE || answer.kind() == CustomerAgent.Kind.REFUSE ? CustomerTask.Status.REFUSED
                        : CustomerTask.Status.UNKNOWN;
                step = new CustomerTask.Step(capability, status, status == CustomerTask.Status.NEEDS_CLARIFICATION ? "INPUT_REQUIRED" : "NO_VERIFIED_RESULT");
            }
            steps.add(step);
        }
        long completed = steps.stream().filter(step -> step.status() == CustomerTask.Status.COMPLETED).count();
        CustomerTask.Status status = completed == steps.size() ? CustomerTask.Status.COMPLETED
                : completed > 0 || steps.stream().anyMatch(step -> step.status() == CustomerTask.Status.PARTIAL) ? CustomerTask.Status.PARTIAL
                : steps.stream().anyMatch(step -> step.status() == CustomerTask.Status.FAILED) ? CustomerTask.Status.FAILED
                : steps.stream().anyMatch(step -> step.status() == CustomerTask.Status.NEEDS_CLARIFICATION) ? CustomerTask.Status.NEEDS_CLARIFICATION
                : steps.stream().allMatch(step -> step.status() == CustomerTask.Status.REFUSED) ? CustomerTask.Status.REFUSED : CustomerTask.Status.UNKNOWN;
        return new CustomerTask.Outcome(budget.getRequestId(), status, List.copyOf(steps),
                facts.task == null ? null : facts.task.constraints(), budget.getModelCalls());
    }

    /** 仅由真实业务执行器写入；模型文本和历史消息不能写入事实区。 */
    public static String record(String tool, String arguments, String result) {
        AiBusinessFacts facts = CURRENT.get();
        if (facts == null) return result;
        if (tool.equals("inspect_voucher_conditions")) facts.inspection = result;
        if (ToolRetryHandler.isFailureMessage(result)) toolStatus(tool, CustomerTask.Status.FAILED, "BUSINESS_SERVICE_UNAVAILABLE");
        else if (capability(tool) != null) facts.coverage.putIfAbsent(capability(tool),
                new CustomerTask.Step(capability(tool), CustomerTask.Status.COMPLETED, "QUERY_EXECUTED"));
        String label = switch (tool) {
            case "query_shop_vouchers" -> "优惠券查询结果";
            case "query_shop_by_name", "query_shop_by_id" -> "店铺查询结果";
            case "query_shop_blogs" -> "同店探店笔记";
            case "query_hot_blogs" -> "平台热门笔记";
            default -> null;
        };
        if (label != null) {
            if (tool.equals("query_shop_vouchers")) {
                facts.voucherQueried = true;
                // 只有查到确定店铺的真实结果才可支撑生成失败后的降级；澄清和异常不算。
                facts.verifiedVouchers |= result != null && result.startsWith("店铺：")
                        && !ToolRetryHandler.isFailureMessage(result);
            }
            // 对外只说明查询失败，不把Feign异常中的服务地址等实现细节展示给用户。
            String display = ToolRetryHandler.isFailureMessage(result) ? "查询暂时不可用，请稍后重试。" : result;
            facts.observations.put(tool + ":" + arguments, label + "：\n" + display);
        }
        return result;
    }

    /** FAQ只展示选中证据原文；实际工具事实独立保留，不采纳模型补写的规则。 */
    public CustomerAgent.Answer answer(CustomerAgent.Answer generated, AiKnowledgeRetrievalResult knowledge) {
        long started = System.nanoTime();
        String outcome = "rejected";
        try {
            CustomerAgent.Answer result = verifiedAnswer(generated, knowledge);
            outcome = "validated";
            return result;
        } finally {
            var budget = com.hmdp.ai.execution.AiExecutionBudget.current();
            log.info("AI_COMPONENT requestId={}, component=validation_render, durationMs={}, outcome={}",
                    budget == null ? "none" : budget.getRequestId(), (System.nanoTime() - started) / 1_000_000, outcome);
        }
    }

    private CustomerAgent.Answer verifiedAnswer(CustomerAgent.Answer generated, AiKnowledgeRetrievalResult knowledge) {
        // 任务范围由已校验输入决定；多余选券字段不参与事实展示，也不能使已完成查询整体失败。
        if (task != null && !task.selects() && generated.selection() != null) {
            generated = new CustomerAgent.Answer("", generated.sourceIds(),
                    task.capabilities().contains(CustomerTask.Capability.FAQ)
                            ? observations.isEmpty() ? CustomerAgent.Kind.FAQ : CustomerAgent.Kind.MIXED
                            : CustomerAgent.Kind.TOOL);
            var budget = com.hmdp.ai.execution.AiExecutionBudget.current();
            log.info("AI_SELECTION_CHECK_NORMALIZED requestId={}, reason=unrequested_selection_ignored",
                    budget == null ? "none" : budget.getRequestId());
        }
        // 服务端星期判断优先于模型协议，模型既不能改结论，也不能以选错kind跳过本轮核对。
        CustomerAgent.Selection controlled = task != null && task.selects() ? selection.weekdayDecision() : null;
        if (controlled != null) generated = new CustomerAgent.Answer("", generated.sourceIds(),
                task.capabilities().contains(CustomerTask.Capability.FAQ) ? CustomerAgent.Kind.MIXED : CustomerAgent.Kind.SELECTION, controlled);
        CustomerAgent.Selection checked = null;
        if (generated.selection() != null) {
            if (generated.kind() != CustomerAgent.Kind.SELECTION && generated.kind() != CustomerAgent.Kind.MIXED) throw new EvidenceUnavailableException();
            checked = selection.validated(generated.selection());
            boolean uncertain = checked.checks().stream().anyMatch(check -> check.verdict().equals("UNKNOWN"));
            coverage.put(CustomerTask.Capability.SELECTION, new CustomerTask.Step(CustomerTask.Capability.SELECTION,
                    uncertain || selection.limited() ? CustomerTask.Status.PARTIAL : CustomerTask.Status.COMPLETED,
                    selection.limited() ? "VOUCHER_SCOPE_LIMITED" : uncertain ? "USAGE_CONDITIONS_UNCONFIRMED" : controlled != null ? "TRADE_WEEKDAY_EVALUATED" : "SELECTION_EVIDENCE_VALIDATED"));
        } else if (generated.kind() == CustomerAgent.Kind.SELECTION) throw new EvidenceUnavailableException();
        if (generated.kind() == CustomerAgent.Kind.SELECTION && !generated.sourceIds().isEmpty()) throw new EvidenceUnavailableException();
        boolean faqRequested = task != null && task.capabilities().contains(CustomerTask.Capability.FAQ);
        // 没有任务范围的来源声明不能越过证据门禁；实际任务不接受模型额外添加FAQ能力。
        if (task == null && !generated.sourceIds().isEmpty()) throw new EvidenceUnavailableException("faq_scope_missing");
        if (faqRequested && faqAnswers == null) {
            faqAnswers(FaqAnswers.unresolved(task.faqQuestions(), CustomerTask.Status.UNKNOWN, "FAQ_EVIDENCE_MISSING"));
        }
        if (faqRequested && faqAnswers.sources().stream().anyMatch(source -> knowledge == null || !knowledge.sources().contains(source))) {
            throw new EvidenceUnavailableException("faq_snapshot_changed");
        }
        // 仅代码构造的澄清/拒绝与无任务的超时恢复保留原文；有事实时始终由模板展示。
        if (!faqRequested && observations.isEmpty() && checked == null) {
            if (generated.kind() == CustomerAgent.Kind.FAQ || generated.kind() == CustomerAgent.Kind.MIXED
                    || generated.kind() == CustomerAgent.Kind.TOOL) throw new EvidenceUnavailableException();
            return generated;
        }
        List<String> parts = new ArrayList<>(observations.values());
        parts.addAll(notes);
        List<String> sourceIds = new ArrayList<>();
        if (faqRequested) {
            // 展示绑定的发布快照，不再从模型最终协议里的sourceIds反推已完成的FAQ。
            for (AiKnowledgeRetrievalResult.Source source : faqAnswers.sources()) {
                parts.add((voucherQueried ? "平台规则（具体券状态以本次查询为准）：" : "平台规则：") + source.content());
                sourceIds.add(source.documentId());
            }
            parts.addAll(faqAnswers.unresolvedNotes());
        }
        if (checked != null) parts.add(selection.render(checked));
        CustomerAgent.Kind kind = !sourceIds.isEmpty() ? checked != null || !observations.isEmpty()
                ? CustomerAgent.Kind.MIXED : CustomerAgent.Kind.FAQ : checked != null ? CustomerAgent.Kind.SELECTION
                : observations.isEmpty() ? CustomerAgent.Kind.UNKNOWN : CustomerAgent.Kind.TOOL;
        return new CustomerAgent.Answer(String.join("\n\n", parts), List.copyOf(sourceIds), kind, checked);
    }

    public static final class EvidenceUnavailableException extends IllegalStateException {
        private final String reason;
        public EvidenceUnavailableException() { this("missing_evidence"); }
        public EvidenceUnavailableException(String reason) {
            super("本次回答缺少可核实的依据，请补充问题后再试"); this.reason = reason;
        }
        public String reason() { return reason; }
    }

    /** 末轮生成超时仍可展示已核实的券；未查到的混合任务部分明确保持未知。 */
    public boolean canRecoverFrom(RuntimeException failure) {
        if (!verifiedVouchers) return false;
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.net.http.HttpTimeoutException
                    || cause instanceof java.net.SocketTimeoutException) return true;
        }
        return false;
    }

    public CustomerAgent.Answer recoveredAnswer() {
        if (!verifiedVouchers) throw new IllegalStateException("没有已核实的优惠券结果");
        if (task != null && task.selects()) coverage.put(CustomerTask.Capability.SELECTION,
                new CustomerTask.Step(CustomerTask.Capability.SELECTION, CustomerTask.Status.PARTIAL, "MODEL_TIMEOUT_AFTER_QUERY"));
        CustomerAgent.Answer verified = answer(new CustomerAgent.Answer("", List.of()), null);
        return new CustomerAgent.Answer("本次仅返回已查到的结果，其余内容暂时无法确认。\n\n" + verified.answer(), List.of());
    }

    @Override
    public void close() {
        if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
    }
}
