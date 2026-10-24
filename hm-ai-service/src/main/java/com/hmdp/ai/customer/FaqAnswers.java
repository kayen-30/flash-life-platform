package com.hmdp.ai.customer;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.knowledge.AiKnowledgeRetrievalResult;
import com.hmdp.ai.execution.AiModelProtocolException;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** FAQ逐题证据结果；模型只能提交来源提议，状态与展示来源由代码核对后生成。 */
public final class FaqAnswers {
    private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    public record Item(int questionIndex, String question, CustomerTask.Status status, List<String> sourceIds, String reason,
                       List<String> missingDetails) {
        public Item(int questionIndex, String question, CustomerTask.Status status, List<String> sourceIds, String reason) {
            this(questionIndex, question, status, sourceIds, reason, List.of());
        }
        public Item { sourceIds = List.copyOf(sourceIds); missingDetails = List.copyOf(missingDetails); }
    }

    private final List<Item> items;
    private final List<AiKnowledgeRetrievalResult.Source> sources;

    private FaqAnswers(List<Item> items, List<AiKnowledgeRetrievalResult.Source> sources) {
        this.items = List.copyOf(items);
        this.sources = List.copyOf(sources);
    }

    public List<Item> items() { return items; }
    public List<AiKnowledgeRetrievalResult.Source> sources() { return sources; }
    public List<String> sourceIds() { return sources.stream().map(AiKnowledgeRetrievalResult.Source::documentId).toList(); }
    public List<String> questions() { return items.stream().map(Item::question).toList(); }
    public boolean protocolComplete() { return items.stream().noneMatch(item -> "MODEL_PROTOCOL_INVALID".equals(item.reason())); }

    /** 每道原文问题独立核对；漏题、坏ID、缺同类信息都不能被其他题目的来源补成完成。 */
    public static FaqAnswers parse(List<String> questions, String raw, AiKnowledgeRetrievalResult knowledge) {
        try {
            if (questions.isEmpty() || questions.size() > 5) throw new IllegalArgumentException();
            JsonNode root = JSON.readTree(raw);
            if (root == null || !root.isObject() || root.size() != 1 || !root.path("answers").isArray()
                    || root.path("answers").size() > questions.size()) throw new IllegalArgumentException();
            Map<String, AiKnowledgeRetrievalResult.Source> candidates = knowledge == null ? Map.of()
                    : knowledge.sources().stream().collect(Collectors.toMap(AiKnowledgeRetrievalResult.Source::documentId,
                    Function.identity(), (first, duplicate) -> first));
            List<Item> answers = new ArrayList<>();
            for (int i = 0; i < questions.size(); i++) answers.add(new Item(i, questions.get(i),
                    CustomerTask.Status.FAILED, List.of(), "MODEL_PROTOCOL_INVALID"));
            boolean[] seen = new boolean[questions.size()];
            for (JsonNode answer : root.path("answers")) {
                if (!answer.isObject() || answer.size() != 2 || !answer.path("questionIndex").isIntegralNumber()
                        || !answer.path("questionIndex").canConvertToInt() || !answer.path("sourceIds").isArray()
                        || answer.path("sourceIds").size() > 3) throw new IllegalArgumentException();
                int index = answer.path("questionIndex").intValue();
                if (index < 0 || index >= questions.size() || seen[index]) throw new IllegalArgumentException();
                seen[index] = true;
                LinkedHashSet<String> ids = new LinkedHashSet<>();
                for (JsonNode id : answer.path("sourceIds")) {
                    if (!id.isTextual() || id.asText().isBlank() || id.asText().length() > 100 || !ids.add(id.asText())) {
                        throw new IllegalArgumentException();
                    }
                }
                String reason = "PUBLISHED_EVIDENCE_SELECTED";
                if (ids.isEmpty() || ids.stream().anyMatch(id -> !candidates.containsKey(id)
                        || candidates.get(id).content() == null || candidates.get(id).content().isBlank())) {
                    reason = "FAQ_EVIDENCE_MISSING";
                } else {
                    String evidence = ids.stream().map(id -> candidates.get(id).content()).collect(Collectors.joining("\n"));
                    if (!FaqEvidenceCoverage.coversSubjects(questions.get(index), evidence)) reason = "FAQ_SUBJECT_NOT_COVERED";
                }
                List<String> approvedIds = List.of();
                List<String> missingDetails = List.of();
                CustomerTask.Status status = CustomerTask.Status.UNKNOWN;
                if (reason.equals("PUBLISHED_EVIDENCE_SELECTED")) {
                    var coverage = FaqEvidenceCoverage.assess(questions.get(index), ids.stream().map(candidates::get).toList());
                    approvedIds = coverage.sourceIds();
                    missingDetails = coverage.missingDetails();
                    if (missingDetails.isEmpty() && approvedIds.isEmpty()) reason = "FAQ_SUBJECT_NOT_COVERED";
                    else if (missingDetails.isEmpty()) status = CustomerTask.Status.COMPLETED;
                    else {
                        // 一个子问题也可能只答出部分细节，不能抹掉已知内容或把整题标完成。
                        status = approvedIds.isEmpty() ? CustomerTask.Status.UNKNOWN : CustomerTask.Status.PARTIAL;
                        reason = "FAQ_REQUIRED_DETAILS_MISSING";
                    }
                }
                answers.set(index, new Item(index, questions.get(index), status, approvedIds, reason, missingDetails));
            }
            LinkedHashSet<String> used = answers.stream().flatMap(item -> item.sourceIds().stream())
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            List<AiKnowledgeRetrievalResult.Source> approved = used.stream().map(candidates::get).toList();
            return new FaqAnswers(answers, approved);
        } catch (Exception invalid) {
            throw new AiModelProtocolException("FAQ逐题来源协议无效", invalid);
        }
    }

    /** 模型或检索故障按题记录；保留空问题来表达组合任务尚未得到可验证的子问题范围。 */
    public static FaqAnswers unresolved(List<String> questions, CustomerTask.Status status, String reason) {
        List<String> scope = questions.isEmpty() ? List.of("") : questions;
        List<Item> items = new ArrayList<>();
        for (int i = 0; i < scope.size(); i++) items.add(new Item(i, scope.get(i), status, List.of(), reason));
        return new FaqAnswers(items, List.of());
    }

    public CustomerTask.Status status() {
        long completed = items.stream().filter(item -> item.status() == CustomerTask.Status.COMPLETED).count();
        if (completed == items.size()) return CustomerTask.Status.COMPLETED;
        if (completed > 0 || items.stream().anyMatch(item -> item.status() == CustomerTask.Status.PARTIAL)) return CustomerTask.Status.PARTIAL;
        if (items.stream().anyMatch(item -> item.status() == CustomerTask.Status.FAILED)) return CustomerTask.Status.FAILED;
        if (items.stream().anyMatch(item -> item.status() == CustomerTask.Status.NEEDS_CLARIFICATION)) return CustomerTask.Status.NEEDS_CLARIFICATION;
        return CustomerTask.Status.UNKNOWN;
    }

    public String reason() {
        CustomerTask.Status status = status();
        return status == CustomerTask.Status.COMPLETED ? "PUBLISHED_EVIDENCE_SELECTED"
                : status == CustomerTask.Status.PARTIAL ? "FAQ_QUESTIONS_PARTIALLY_ANSWERED"
                : items.stream().filter(item -> item.status() == status).findFirst().orElseThrow().reason();
    }

    /** 未回答的问题按原文明确列出，不能用已完成的其他问题掩盖。 */
    public List<String> unresolvedNotes() {
        return items.stream().filter(item -> item.status() != CustomerTask.Status.COMPLETED).map(item ->
                item.question().isBlank() ? "请单独说明需要咨询的平台问题。"
                        : !item.missingDetails().isEmpty() ? "平台问题『" + item.question() + "』缺少" + String.join("、", item.missingDetails()) + "的依据，暂时无法确认。"
                        : "平台问题『" + item.question() + "』暂时无法核实，请补充问题或稍后重试。").toList();
    }
}
