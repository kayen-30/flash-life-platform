package com.hmdp.ai.customer;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.fasterxml.jackson.core.JsonParseException;
import com.hmdp.ai.execution.AiModelProtocolException;
import com.hmdp.ai.execution.AiModelProtocolException.Field;
import com.hmdp.ai.execution.AiModelProtocolException.Rejection;
import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Pattern;

/** 模型只提取本轮原文；金额与星期由代码规范化，已确认的约束才允许跨轮沿用。 */
public record CustomerTask(List<Capability> capabilities, String shopName, boolean continuation,
                           Constraints constraints, List<String> missing, List<String> faqQuestions) {
    public CustomerTask(List<Capability> capabilities, String shopName, boolean continuation,
                        Constraints constraints, List<String> missing) {
        this(capabilities, shopName, continuation, constraints, missing, List.of());
    }

    public CustomerTask {
        faqQuestions = faqQuestions == null ? List.of() : List.copyOf(faqQuestions);
    }
    private static final ObjectMapper JSON = strictJson();

    /** 模型字段必须符合声明类型，不能通过数字枚举或隐式文本转换改变任务。 */
    private static ObjectMapper strictJson() {
        var mapper = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                        DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS, DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);
        mapper.coercionConfigFor(LogicalType.Textual)
                .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
        return mapper;
    }
    private static final Pattern WEEKDAY = Pattern.compile("(?:星期|周|礼拜)([一二三四五六日天])");
    private static final Pattern WEEKDAY_ONLY = Pattern.compile(
            "(?=.*(?:星期|礼拜|周))(?:(?:星期|礼拜|周)[一二三四五六日天]?|本次|当天|使用|用券|在|的|要|须|必须|要求|条件|限制|符合|满足|匹配|可用|适用|能用|[\\s，、。])+");
    private static final Pattern SELECT_PREFIX = Pattern.compile("^(?:(?:请|帮我|只|仅|先)\\s*)*选(?:择)?\\s*");
    private static final Pattern POSITIVE_REQUEST_PREFIX = Pattern.compile("(?:(?:请|帮我|只|仅|先|然后|再|同时|并且|并)\\s*)*");
    private static final Pattern LOWEST_PAYMENT_GOAL = Pattern.compile("(?:支付|付款|实付)(?:金额)?(?:最少|最低)|(?:最低|最少)(?:的)?(?:支付|付款|实付)(?:金额)?");
    private static final Pattern HIGHEST_DEDUCTION_GOAL = Pattern.compile("抵扣(?:金额)?(?:最多|最大|最高)|(?:最多|最大|最高)(?:的)?抵扣(?:金额)?");
    private static final Pattern GOAL_NEGATION = Pattern.compile("(?:不要|不能|不想|不接受|不需要|不要求|不用|禁止|不是|别|不)(?:选择|选|按|以)?[\\s:：]*$");
    private static final Pattern AMOUNT = Pattern.compile("(\\d+(?:\\.\\d{1,2})?)\\s*元");

    private static final Pattern EXPLICIT_BUY_BUDGET = Pattern.compile(
            "(?:买券|购券|优惠券)预算\\s*(?:为|是|不超过|最多|[:：])?\\s*\\d+(?:\\.\\d{1,2})?\\s*元");
    private static final Pattern GUIDANCE = Pattern.compile("(?:怎么|怎样|如何|哪里|哪儿|去哪|到哪).*(?:看|查看|浏览|了解)");
    private static final Pattern ENTITY_REFERENCE = Pattern.compile("这家|那家|该店|这店|那店|它|刚才|上述|前面");
    private static final Pattern LIVE_REQUEST = Pattern.compile("列出|给我|查一下|查询|搜索|最新|找出|推荐|[0-9一二两三四五六七八九十]+(?:篇|条)");
    private static final Pattern EXCLUDE_UNKNOWN = Pattern.compile(
            "(?:条件)?(?:未知|不确定)(?:时|就)?(?:不推荐|不选)|(?:不能|无法)确定就不选|只选(?:确定|已确认)(?:适用|可用)(?:的)?(?:券|候选)?");
    private static final Pattern ALLOW_PENDING = Pattern.compile(
            "(?:允许|接受|可以(?:先)?给|可给|先给)(?:条件)?待确认(?:的)?候选(?:券)?|(?:条件)?(?:未知|不确定)(?:也)?(?:可以|可|允许)(?:先)?(?:推荐|作为待确认候选)");
    private static final Pattern POLICY_MENTION = Pattern.compile(EXCLUDE_UNKNOWN.pattern() + "|" + ALLOW_PENDING.pattern());
    private static final Pattern POLICY_NEGATION = Pattern.compile("(?:不要|不能|不想|不接受|不需要|不要求|不用|禁止|别|不)[\\s:：]*$");
    private static final Pattern FAQ_BOUNDARY = Pattern.compile("[。；;！？!?\\n，,]|(?:并且|同时|另外|顺便|然后|以及|还有|再|并)(?=(?:请)?(?:解释|说|问|介绍|告诉|咨询|讲))(?:说|问)?"
            + "|(?:而是|但是|但)(?=(?:请)?(?:说明|解释|介绍|回答|讲解|告诉|问|说|如何|怎么|怎样))"
            + "|(?=只(?:请)?(?:说明|解释|介绍|回答|讲解|告诉|问|说))|以及|还有|另外|同时");
    private static final Pattern FAQ_PREFIX = Pattern.compile("^(?:(?:并且|同时|另外|顺便|然后|以及|还有|再|并)(?:说|问)?|请问|我想问的是|我真正要问|我实际要问|我想问|我还想问|还想问|咨询一下|顺带问)[\\s:：]*");
    private static final Pattern FAQ_QUESTION = Pattern.compile("怎么|怎样|如何|哪里|哪儿|在哪|何处|哪个|哪些|什么|多久|多长|多少|几(?:人|位|天|日|分钟|小时)|何时|什么时候|是否|能否|可否|能不能|可不可以|吗|区别|解释|介绍|说说|讲讲|说明|步骤|流程");
    private static final Pattern FAQ_COMPLETE_LEFT = Pattern.compile("怎么|怎样|如何|哪里|哪儿|在哪|何处|哪个|哪些|什么|多久|多长|多少|几(?:人|位|天|日|分钟|小时)|何时|什么时候|是否|能否|可否|能不能|可不可以|吗|区别|步骤|流程");
    private static final Pattern FAQ_NEGATED = Pattern.compile("^(?:我)?不是(?:要)?(?:问|咨询|了解|讨论)|^(?:我)?(?:暂时|现在|先)?(?:关于)?(?:不用|不必|不需要|不要|不想|无需|别)(?:再|去)?(?:解释|回答|介绍|说明|说|讲|问|咨询|告诉|了解|讨论)|(?:不用|不必|不需要|不要|无需)(?:再)?(?:解释|回答|介绍|说明|说|讲)[了啦吧]?[。？?！!\\s]*$");
    private static final Pattern EXECUTION_REQUEST = Pattern.compile("选(?:一张|个|券|最)|挑(?:一张|个|券|最)|比较.{0,20}(?:券|店)|推荐.{0,20}(?:券|店)|最(?:省钱|便宜|划算)|抵扣(?:最大|最多)|查(?:一下|询)?(?:这家|那家|该店|店)|列出|给我.{0,12}(?:张|篇|条)|买券预算|购券预算|优惠券预算");
    private static final Pattern FAQ_DETAIL_CONTINUATION = Pattern.compile("保证|承诺|最迟|到账时间|到账时限|手续费|服务费|费用|操作步骤|操作流程|入口|查看位置|列明");
    private static final Pattern FAQ_EXECUTION_GUIDANCE = Pattern.compile("(?:解释|介绍|说明|讲解|讲讲|说说|怎么|怎样|如何).{0,12}(?:比较|选|挑|推荐)"
            + "|(?:支付|抵扣)(?:金额)?(?:最多|最少|最高|最低|最大)(?:是什么意思|有什么含义|的含义是什么|指的是什么)");
    private static final Pattern FAQ_COMPARISON_LEFT = Pattern.compile("(?:比较|区分|区别).*(?:普通券|秒杀券|优惠券)$");
    private static final Pattern FAQ_COMPARISON_RIGHT = Pattern.compile("^(?:普通券|秒杀券|优惠券)");

    public enum Capability { SHOP, VOUCHERS, SELECTION, SHOP_BLOGS, HOT_BLOGS, FAQ, OUT_OF_SCOPE, UNKNOWN }
    public enum SelectionGoal { LOWEST_PAYMENT, HIGHEST_DEDUCTION, UNSUPPORTED }
    public enum UnknownPolicy { DEFAULT, EXCLUDE, ALLOW_PENDING, UNSUPPORTED }
    public enum Status { COMPLETED, PARTIAL, NEEDS_CLARIFICATION, UNKNOWN, REFUSED, FAILED }
    public record Step(Capability capability, Status status, String reason) { }
    public record Outcome(String requestId, Status status, List<Step> steps, Constraints constraints, int modelCalls) { }
    public record State(Long shopId, String shopName, List<Capability> capabilities, Constraints constraints,
                        List<String> missing, long revision) { }
    public record Constraints(Integer weekday, String dayQuote, Long buyBudgetFen, String budgetQuote,
                              List<String> usageQuotes, SelectionGoal selectionGoal,
                              UnknownPolicy unknownPolicy, String unknownPolicyQuote) {
        public Constraints {
            usageQuotes = usageQuotes == null ? List.of() : List.copyOf(usageQuotes);
            selectionGoal = selectionGoal == null ? SelectionGoal.LOWEST_PAYMENT : selectionGoal;
            // 旧会话没有偏好字段时保持原策略；显式策略必须与其原文一致，不能靠枚举值授予权限。
            unknownPolicy = unknownPolicy == null ? UnknownPolicy.DEFAULT : unknownPolicy;
            if ((unknownPolicy == UnknownPolicy.DEFAULT) != (unknownPolicyQuote == null)
                    || (unknownPolicyQuote != null && (unknownPolicyQuote.length() > 100
                    || unknownPolicy != parseUnknownPolicy(unknownPolicyQuote)))) {
                throw new IllegalArgumentException("未知条件偏好与原文不一致");
            }
        }
        public Constraints(Integer weekday, String dayQuote, Long buyBudgetFen, String budgetQuote,
                           List<String> usageQuotes, SelectionGoal selectionGoal) {
            this(weekday, dayQuote, buyBudgetFen, budgetQuote, usageQuotes, selectionGoal, UnknownPolicy.DEFAULT, null);
        }
        public Constraints(Integer weekday, String dayQuote, Long buyBudgetFen, String budgetQuote, List<String> usageQuotes) {
            this(weekday, dayQuote, buyBudgetFen, budgetQuote, usageQuotes, SelectionGoal.LOWEST_PAYMENT);
        }
        public static Constraints empty() { return new Constraints(null, null, null, null, List.of()); }
        public List<String> evidence() {
            List<String> values = new ArrayList<>(usageQuotes == null ? List.of() : usageQuotes);
            if (dayQuote != null) values.add(dayQuote);
            if (budgetQuote != null) values.add(budgetQuote);
            return List.copyOf(values);
        }
    }

    /** 字段合法不等于语义正确：只接受本轮连续原文，禁止模型直接提交金额或业务ID。 */
    public static CustomerTask parse(String raw, String message, State previous) {
        return parse(raw, message, previous, false);
    }

    /** 已验证页面实体可支撑新任务；模型的continuation标记不能单独制造前序任务缺失。 */
    public static CustomerTask parse(String raw, String message, State previous, boolean verifiedShopAvailable) {
        try {
            Extracted value = JSON.readValue(raw, Extracted.class);
            if (value == null) throw invalid(Rejection.ROOT_STRUCTURE, Field.ROOT);
            if (value.capabilities() == null || value.capabilities().contains(null)) throw invalid(Rejection.FIELD_TYPE, Field.CAPABILITIES);
            if (value.capabilities().size() > 6) throw invalid(Rejection.FIELD_LIMIT, Field.CAPABILITIES);
            String name = quote(value.shopName(), message, 80, Field.SHOP_NAME);
            String day = quote(value.dayQuote(), message, 100, Field.DAY_QUOTE);
            String budget = restoreBudgetType(quote(value.budgetQuote(), message, 120, Field.BUDGET_QUOTE), message);
            String policyQuote = restorePolicyNegation(quote(value.unknownPolicyQuote(), message, 100, Field.UNKNOWN_POLICY_QUOTE), message);
            List<String> usage = new ArrayList<>();
            if (value.usageQuotes() != null) {
                if (value.usageQuotes().size() > 5) throw invalid(Rejection.FIELD_LIMIT, Field.USAGE_QUOTES);
                for (String part : value.usageQuotes()) {
                    String validated = quote(part, message, 200, Field.USAGE_QUOTES);
                    if (validated == null) throw invalid(Rejection.FIELD_TYPE, Field.USAGE_QUOTES);
                    usage.add(validated);
                }
            }
            boolean policyConflict = false;
            for (var iterator = usage.iterator(); iterator.hasNext();) {
                String part = iterator.next();
                String complete = restorePolicyNegation(part, message);
                UnknownPolicy proposed = parseUnknownPolicy(part);
                if (proposed == UnknownPolicy.UNSUPPORTED) continue;
                // 偏好不是商家使用条件；兼容模型把独立偏好片段重复放进usageQuotes的情况。
                if (policyQuote == null) policyQuote = complete;
                else if (parseUnknownPolicy(policyQuote) != parseUnknownPolicy(complete)) policyConflict = true;
                iterator.remove();
            }
            boolean reuse = value.continuation() && previous != null
                    && (name == null || previous.shopId() == null || name.equals(previous.shopName()));
            List<Capability> tasks = new ArrayList<>(new LinkedHashSet<>(value.capabilities()));
            if (tasks.isEmpty() && reuse) tasks.addAll(previous.capabilities());
            if (tasks.isEmpty()) tasks.add(Capability.UNKNOWN);
            // 无具体实体的查看方式属于功能咨询；页面实体不能把泛指使用指导劫持为实时查询。
            boolean guidance = name == null && tasks.size() == 1
                    && Set.of(Capability.SHOP, Capability.SHOP_BLOGS).contains(tasks.get(0))
                    && GUIDANCE.matcher(message).find() && !ENTITY_REFERENCE.matcher(message).find()
                    && !LIVE_REQUEST.matcher(message).find();
            if (guidance) tasks = new ArrayList<>(List.of(Capability.FAQ));
            if (!tasks.contains(Capability.FAQ) && value.faqQuestions() != null && !value.faqQuestions().isEmpty()) {
                throw invalid(Rejection.SUBTASK_SCOPE, Field.FAQ_QUESTIONS);
            }
            List<String> faq = new ArrayList<>();
            if (tasks.contains(Capability.FAQ)) {
                if (value.faqQuestions() != null) {
                    if (value.faqQuestions().size() > 5) throw invalid(Rejection.FIELD_LIMIT, Field.FAQ_QUESTIONS);
                    for (String part : value.faqQuestions()) {
                        String validated = quote(part, message, 1000, Field.FAQ_QUESTIONS);
                        if (validated == null) throw invalid(Rejection.FIELD_TYPE, Field.FAQ_QUESTIONS);
                        faq.add(validated);
                    }
                }
                faq = normalizeFaqQuestions(message, faq, tasks.equals(List.of(Capability.FAQ)), name, day, budget, usage);
            }
            boolean selecting = tasks.contains(Capability.SELECTION);
            String selectionScope = message;
            if (selecting) {
                // 已支持的明确偏好不能因模型漏字段而消失；店名和FAQ原文不作为本次选券偏好。
                selectionScope = name == null ? message : message.replace(name, " ".repeat(name.length()));
                for (String question : faq) selectionScope = selectionScope.replace(question, " ".repeat(question.length()));
                if (policyQuote != null && !selectionScope.contains(policyQuote)) throw invalid(Rejection.SUBTASK_SCOPE, Field.UNKNOWN_POLICY_QUOTE);
                var mentions = POLICY_MENTION.matcher(selectionScope);
                while (mentions.find()) {
                    String stated = restorePolicyNegation(message, mentions.start(), mentions.end());
                    if (policyQuote == null) policyQuote = stated;
                    else if (parseUnknownPolicy(policyQuote) != parseUnknownPolicy(stated)) policyConflict = true;
                }
            }
            // 使用条件仅属于选券，单纯查店或列券不继承前任务的未决条件。
            if (!selecting) { day = null; budget = null; usage.clear(); policyQuote = null; policyConflict = false; }
            Constraints old = reuse && tasks.contains(Capability.SELECTION) && previous.constraints() != null
                    ? previous.constraints() : Constraints.empty();
            List<String> missing = new ArrayList<>(reuse && selecting && previous.missing() != null ? previous.missing() : List.of());
            missing.remove("PREVIOUS_TASK_MISSING");
            Integer weekday = old.weekday();
            if (day != null) {
                var matcher = WEEKDAY.matcher(day);
                weekday = matcher.find() ? "一二三四五六日天".indexOf(matcher.group(1)) + 1 : null;
                if (weekday != null && weekday == 8) weekday = 7;
                missing.remove("USE_DAY_AMBIGUOUS_OR_UNSUPPORTED");
                if (weekday == null || matcher.find()) { weekday = null; missing.add("USE_DAY_AMBIGUOUS_OR_UNSUPPORTED"); }
            }
            Long fen = old.buyBudgetFen();
            if (budget != null) {
                missing.remove("BUY_BUDGET_AMBIGUOUS_OR_UNSUPPORTED");
                fen = amount(budget, reuse ? old.buyBudgetFen() : null);
                if (fen == null) missing.add("BUY_BUDGET_AMBIGUOUS_OR_UNSUPPORTED");
            }
            if (value.continuation() && previous == null && name == null && !verifiedShopAvailable) missing.add("PREVIOUS_TASK_MISSING");
            List<String> inherited = new ArrayList<>(old.usageQuotes() == null ? List.of() : old.usageQuotes());
            // 先清理前轮重复星期，再合并本轮；换星期不能留下旧星期的冗余副本。
            inherited.removeIf(part -> repeatsWeekday(part, old.weekday()));
            Integer resolvedWeekday = weekday;
            usage.removeIf(part -> repeatsWeekdayRequest(part, resolvedWeekday, message));
            inherited.addAll(usage);
            if (inherited.size() > 5) missing.add("TOO_MANY_USAGE_CONDITIONS");
            SelectionGoal goal = !selecting ? SelectionGoal.LOWEST_PAYMENT
                    : resolveSelectionGoal(selectionScope, value.selectionGoal(), old.selectionGoal(), reuse);
            missing.remove("SELECTION_GOAL_UNSUPPORTED");
            if (goal == SelectionGoal.UNSUPPORTED && !missing.contains("SELECTION_GOAL_UNSUPPORTED")) missing.add("SELECTION_GOAL_UNSUPPORTED");
            UnknownPolicy policy = policyQuote == null ? old.unknownPolicy() : parseUnknownPolicy(policyQuote);
            if (policyQuote != null) missing.remove("UNKNOWN_POLICY_AMBIGUOUS_OR_UNSUPPORTED");
            if ((policyConflict || policy == UnknownPolicy.UNSUPPORTED) && !missing.contains("UNKNOWN_POLICY_AMBIGUOUS_OR_UNSUPPORTED")) {
                missing.add("UNKNOWN_POLICY_AMBIGUOUS_OR_UNSUPPORTED");
            }
            Constraints constraints = new Constraints(weekday, day == null ? old.dayQuote() : day,
                    fen, budget == null ? old.budgetQuote() : budget,
                    List.copyOf(new LinkedHashSet<>(inherited)).stream().limit(5).toList(), goal,
                    policy, policyQuote == null ? old.unknownPolicyQuote() : policyQuote);
            return new CustomerTask(List.copyOf(tasks), name, reuse, constraints, List.copyOf(missing), List.copyOf(faq));
        } catch (AiModelProtocolException rejected) {
            throw rejected;
        } catch (UnrecognizedPropertyException rejected) {
            throw new AiModelProtocolException("任务提取协议无效", Rejection.UNKNOWN_FIELD, Field.ROOT, rejected);
        } catch (JsonParseException rejected) {
            throw new AiModelProtocolException("任务提取协议无效", Rejection.JSON_SYNTAX, Field.ROOT, rejected);
        } catch (JsonMappingException rejected) {
            Field field = rejected.getPath().stream().map(reference -> Field.fromJsonName(reference.getFieldName()))
                    .filter(value -> value != Field.NONE).findFirst().orElse(Field.ROOT);
            Rejection code = rejected instanceof InvalidFormatException format && format.getTargetType() != null
                    && format.getTargetType().isEnum() ? Rejection.ENUM_VALUE
                    : field == Field.ROOT ? Rejection.ROOT_STRUCTURE : Rejection.FIELD_TYPE;
            throw new AiModelProtocolException("任务提取协议无效", code, field, rejected);
        } catch (Exception rejected) {
            throw new AiModelProtocolException("任务提取协议无效", Rejection.UNCLASSIFIED, Field.NONE, rejected);
        }
    }

    /** 只规范化明确支持的偏好原文；否定、复合或其他说法交给澄清，不猜用户的风险取舍。 */
    private static UnknownPolicy parseUnknownPolicy(String quote) {
        if (quote == null) return UnknownPolicy.DEFAULT;
        String value = quote.replaceAll("[\\s，,。.!！?？、:：]", "");
        if (EXCLUDE_UNKNOWN.matcher(value).matches()) return UnknownPolicy.EXCLUDE;
        if (ALLOW_PENDING.matcher(value).matches()) return UnknownPolicy.ALLOW_PENDING;
        return UnknownPolicy.UNSUPPORTED;
    }

    /** 补回紧邻片段的否定词；“不允许待确认候选”不能因模型只复制后半句而放宽。 */
    private static String restorePolicyNegation(String quote, String message) {
        if (quote == null) return null;
        int start = message.indexOf(quote);
        return restorePolicyNegation(message, start, start + quote.length());
    }

    private static String restorePolicyNegation(String message, int start, int end) {
        var prefix = POLICY_NEGATION.matcher(message.substring(0, start));
        return message.substring(prefix.find() ? prefix.start() : start, end);
    }

    private static String quote(String value, String message, int max, Field field) {
        if (value == null || value.isEmpty()) return null;
        if (value.length() > max) throw invalid(Rejection.QUOTE_LIMIT, field);
        if (value.isBlank() || !message.contains(value)) throw invalid(Rejection.QUOTE_NOT_IN_INPUT, field);
        return value;
    }

    private static AiModelProtocolException invalid(Rejection code, Field field) {
        return new AiModelProtocolException("任务提取协议无效", code, field, null);
    }

    /** 引用必须保留完整问句；原文是范围依据，模型不能通过只复制主题词绕过细节检查。 */
    private static List<String> normalizeFaqQuestions(String message, List<String> proposed, boolean faqOnly,
                                                     String shop, String day, String budget, List<String> usage) {
        List<int[]> groups = new ArrayList<>();
        List<String> conditions = new ArrayList<>(usage);
        if (day != null) conditions.add(day);
        if (budget != null) conditions.add(budget);
        int start = 0;
        boolean hardBoundary = true;
        boolean previousFaq = false;
        var boundaries = FAQ_BOUNDARY.matcher(message);
        while (start < message.length()) {
            boolean found = boundaries.find();
            while (found && (quotedAt(message, boundaries.start())
                    || skipSoftFaqConnector(message, start, boundaries.start(), boundaries.end(), boundaries.group()))) {
                found = boundaries.find();
            }
            int end = found ? boundaries.start() : message.length();
            int contentStart = start;
            while (contentStart < end && Character.isWhitespace(message.charAt(contentStart))) contentStart++;
            String clause = message.substring(contentStart, end).stripTrailing();
            var prefix = FAQ_PREFIX.matcher(clause);
            if (prefix.find()) {
                contentStart += prefix.end();
                clause = message.substring(contentStart, end).stripTrailing();
            }
            end = contentStart + clause.length();
            final String current = clause;
            boolean anchored = proposed.stream().anyMatch(quote -> quote.contains(current) || current.contains(quote));
            boolean inquiry = FAQ_QUESTION.matcher(clause).find();
            boolean execution = !faqOnly && !FAQ_EXECUTION_GUIDANCE.matcher(clause).find() && (EXECUTION_REQUEST.matcher(clause).find()
                    || !inquiry && conditions.stream().anyMatch(condition -> clauseWithoutShop(current, shop).contains(condition)));
            List<Integer> subjects = FaqEvidenceCoverage.scopeSubjects(clause);
            boolean supplement = previousFaq && !hardBoundary && FAQ_DETAIL_CONTINUATION.matcher(clause).find()
                    && FaqEvidenceCoverage.scopeSubjects(message.substring(groups.get(groups.size() - 1)[0], groups.get(groups.size() - 1)[1])).containsAll(subjects);
            boolean active = !clause.isBlank() && !FAQ_NEGATED.matcher(clause).find() && !execution
                    && (anchored || supplement || inquiry && (faqOnly || !subjects.isEmpty()));
            if (active) {
                boolean separateQuotes = proposed.stream().anyMatch(quote -> current.contains(quote))
                        && previousFaq && proposed.stream().anyMatch(quote -> message.substring(groups.get(groups.size() - 1)[0], groups.get(groups.size() - 1)[1]).contains(quote)
                        && !current.contains(quote));
                boolean previousInquiry = previousFaq && FAQ_QUESTION.matcher(message.substring(
                        groups.get(groups.size() - 1)[0], groups.get(groups.size() - 1)[1])).find();
                boolean differentSubject = previousInquiry && !FaqEvidenceCoverage.scopeSubjects(clause).isEmpty()
                        && !FaqEvidenceCoverage.scopeSubjects(message.substring(groups.get(groups.size() - 1)[0], groups.get(groups.size() - 1)[1]))
                        .containsAll(FaqEvidenceCoverage.scopeSubjects(clause));
                boolean independentSubject = previousInquiry && inquiry && !subjects.isEmpty() && FaqEvidenceCoverage.startsIndependentQuestion(clause);
                // 收到短信等陈述是后续问题的上下文，不能只因后半句出现账号就拆出一题。
                // 逗号后的同题补充仍是一题；独立主题、明确句界或模型已分开的合法子题保持独立。
                if (previousFaq && !hardBoundary && !separateQuotes && !differentSubject && !independentSubject) groups.get(groups.size() - 1)[1] = end;
                else groups.add(new int[]{contentStart, end});
            }
            previousFaq = active;
            if (!found) break;
            hardBoundary = !boundaries.group().matches("[，,]") && !softFaqConnector(boundaries.group());
            start = boundaries.end();
        }
        List<String> normalized = new ArrayList<>();
        for (int[] group : groups) {
            String complete = message.substring(group[0], group[1]);
            // 完整的合法原文保持不变，包括问号；规范化只补范围，不改写用户措辞。
            String original = proposed.stream().filter(quote -> quote.replaceAll("[？?。！!\\s]+$", "").equals(complete)).findFirst().orElse(null);
            if (original == null && proposed.isEmpty() && faqOnly && message.replaceAll("[？?。！!\\s]+$", "").equals(complete)) original = message;
            normalized.add(original == null ? complete : original);
        }
        if (normalized.isEmpty() && proposed.isEmpty() && faqOnly && !FAQ_NEGATED.matcher(message).find()) normalized.add(message);
        // 不能静默丢掉模型提交的其他片段，也不能把执行条件、否定咨询恢复成问题。
        if (proposed.stream().anyMatch(quote -> {
            // 句界标点不属于被丢弃的业务条件；原始引用已验证，范围映射兼容分组去掉的问号。
            String scope = quote.replaceAll("[？?。！!\\s]+$", "").stripLeading();
            return scope.isEmpty() || normalized.stream().noneMatch(question -> question.contains(scope) || scope.contains(question));
        })) {
            throw invalid(Rejection.SUBTASK_SCOPE, Field.FAQ_QUESTIONS);
        }
        if (normalized.size() > 5) throw invalid(Rejection.FIELD_LIMIT, Field.FAQ_QUESTIONS);
        if (normalized.stream().anyMatch(question -> question.length() > 1000)) throw invalid(Rejection.QUOTE_LIMIT, Field.FAQ_QUESTIONS);
        return new ArrayList<>(new LinkedHashSet<>(normalized));
    }

    private static String clauseWithoutShop(String clause, String shop) {
        return shop == null ? clause : clause.replace(shop, " ".repeat(shop.length()));
    }

    private static boolean softFaqConnector(String delimiter) {
        return Set.of("以及", "还有", "另外", "同时").contains(delimiter);
    }

    /** “以及”可连接两个咨询，也可连接同一比较问题里的两个对象。 */
    private static boolean skipSoftFaqConnector(String message, int start, int from, int to, String delimiter) {
        if (!softFaqConnector(delimiter)) return false;
        String before = message.substring(start, from);
        return !FAQ_COMPLETE_LEFT.matcher(before).find() || "以及".equals(delimiter)
                && FAQ_COMPARISON_LEFT.matcher(before).find() && FAQ_COMPARISON_RIGHT.matcher(message.substring(to)).find();
    }

    /** 引文中的问号、逗号不产生新任务；未配对的标点不作为引用边界。 */
    private static boolean quotedAt(String message, int position) {
        for (String pair : List.of("“”", "‘’", "\"\"", "''")) {
            int open = message.indexOf(pair.charAt(0));
            while (open >= 0) {
                int close = message.indexOf(pair.charAt(1), open + 1);
                if (close < 0) break;
                if (open < position && position < close) return true;
                open = message.indexOf(pair.charAt(0), close + 1);
            }
        }
        return false;
    }

    /** 只消除已由结构字段承载的纯星期要求；人数、晚间、核销日期及相反星期仍保留。 */
    private static boolean repeatsWeekday(String quote, Integer weekday) {
        if (weekday == null || !WEEKDAY_ONLY.matcher(quote).matches()) return false;
        var matcher = WEEKDAY.matcher(quote);
        while (matcher.find()) {
            int value = Math.min(7, "一二三四五六日天".indexOf(matcher.group(1)) + 1);
            if (value != weekday) return false;
        }
        return true;
    }

    /** 只归一化本轮无歧义的纯选择指令；否定、额外条件及无法复核的旧片段不能被丢弃。 */
    private static boolean repeatsWeekdayRequest(String quote, Integer weekday, String message) {
        var prefix = SELECT_PREFIX.matcher(quote);
        if (!prefix.find()) return repeatsWeekday(quote, weekday);
        int start = message.indexOf(quote);
        if (start < 0 || message.indexOf(quote, start + 1) >= 0) return false;
        int boundary = start - 1;
        while (boundary >= 0 && "，,。；;！？!?\n".indexOf(message.charAt(boundary)) < 0) boundary--;
        // 即使模型只复制了“选星期符合”，原文中的“不选…”也不能被当作肯定选择要求。
        if (!POSITIVE_REQUEST_PREFIX.matcher(message.substring(boundary + 1, start).strip()).matches()) return false;
        String condition = quote.substring(prefix.end()).replaceFirst("(?:优惠券|券)$", "");
        return repeatsWeekday(condition, weekday);
    }

    /** 排序目标属于用户需求；已识别的原文优先于模型枚举，未声明修改则继承已确认目标。 */
    private static SelectionGoal resolveSelectionGoal(String scope, SelectionGoal proposed, SelectionGoal previous, boolean reuse) {
        // 仍有未支持目标时不以某个已识别词替代完整要求，例如“性价比最高且抵扣最多”。
        if (proposed == SelectionGoal.UNSUPPORTED) return SelectionGoal.UNSUPPORTED;
        boolean low = goalMention(LOWEST_PAYMENT_GOAL, scope, false), high = goalMention(HIGHEST_DEDUCTION_GOAL, scope, false);
        boolean notLow = goalMention(LOWEST_PAYMENT_GOAL, scope, true), notHigh = goalMention(HIGHEST_DEDUCTION_GOAL, scope, true);
        if ((low && high) || (low && notLow) || (high && notHigh)) return SelectionGoal.UNSUPPORTED;
        if (low) return SelectionGoal.LOWEST_PAYMENT;
        if (high) return SelectionGoal.HIGHEST_DEDUCTION;
        if (notLow || notHigh) return SelectionGoal.UNSUPPORTED;
        if (reuse) return previous == null ? SelectionGoal.LOWEST_PAYMENT : previous;
        // 没有原文依据的“抵扣最多”不能改变默认支付目标；让用户确认而非猜测。
        return proposed == SelectionGoal.HIGHEST_DEDUCTION ? SelectionGoal.UNSUPPORTED : SelectionGoal.LOWEST_PAYMENT;
    }

    private static boolean goalMention(Pattern pattern, String scope, boolean negated) {
        var matcher = pattern.matcher(scope);
        while (matcher.find()) {
            if (GOAL_NEGATION.matcher(scope.substring(0, matcher.start())).find() == negated) return true;
        }
        return false;
    }

    /** 补全模型截短的预算原文，仅允许本轮唯一位置的明确买券表达，仍然保留连续来源。 */
    private static String restoreBudgetType(String quote, String message) {
        if (quote == null || message.indexOf(quote) != message.lastIndexOf(quote)) return quote;
        var matcher = EXPLICIT_BUY_BUDGET.matcher(message);
        String full = null;
        while (matcher.find()) {
            if (matcher.group().contains(quote)) {
                if (full != null) return quote;
                full = matcher.group();
            }
        }
        return full == null ? quote : full;
    }

    /** 首次仅认明确买券预算；后续可改绝对值或增减，不把用餐预算当买券预算。 */
    private static Long amount(String quote, Long previous) {
        if (quote.contains("人均") || quote.contains("用餐") || quote.contains("吃饭")) return null;
        boolean explicit = quote.contains("买券预算") || quote.contains("购券预算") || quote.contains("优惠券预算");
        if (!explicit && (previous == null || !quote.contains("预算"))) return null;
        var match = AMOUNT.matcher(quote);
        if (!match.find()) return null;
        try {
            long value = new BigDecimal(match.group(1)).movePointRight(2).longValueExact();
            if (match.find()) return null;
            if (quote.matches(".*(?:再少|减少|减去|降低|减).*")) return previous == null ? null : nonnegative(Math.subtractExact(previous, value));
            if (quote.matches(".*(?:再多|增加|加上|提高|加).*")) return previous == null ? null : nonnegative(Math.addExact(previous, value));
            return value;
        } catch (ArithmeticException invalid) { return null; }
    }

    private static Long nonnegative(long value) { return value < 0 ? null : value; }
    public boolean needsShop() { return capabilities.stream().anyMatch(c -> Set.of(Capability.SHOP, Capability.VOUCHERS, Capability.SELECTION, Capability.SHOP_BLOGS).contains(c)); }
    public boolean selects() { return capabilities.contains(Capability.SELECTION); }
    public String context() {
        try { return JSON.writeValueAsString(this); }
        catch (Exception invalid) { throw new IllegalStateException("任务序列化失败", invalid); }
    }
    public record Extracted(List<Capability> capabilities, String shopName, boolean continuation,
                            String dayQuote, String budgetQuote, List<String> usageQuotes, SelectionGoal selectionGoal,
                            List<String> faqQuestions, String unknownPolicyQuote) {
        public Extracted(List<Capability> capabilities, String shopName, boolean continuation, String dayQuote,
                         String budgetQuote, List<String> usageQuotes, SelectionGoal selectionGoal, List<String> faqQuestions) {
            this(capabilities, shopName, continuation, dayQuote, budgetQuote, usageQuotes, selectionGoal, faqQuestions, null);
        }
        public Extracted(List<Capability> capabilities, String shopName, boolean continuation, String dayQuote,
                         String budgetQuote, List<String> usageQuotes, SelectionGoal selectionGoal) {
            this(capabilities, shopName, continuation, dayQuote, budgetQuote, usageQuotes, selectionGoal, List.of());
        }
        public Extracted(List<Capability> capabilities, String shopName, boolean continuation, String dayQuote, String budgetQuote, List<String> usageQuotes) {
            this(capabilities, shopName, continuation, dayQuote, budgetQuote, usageQuotes, null, List.of());
        }
    }
}
