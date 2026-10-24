package com.hmdp.ai.customer;

import com.hmdp.ai.knowledge.AiKnowledgeRetrievalResult;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

/** 核对问题明确要求的事实细节；这是必要条件检查，不是通用语义蕴含证明。 */
final class FaqEvidenceCoverage {
    private static final String NUMBER = "(?:[0-9]+(?:\\.[0-9]+)?|[零〇一二两三四五六七八九十百千万]+)";
    private static final String TIME = NUMBER + "(?:\\s*[-~～至到]\\s*" + NUMBER + ")?\\s*(?:个)?\\s*(?:工作日|自然日|分钟|小时|秒钟|天|日|秒|周|月|年)";
    private static final String RECEIPT = "(?:到账|退回|退还|完成退款|退款完成)";
    private static final String MONEY = "(?:[¥￥]\\s*" + NUMBER + "|" + NUMBER + "\\s*(?:元|块|%|％))";
    private static final Pattern REFUND = pattern("退款|退钱|退费|返款|到账|退回(?:款|钱)");
    private static final Pattern REFUND_SUBJECT = pattern("退款|退钱|退费|返款|退回(?:款|钱)");
    private static final Pattern CANCEL = pattern("取消订单|撤销订单|退单");
    private static final Pattern TIME_QUESTION = pattern("多久|多长时间|什么时候|何时|几(?:个)?(?:工作日|自然日|分钟|小时|天|日|秒|周|月)|时限|周期|时间|最迟|立刻|立即|马上|即时");
    private static final Pattern PROMISE_QUESTION = pattern("保证|承诺|一定|肯定|最迟|立刻|立即|马上|即时");
    private static final Pattern FEE_QUESTION = pattern("手续费|服务费|收费|费用|扣费|扣多少");
    private static final Pattern NAVIGATION = pattern("哪里|哪儿|在哪|何处|哪个页面|去哪|怎样查看|怎么查看|如何查看");
    private static final Pattern VALUE_QUESTION = pattern("多少|几(?:人|位|天|分钟)|多久|什么时候|何时|最迟|立刻|立即|马上|是否|能否|可否|能不能|可不可以|可以.{0,12}吗|支持.{0,12}吗|允许.{0,12}吗|保证.{0,12}吗|是什么|什么限制");
    private static final Pattern META_QUESTION = pattern("是否|能否|可否|能不能|可不可以|请查看|需查看|请咨询|以.{0,20}为准");
    private static final Pattern REFUND_INTRO = pattern("^(?:申请|办理|发起)?退款(?:时|后|申请(?:通过|受理|成功)后)?[：:]?$");
    private static final Pattern RECEIPT_TIME = pattern(TIME + "(?:内|后|左右|之内|以内|原路|即可|可以|将|会|完成|\\s)*" + RECEIPT
            + "|" + RECEIPT + "(?:时限|时间|周期|期限|通常|一般|预计|大约|约|为|是|需|需要|要|在|不超过|最多|最迟|[，,:：\\s])*" + TIME
            + "|(?:当日|当天|次日|次工作日|立即|即时)(?:内|即可|原路|\\s)*" + RECEIPT);
    private static final Pattern PROMISE = pattern("(?:不保证|不承诺|无法保证|不能保证|保证|承诺|最迟|确保|一定).{0,32}" + RECEIPT
            + "|" + RECEIPT + ".{0,16}(?:不保证|不承诺|无法保证|不能保证|保证|承诺)");
    private static final Pattern OTHER_ACTION = pattern("回复|联系客服|联系客户|验证码|发送通知|短信");
    private static final Pattern OTHER_MONEY_SUBJECT = pattern("充值|提现|转账|红包|返现|借款|贷款|工资");
    private static final Pattern FEE_VALUE = pattern("(?:退款(?:手续费|费用|服务费)|手续费|服务费|扣费)(?:金额|为|是|需|需要|收取|按|扣除|支付|原支付金额的|实付金额的|[：:\\s])*" + MONEY
            + "|(?:退款|退钱|退费)(?:时|后|将|需|需要|会)?(?:收取|扣除|扣取|收|扣)" + MONEY + "(?:的)?(?:手续费|服务费)"
            + "|(?:退款|退钱|退费)(?:完全)?免费"
            + "|(?:免收|不收取|不收|不扣|免)(?:任何|额外|退款)?(?:手续费|服务费|费用)"
            + "|" + MONEY + "(?:的)?(?:退款手续费|手续费|服务费)");
    private static final Pattern VALIDITY = pattern("有效期|核销期限|使用期限|有效至|到期时间|过期");
    private static final Pattern VALIDITY_VALUE = pattern("(?:有效期|使用期限|核销期限).{0,24}" + TIME
            + "|(?:有效至|到期时间|有效期至|使用至).{0,8}[0-9]{4}[年./-][0-9]{1,2}[月./-][0-9]{1,2}"
            + "|" + TIME + "(?:内)?有效|长期有效|永久有效|无有效期限制");
    private static final Pattern STACK = pattern("叠加|同享|一起用|同时使用|合并使用");
    private static final Pattern STACK_VALUE = pattern("(?:不可|不能|不支持|禁止|不得|可以|允许|支持|可)(?:(?:与|和).{1,24})?(?:叠加|同享|同时使用|合并使用)"
            + "|(?:叠加|同享)(?:使用)?(?:不允许|不支持|禁止)");
    private static final Pattern TRANSFER = pattern("转赠|转让|转送|转给别人|给别人用");
    private static final Pattern TRANSFER_VALUE = pattern("(?:不可|不能|不允许|不支持|禁止|不得|可以|允许|支持|可)(?:转赠|转让|转送|给别人用|转给他人|他人使用)"
            + "|(?:仅限|只限|仅可)(?:购买者|购券者|本人)使用");
    private static final Pattern PEOPLE = pattern("人数|几人|(?:\\d+|[一二两三四五六七八九十]+)\\s*(?:人|位(?!\\s*(?:数|字|码|验证码|密码|有效数|小数)))|单人|双人|多人");
    private static final Pattern PEOPLE_VALUE = pattern("(?:仅限|只限|至少|最多|限|适用|供|允许)\\s*" + NUMBER + "\\s*(?:人|位)"
            + "|" + NUMBER + "\\s*人(?:使用|可用|适用)|(?:单人|双人|多人)套餐|人数不限|不限人数");
    private static final Pattern CODE = pattern("验证码|短信码|安全码");
    private static final Pattern CODE_QUESTION = pattern("几位|位数|长度|多少位");
    private static final Pattern CODE_VALIDITY_QUESTION = pattern("有效|失效|过期");
    private static final Pattern CODE_VALUE = pattern("(?:验证码|短信码|安全码)(?:长度|位数|为|是|由|包含|采用|\\s)*" + NUMBER + "位"
            + "|" + NUMBER + "位(?:数字|字符)?(?:登录|手机|短信)?验证码");
    private static final Pattern CODE_VALIDITY_VALUE = pattern("(?:验证码|短信码|安全码)(?:的)?(?:有效期|有效期限|有效时间)(?:为|是|在|[：:\\s])*" + TIME
            + "|(?:验证码|短信码|安全码)(?:为|是|在|\\s)*" + TIME + "(?:内有效|有效|后失效|后过期)"
            + "|" + TIME + "(?:内)?(?:登录|手机|短信)?(?:验证码|短信码|安全码)(?:有效|失效|过期)");
    private static final Pattern LOCATION_QUESTION = pattern("(?:在哪里|哪里|哪儿|在哪|何处).{0,8}(?:查看|看|查阅|查找|浏览|阅读|获取|登录|填写)"
            + "|(?:入口|按钮|页面|网址|链接|位置|请求头).{0,8}(?:在哪|哪里|何处|哪个)|哪个(?:页面|入口|按钮|请求头)"
            + "|(?:怎么|怎样|如何)(?:查看|查阅|浏览|阅读|看(?!待))|(?:在哪里|哪儿|何处)[？?。！!\\s]*$");
    private static final Pattern PROCEDURE_QUESTION = pattern("(?:怎么|怎样|如何|下一步|接下来).{0,12}(?:登录|进入(?:账号|账户)|填写|填入|携带|传入|设置|退出|申请|办理|取消|退款|获取验证码|发布|删除|点赞|注册|操作)"
            + "|(?:操作|登录|退款|取消|发布)(?:步骤|流程)|(?:手机号|验证码|登录|退款).{0,8}(?:怎么|怎样|如何)(?:操作|填写|办理|申请|使用)");
    private static final Pattern LOCATION_VALUE = pattern("(?:在|进入|打开|前往|点击).{0,32}(?:页面|页|栏目|列表|详情|请求头)"
            + "|(?:页面|页|栏目|列表|详情|请求头).{0,24}(?:展示|显示|包含|查看|查阅|阅读|填写|携带|输入)");
    private static final Pattern PROCEDURE_VALUE = pattern("(?:点击|点按|点开|打开|进入|填写|填入|输入|选择).{0,32}(?:页面|页|按钮|入口|详情|表单|手机号|验证码|账号|账户|登录|退款|笔记|提交)"
            + "|(?:在|通过).{0,24}(?:请求头|header).{0,12}(?:携带|设置|传入|传递|传送)"
            + "|(?:放在|置于).{0,24}(?:请求头|header)"
            + "|(?:请求头|header).{0,12}(?:携带|设置|传入).{0,16}(?:token|令牌|凭证)");
    private static final Pattern LOGIN = pattern("登录|账号|账户|帐号|手机号|验证码|短信码|安全码|凭证|令牌|(?i:token|authorization|header)");
    private static final Pattern NOTES = pattern("笔记|探店|体验|评论(?!数|量)|评价(?!数|量)");
    private static final Pattern VOUCHER = pattern("普通券|秒杀券|优惠券|券类型|买券|购券|抵扣金额|购买金额");
    private static final Pattern VOUCHER_SUBJECT = pattern("券");
    private static final Pattern VOUCHER_VALIDITY_QUESTION = pattern("(?:优惠券|普通券|秒杀券|券).{0,8}(?:有效期|使用期限|核销期限|过期)");
    private static final Pattern INDEPENDENT_QUESTION = pattern("^(?:退款|退钱|退费|验证码|短信码|安全码|登录|普通券|秒杀券|优惠券|探店笔记|店铺|如何|怎么|怎样|解释|介绍|说明)");
    private static final Pattern NOTE_CONTENT = pattern("笔记|体验|评论(?!数|量)|评价(?!数|量)|达人分享|探店分享");
    private static final Pattern LOGOUT = pattern("退出登录|注销|退出账号|退出账户");
    private static final Pattern TOKEN = pattern("凭证|令牌|请求头|(?i:token|authorization|header)");
    private static final Pattern ORDER = pattern("下单|订单");
    private static final Pattern ORDINARY_VOUCHER = pattern("普通(?:代金)?券");
    private static final Pattern SECKILL_VOUCHER = pattern("秒杀券|秒杀");
    private static final String PAYMENT_AMOUNT = "(?:购买金额|付款(?:金额|数额)|支付(?:金额|数额)|购券(?:金额|价格)|买券价格)";
    private static final String DEDUCTION_AMOUNT = "(?:抵扣(?:金额|数额)|券面金额|面值)";
    private static final Pattern VOUCHER_AMOUNT = pattern(PAYMENT_AMOUNT + "|" + DEDUCTION_AMOUNT);
    private static final Pattern AMOUNT_MEANING = pattern("含义|意思|区别|差别|区分|一回事|是什么|指什么|代表什么|怎么理解|有何不同|有什么不同");
    private static final Pattern PAYMENT_DEFINITION = pattern(PAYMENT_AMOUNT + "(?:是|为|指|表示|代表).{0,8}"
            + "(?:(?:买券|购券|购买(?:优惠券|代金券|券)).{0,12}(?:支付|付款|花费|支出).{0,8}(?:价格|金额|费用|钱)"
            + "|(?:购买|买|购)券.{0,8}(?:所需|需要)(?:的)?(?:金额|费用|价格))");
    private static final Pattern DEDUCTION_DEFINITION = pattern(DEDUCTION_AMOUNT + "(?:是|为|指|表示|代表).{0,12}"
            + "(?:用券|使用(?:优惠券|代金券|券)|核销|消费|结算).{0,16}(?:抵扣|抵用|抵减).{0,10}(?:金额|额度|数额|面额)");
    private static final Pattern SHOP_INFORMATION = pattern("店铺详情|店铺(?:基本|基础)?(?:信息|资料)|页面.{0,8}基本资料");
    private static final Pattern SHOP_INFORMATION_VALUE = pattern("店铺详情|商铺详情|店铺名称|地址|营业时间|人均|评分|商圈|销量|评论数");
    private static final List<Detail> SHOP_FIELDS = List.of(new Detail("人均信息", pattern("人均"), false),
            new Detail("评分信息", pattern("评分"), false), new Detail("地址信息", pattern("地址"), false),
            new Detail("营业时间信息", pattern("营业时间"), false), new Detail("商圈信息", pattern("商圈"), false),
            new Detail("销量信息", pattern("销量"), false), new Detail("评论数量信息", pattern("评论数|评论数量|评价数|评价数量"), false));
    private static final Pattern LOGOUT_STATE_QUESTION = pattern("有效|失效|可用|能用|凭证|令牌|登录态|会话|(?i:token)");
    private static final Pattern LOGOUT_STATE_VALUE = pattern("(?:退出登录|注销|退出账号|退出账户).{0,24}(?:删除|清除|清理).{0,16}(?:登录态|会话|凭证|令牌|(?i:token))"
            + "|(?:退出登录|注销|退出账号|退出账户).{0,24}(?:登录态|会话|凭证|令牌|(?i:token)).{0,12}(?:有效|失效|可用|无效)");
    private static final Pattern NEW_LOGIN_ACTION = pattern("重新登录|再次登录|新(?:的)?(?:凭证|令牌|(?i:token))");
    private static final Pattern ORDER_LIMIT_QUESTION = pattern("(?:下单|购买).{0,4}(?:[二两三四五六七八九十2-9]次|几次|多少次|次数|多次)|重复下单|多次下单|再次下单|再下一单|第二次下单");
    private static final Pattern ORDER_LIMIT_VALUE = pattern("(?:同一|同一个|每个)用户.{0,20}(?:同一张|同张|每张).{0,12}(?:只能|仅能|最多|限|只允许).{0,8}(?:下单|购买).{0,4}" + NUMBER + "(?:次|单)"
            + "|(?:同一|同一个|每个)用户.{0,20}(?:同一张|同张|每张).{0,12}(?:不限(?:下单|购买)次数|(?:下单|购买)次数不限)");
    private static final Pattern RANKING_QUESTION = pattern("(?:笔记|点赞).{0,12}(?:热门|排序|排位|排名)|(?:热门|排序|排位|排名).{0,12}(?:笔记|点赞)");
    private static final Pattern RANKING_VALUE = pattern("(?:笔记|内容).{0,8}(?:按|按照)点赞(?:数|量)?.{0,12}(?:排序|排名|形成热门|进入热门)"
            + "|(?:热门|排名).{0,12}(?:按|根据|取决于).{0,8}点赞(?:数|量)?");
    private static final List<Pattern> GUIDANCE_SUBJECTS = List.of(LOGIN, NOTES, REFUND, CANCEL, TRANSFER, STACK, VALIDITY, PEOPLE);

    /** 仅用于保留原文子题边界，主题线索本身不能证明答案已被来源覆盖。 */
    static List<Integer> scopeSubjects(String question) {
        List<Pattern> subjects = List.of(REFUND, CANCEL, TRANSFER, STACK, PEOPLE, VALIDITY, LOGIN, NOTES, VOUCHER);
        List<Integer> found = new ArrayList<>();
        for (int i = 0; i < subjects.size(); i++) if (subjects.get(i).matcher(question).find()) found.add(i);
        return found;
    }

    static boolean startsIndependentQuestion(String question) { return INDEPENDENT_QUESTION.matcher(question).find(); }

    record Result(List<String> sourceIds, List<String> missingDetails) {
        Result { sourceIds = List.copyOf(sourceIds); missingDetails = List.copyOf(missingDetails); }
    }

    private record Detail(String name, Pattern claim, boolean refundScoped, boolean guidance, Pattern subject,
                          boolean sentenceScoped) {
        Detail(String name, Pattern claim, boolean refundScoped, boolean guidance, Pattern subject) {
            this(name, claim, refundScoped, guidance, subject, false);
        }
        Detail(String name, Pattern claim, boolean refundScoped, boolean guidance) { this(name, claim, refundScoped, guidance, null); }
        Detail(String name, Pattern claim, boolean refundScoped) { this(name, claim, refundScoped, false); }
    }

    static boolean coversSubjects(String question, String evidence) {
        return List.of(REFUND, CANCEL, TRANSFER, STACK, PEOPLE, VALIDITY).stream()
                .noneMatch(subject -> subject.matcher(question).find() && !subject.matcher(evidence).find());
    }

    /** 同主题不等于细节齐全；只保留确实支持至少一项所需细节的来源。 */
    static Result assess(String question, List<AiKnowledgeRetrievalResult.Source> sources) {
        List<Detail> required = new ArrayList<>();
        boolean navigationOnly = NAVIGATION.matcher(question).find() && !VALUE_QUESTION.matcher(question).find();
        if (REFUND.matcher(question).find()) {
            if (TIME_QUESTION.matcher(question).find() && !navigationOnly) required.add(new Detail("到账时限", RECEIPT_TIME, true));
            if (PROMISE_QUESTION.matcher(question).find() && !navigationOnly) required.add(new Detail("到账保证", PROMISE, true));
            if (FEE_QUESTION.matcher(question).find() && !navigationOnly) required.add(new Detail("退款费用", FEE_VALUE, true));
        }
        if (VALIDITY.matcher(question).find() && !CODE.matcher(question).find() && !navigationOnly) {
            required.add(new Detail("有效期限", VALIDITY_VALUE, false, false, VOUCHER_SUBJECT.matcher(question).find() ? VOUCHER_SUBJECT : null));
        }
        if (STACK.matcher(question).find() && !navigationOnly) required.add(new Detail("叠加限制", STACK_VALUE, false));
        if (TRANSFER.matcher(question).find() && !navigationOnly) required.add(new Detail("转赠限制", TRANSFER_VALUE, false));
        if (PEOPLE.matcher(question).find() && !navigationOnly) required.add(new Detail("人数限制", PEOPLE_VALUE, false));
        if (CODE.matcher(question).find() && CODE_QUESTION.matcher(question).find() && !navigationOnly) required.add(new Detail("验证码长度", CODE_VALUE, false));
        if (CODE.matcher(question).find() && CODE_VALIDITY_QUESTION.matcher(question).find() && !navigationOnly) {
            required.add(new Detail("验证码有效期", CODE_VALIDITY_VALUE, false));
        }
        if (CODE.matcher(question).find() && VOUCHER_VALIDITY_QUESTION.matcher(question).find() && !navigationOnly) {
            required.add(new Detail("优惠券有效期限", VALIDITY_VALUE, false, false, VOUCHER_SUBJECT));
        }
        // 关系型规则需要明确的政策事实，不能用登录、订单或笔记的主题介绍替代。
        if (LOGOUT.matcher(question).find() && LOGOUT_STATE_QUESTION.matcher(question).find()) {
            required.add(new Detail("退出后登录态", LOGOUT_STATE_VALUE, false, false, LOGOUT, true));
        }
        if (ORDER.matcher(question).find() && ORDER_LIMIT_QUESTION.matcher(question).find()) {
            required.add(new Detail("单用户下单次数", ORDER_LIMIT_VALUE, false, false, null, true));
        }
        if (RANKING_QUESTION.matcher(question).find()) {
            required.add(new Detail("热门排序规则", RANKING_VALUE, false, false, null, true));
        }
        // 字段名和金额数字不能解释业务含义；两种金额分别核对，允许不同来源各支持一部分。
        if (AMOUNT_MEANING.matcher(question).find()) {
            if (pattern(PAYMENT_AMOUNT).matcher(question).find()) required.add(new Detail("购买金额含义", PAYMENT_DEFINITION, false));
            if (pattern(DEDUCTION_AMOUNT).matcher(question).find()) required.add(new Detail("抵扣金额含义", DEDUCTION_DEFINITION, false));
        }
        // 排序原理、概念区别不等于操作请求；去哪里吃饭也不是询问页面入口。
        Pattern actionSubject = LOGOUT.matcher(question).find() ? LOGOUT : null;
        if (LOCATION_QUESTION.matcher(question).find()) required.add(new Detail("查看位置", LOCATION_VALUE, false, true, actionSubject));
        else if (PROCEDURE_QUESTION.matcher(question).find()) required.add(new Detail("操作步骤", PROCEDURE_VALUE, false, true, actionSubject));
        sources = contributingSources(question, sources, required);
        if (required.isEmpty()) return withFieldCoverage(question, sources,
                sources.stream().map(AiKnowledgeRetrievalResult.Source::documentId).toList(), List.of());
        List<String> missing = new ArrayList<>();
        LinkedHashSet<String> used = new LinkedHashSet<>();
        for (Detail detail : required) {
            List<String> matching = sources.stream().filter(source -> supports(source.content(), detail, question))
                    .map(AiKnowledgeRetrievalResult.Source::documentId).toList();
            if (matching.isEmpty()) missing.add(detail.name());
            else used.addAll(matching);
        }
        return withFieldCoverage(question, sources, List.copyOf(used), missing);
    }

    /** 一份来源只支持部分已明确点名的字段时，保留已知部分，不能把整题标完成。 */
    private static Result withFieldCoverage(String question, List<AiKnowledgeRetrievalResult.Source> sources,
                                           List<String> approvedIds, List<String> missing) {
        if (approvedIds.isEmpty()) return new Result(approvedIds, missing);
        List<String> uncovered = new ArrayList<>(missing);
        for (Detail field : SHOP_FIELDS) {
            if (field.claim().matcher(question).find() && sources.stream()
                    .filter(source -> approvedIds.contains(source.documentId()))
                    .noneMatch(source -> field.claim().matcher(source.content()).find())) uncovered.add(field.name());
        }
        return new Result(approvedIds, uncovered);
    }

    /** 每份来源须贡献已识别的主题或字段；保留多份事实，不按引用数量猜测完整性。 */
    private static List<AiKnowledgeRetrievalResult.Source> contributingSources(
            String question, List<AiKnowledgeRetrievalResult.Source> sources, List<Detail> required) {
        List<Pattern> topics = new ArrayList<>();
        for (Detail field : SHOP_FIELDS) if (field.claim().matcher(question).find()) topics.add(field.claim());
        if (topics.isEmpty() && SHOP_INFORMATION.matcher(question).find()) topics.add(SHOP_INFORMATION_VALUE);
        if (NOTE_CONTENT.matcher(question).find()) topics.add(NOTE_CONTENT);
        if (LOGIN.matcher(question).find()) {
            int before = topics.size();
            if (LOGOUT.matcher(question).find()) topics.add(LOGOUT);
            if (TOKEN.matcher(question).find()) topics.add(TOKEN);
            if (CODE.matcher(question).find()) topics.add(CODE);
            if (topics.size() == before) topics.add(LOGIN);
        }
        if (ORDER.matcher(question).find()) topics.add(ORDER);
        if (VOUCHER_AMOUNT.matcher(question).find()) topics.add(VOUCHER_AMOUNT);
        else if (ORDINARY_VOUCHER.matcher(question).find() && !SECKILL_VOUCHER.matcher(question).find()) topics.add(ORDINARY_VOUCHER);
        else if (SECKILL_VOUCHER.matcher(question).find() && !ORDINARY_VOUCHER.matcher(question).find()) topics.add(SECKILL_VOUCHER);
        else if (VOUCHER_SUBJECT.matcher(question).find()) topics.add(VOUCHER_SUBJECT);
        for (Pattern topic : List.of(REFUND, CANCEL, TRANSFER, STACK, PEOPLE, VALIDITY)) {
            if (topic.matcher(question).find()) topics.add(topic);
        }
        // 未识别的语义不在这里推断，已有细节门禁仍独立执行。
        if (topics.isEmpty()) return sources;
        // 明确事实的支持关系优先于较粗的主题词，不能把“购买次数不限”误删成无下单依据。
        return sources.stream().filter(source -> topics.stream().anyMatch(topic -> topic.matcher(source.content()).find())
                || required.stream().anyMatch(detail -> !detail.guidance() && supports(source.content(), detail, question))).toList();
    }

    private static boolean supports(String evidence, Detail detail, String question) {
        // 新增关系型事实允许同句逗号；退款及既有操作检查继续保持原分句边界。
        String[] clauses = evidence.split(detail.sentenceScoped() ? "[。；;！？!?\\n]" : "[。；;！？!?\\n，,]");
        for (int i = 0; i < clauses.length; i++) {
            String clause = clauses[i].trim();
            if (detail.subject() != null && !detail.subject().matcher(clause).find()) continue;
            if (!detail.guidance() && META_QUESTION.matcher(clause).find()) continue;
            // 导航指引可以回答“在哪看”，但同文档的登录步骤不能证明退款或探店操作。
            // ponytail: 仅核对已识别主题和同句操作线索；完整语义覆盖仍需独立评测。
            if (detail.guidance() && GUIDANCE_SUBJECTS.stream()
                    .anyMatch(subject -> subject.matcher(question).find() && !subject.matcher(clause).find())) continue;
            if (detail.refundScoped() && !REFUND_SUBJECT.matcher(clause).find()
                    && !(i > 0 && REFUND_INTRO.matcher(clauses[i - 1].trim()).matches()
                    && !OTHER_MONEY_SUBJECT.matcher(clause).find())) continue;
            if (detail.refundScoped() && OTHER_MONEY_SUBJECT.matcher(clause).find()) continue;
            var match = detail.claim().matcher(clause);
            while (match.find()) {
                // 同句提到登录不代表“打开笔记”就是登录步骤，操作本身不能明确指向别的主题。
                if (detail.guidance() && GUIDANCE_SUBJECTS.stream().anyMatch(subject ->
                        subject.matcher(question).find() && !subject.matcher(match.group()).find())
                        && GUIDANCE_SUBJECTS.stream().anyMatch(subject ->
                        !subject.matcher(question).find() && subject.matcher(match.group()).find())) continue;
                // 重新登录得到的新凭证不能证明退出前的凭证仍然有效。
                if (detail.subject() == LOGOUT && NEW_LOGIN_ACTION.matcher(match.group()).find()) continue;
                // “客服承诺5分钟回复退款进度”不是款项到账承诺。
                if (!detail.refundScoped() || !OTHER_ACTION.matcher(match.group()).find()) return true;
            }
        }
        return false;
    }

    private static Pattern pattern(String regex) { return Pattern.compile(regex); }
}
