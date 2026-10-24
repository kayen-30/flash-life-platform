package com.hmdp.ai.customer;

import com.hmdp.ai.knowledge.AiKnowledgeRetrievalResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class FaqAnswersTest {
    private final AiKnowledgeRetrievalResult knowledge = new AiKnowledgeRetrievalResult("FAQ", List.of(
            new AiKnowledgeRetrievalResult.Source("voucher-rule", "券说明", "发布来源", "v1", 1,
                    "普通券展示购买金额和抵扣金额；秒杀券还展示库存和活动时间。")), AiKnowledgeRetrievalResult.RetrievalMode.KEYWORD);

    @Test
    void aPublishedButUnrelatedSourceCannotCompleteRefundOrOtherUnsupportedSubjects() {
        for (String question : List.of("退款保证几分钟到账", "退钱什么时候收到", "可以取消订单吗", "能否转赠给别人",
                "是否允许叠加", "两人可以使用吗", "两位顾客可以使用吗", "券的核销有效期是多久")) {
            var answer = FaqAnswers.parse(List.of(question), mapping("voucher-rule"), knowledge);
            assertThat(answer.status()).isEqualTo(CustomerTask.Status.UNKNOWN);
            assertThat(answer.sourceIds()).isEmpty();
            assertThat(answer.items().get(0).reason()).isEqualTo("FAQ_SUBJECT_NOT_COVERED");
        }
    }

    @Test
    void normalizedTaskScopesCannotTurnATopicOnlyRefundSourceIntoACompletedAnswer() throws Exception {
        for (var sample : List.of(List.of("退款保证几分钟到账？", "退款", "退款进度页面支持查询。"),
                List.of("如何申请退款？", "退款", "退款进度可以查询。"),
                List.of("退款进度在哪里看？", "退款进度", "退款支持查询进度。"))) {
            var extracted = new CustomerTask.Extracted(List.of(CustomerTask.Capability.FAQ), null, false,
                    null, null, List.of(), null, List.of(sample.get(1)));
            var task = CustomerTask.parse(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(extracted), sample.get(0), null);
            var answer = FaqAnswers.parse(task.faqQuestions(), mapping("detail"), details(sample.get(2)));
            assertThat(answer.status()).as(sample.toString()).isEqualTo(CustomerTask.Status.UNKNOWN);
            assertThat(answer.sourceIds()).isEmpty();
            assertThat(answer.items().get(0).missingDetails()).isNotEmpty();
        }
    }

    @Test
    void normalizedIndependentScopesRetainTheirOwnSuccessfulAndMissingResults() throws Exception {
        String message = "普通券和秒杀券区别，退款保证几分钟到账？";
        var extracted = new CustomerTask.Extracted(List.of(CustomerTask.Capability.FAQ), null, false,
                null, null, List.of(), null, List.of("普通券和秒杀券区别"));
        var task = CustomerTask.parse(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(extracted), message, null);
        var answers = FaqAnswers.parse(task.faqQuestions(),
                "{\"answers\":[{\"questionIndex\":0,\"sourceIds\":[\"voucher-rule\"]},{\"questionIndex\":1,\"sourceIds\":[\"voucher-rule\"]}]}", knowledge);
        assertThat(answers.status()).isEqualTo(CustomerTask.Status.PARTIAL);
        assertThat(answers.items()).extracting(FaqAnswers.Item::status)
                .containsExactly(CustomerTask.Status.COMPLETED, CustomerTask.Status.UNKNOWN);
        assertThat(answers.questions()).containsExactly("普通券和秒杀券区别", "退款保证几分钟到账");
    }

    @Test
    void codeLifetimeAndOtherTransactionsCannotBorrowRefundTiming() throws Exception {
        String question = "退款多久到账，验证码多久有效？";
        var extracted = new CustomerTask.Extracted(List.of(CustomerTask.Capability.FAQ), null, false,
                null, null, List.of(), null, List.of(question));
        var task = CustomerTask.parse(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(extracted), question, null);
        assertThat(task.faqQuestions()).containsExactly("退款多久到账", "验证码多久有效");
        var body = details("退款款项通常在3天内到账。验证码为6位。");
        var answer = FaqAnswers.parse(task.faqQuestions(),
                "{\"answers\":[{\"questionIndex\":0,\"sourceIds\":[\"detail\"]},{\"questionIndex\":1,\"sourceIds\":[\"detail\"]}]}", body);
        assertThat(answer.status()).isEqualTo(CustomerTask.Status.PARTIAL);
        assertThat(answer.items()).extracting(FaqAnswers.Item::status)
                .containsExactly(CustomerTask.Status.COMPLETED, CustomerTask.Status.UNKNOWN);
        assertThat(answer.items().get(1).missingDetails()).containsExactly("验证码有效期");
        assertThat(FaqAnswers.parse(List.of("验证码多久有效"), mapping("detail"), details("登录验证码有效期为5分钟。" )).status())
                .isEqualTo(CustomerTask.Status.COMPLETED);
        for (String evidence : List.of("退款不收取手续费。钱包充值款项在5分钟内到账。", "退款：钱包充值款项在5分钟内到账。")) {
            var unsupported = FaqAnswers.parse(List.of("退款多久到账"), mapping("detail"), details(evidence));
            assertThat(unsupported.status()).as(evidence).isEqualTo(CustomerTask.Status.UNKNOWN);
            assertThat(unsupported.items().get(0).missingDetails()).containsExactly("到账时限");
        }
        var onlyFee = FaqAnswers.parse(List.of("退款手续费多少，多久能到账"), mapping("detail"),
                details("退款不收取手续费。钱包充值款项在5分钟内到账。"));
        assertThat(onlyFee.status()).isEqualTo(CustomerTask.Status.PARTIAL);
        assertThat(onlyFee.items().get(0).missingDetails()).containsExactly("到账时限");
        var expiry = FaqAnswers.parse(List.of("验证码与优惠券有效期多久"), mapping("detail"),
                details("登录验证码有效期为5分钟。优惠券用于抵扣金额。"));
        assertThat(expiry.status()).isEqualTo(CustomerTask.Status.PARTIAL);
        assertThat(expiry.items().get(0).missingDetails()).containsExactly("优惠券有效期限");
        assertThat(FaqAnswers.parse(List.of("优惠券有效期多久"), mapping("detail"),
                details("登录验证码有效期为5分钟。优惠券用于抵扣金额。")).status()).isEqualTo(CustomerTask.Status.UNKNOWN);
    }

    @Test
    void unrelatedQuestionDoesNotEraseTheAnswerableQuestionOrInheritItsCompletion() {
        var result = FaqAnswers.parse(List.of("普通券和秒杀券区别", "退款保证几分钟到账"),
                "{\"answers\":[{\"questionIndex\":0,\"sourceIds\":[\"voucher-rule\"]},{\"questionIndex\":1,\"sourceIds\":[\"voucher-rule\"]}]}", knowledge);
        assertThat(result.status()).isEqualTo(CustomerTask.Status.PARTIAL);
        assertThat(result.sourceIds()).containsExactly("voucher-rule");
        assertThat(result.items()).extracting(FaqAnswers.Item::status).containsExactly(CustomerTask.Status.COMPLETED, CustomerTask.Status.UNKNOWN);
        assertThat(result.unresolvedNotes()).singleElement().asString().contains("退款保证几分钟到账");
    }

    @Test
    void missingQuestionCannotBeFilledWithAnotherQuestionsEvidence() {
        var result = FaqAnswers.parse(List.of("普通券展示什么", "如何退款"), mapping("voucher-rule"), knowledge);
        assertThat(result.status()).isEqualTo(CustomerTask.Status.PARTIAL);
        assertThat(result.items().get(1).status()).isEqualTo(CustomerTask.Status.FAILED);
        assertThat(result.items().get(1).reason()).isEqualTo("MODEL_PROTOCOL_INVALID");
        assertThat(result.items().get(1).sourceIds()).isEmpty();
        var failed = FaqAnswers.parse(List.of("普通券展示什么", "如何退款"), mapping("fake"), knowledge);
        assertThat(failed.status()).isEqualTo(CustomerTask.Status.FAILED);
        assertThat(failed.reason()).isEqualTo("MODEL_PROTOCOL_INVALID");
    }

    @Test
    void indexDuplicatesOutOfRangeTrailingDataAndNonStringIdsAreRejected() {
        for (String raw : List.of(mapping("voucher-rule") + " trailing",
                "{\"answers\":[{\"questionIndex\":0,\"sourceIds\":[]},{\"questionIndex\":0,\"sourceIds\":[]}]}",
                "{\"answers\":[{\"questionIndex\":5,\"sourceIds\":[]}]}",
                "{\"answers\":[{\"questionIndex\":0,\"sourceIds\":[7]}]}",
                "{\"answers\":[{\"questionIndex\":0,\"sourceIds\":[],\"answer\":\"伪造规则\"}]}")) {
            assertThatThrownBy(() -> FaqAnswers.parse(List.of("普通券展示什么"), raw, knowledge)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> FaqAnswers.parse(List.of("普通券展示什么"),
                "{\"answers\":[],\"answers\":[{\"questionIndex\":0,\"sourceIds\":[\"voucher-rule\"]}]}", knowledge))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(FaqAnswers.parse(List.of("普通券展示什么"), mapping("not-published"), knowledge).status()).isEqualTo(CustomerTask.Status.UNKNOWN);
    }

    @Test
    void newPublishedRefundGuidanceCanBeUsedWhenItActuallyContainsTheRequiredSubject() {
        var refund = new AiKnowledgeRetrievalResult("", List.of(new AiKnowledgeRetrievalResult.Source(
                "refund-guide", "退款进度", "单元测试发布快照", "test-v1", 1, "退款页面展示到账进度。")), AiKnowledgeRetrievalResult.RetrievalMode.KEYWORD);
        assertThat(FaqAnswers.parse(List.of("退款到账进度在哪里看"), mapping("refund-guide"), refund).status())
                .isEqualTo(CustomerTask.Status.COMPLETED);
    }

    @Test
    void verificationCodeLengthDoesNotBecomeAPeopleCondition() {
        var login = new AiKnowledgeRetrievalResult("", List.of(new AiKnowledgeRetrievalResult.Source(
                "login", "登录", "测试发布快照", "test-v1", 1, "手机号验证码登录，登录成功后返回token。")),
                AiKnowledgeRetrievalResult.RetrievalMode.KEYWORD);
        for (String question : List.of("我收到了短信里的六位数字，接下来怎样进入账号？", "短信里的6位验证码怎样填写？")) {
            var missing = FaqAnswers.parse(List.of(question), mapping("login"), login);
            assertThat(missing.status()).isEqualTo(CustomerTask.Status.UNKNOWN);
            assertThat(missing.items().get(0).missingDetails()).containsExactly("操作步骤");
            assertThat(FaqAnswers.parse(List.of(question), mapping("detail"),
                    details("登录时在登录页填写手机号和对应验证码，再点击登录按钮提交。")).status())
                    .isEqualTo(CustomerTask.Status.COMPLETED);
        }
    }

    @Test
    void aRelatedDescriptionCannotCompleteLocationOrProcedureQuestions() {
        for (var sample : List.of(
                List.of("别人到店后的真实体验去哪里看？", "探店笔记按点赞数形成热门内容，可了解真实体验。", "查看位置"),
                List.of("怎样查看用户的探店体验分享？", "探店笔记用于分享消费体验。", "查看位置"),
                List.of("别人到店后的真实体验去哪里看？", "店铺详情包含名称、地址、评分和评论数，可用于回答探店和到店决策问题。", "查看位置"),
                List.of("如何用手机号验证码登录？", "支持手机号验证码登录，成功后返回token。", "操作步骤"),
                List.of("如何申请退款？", "退款进度可以查询；在登录页填写手机号和验证码。", "操作步骤"),
                List.of("怎样查看用户的探店分享？", "在登录页面填写手机号和验证码。", "查看位置"))) {
            var answer = FaqAnswers.parse(List.of(sample.get(0)), mapping("detail"), details(sample.get(1)));
            assertThat(answer.status()).as(sample.toString()).isEqualTo(CustomerTask.Status.UNKNOWN);
            assertThat(answer.sourceIds()).isEmpty();
            assertThat(answer.items().get(0).missingDetails()).containsExactly(sample.get(2));
        }
    }

    @Test
    void verifiedNavigationAndInstructionsDoNotRequireNumericPolicyFacts() {
        for (var sample : List.of(
                List.of("别人到店后的真实体验去哪里看？", "在发现页面点击探店笔记的封面，打开详情阅读体验正文。"),
                List.of("退款到账时间在哪里查看？", "退款到账时间请在退款页面查看。"),
                List.of("登录凭证应该放在哪个请求头？", "登录后在authorization请求头中携带token。"),
                List.of("请求头怎么携带登录凭证？", "登录后在authorization请求头中携带token。"),
                List.of("请求头怎么携带登录凭证？", "登录凭证必须放在authorization请求头。"),
                List.of("请求头怎么携带登录凭证？", "登录凭证通过authorization请求头传递。"),
                List.of("如何申请退款？", "申请退款：进入退款页面，点击退款申请按钮。"),
                List.of("探店笔记怎么排热门？", "探店笔记按点赞数排序形成热门内容。"),
                List.of("我想决定去哪儿吃饭，页面能提供哪些基本资料？", "店铺详情包含地址、营业时间和人均。"))) {
            assertThat(FaqAnswers.parse(List.of(sample.get(0)), mapping("detail"), details(sample.get(1))).status())
                    .as(sample.toString()).isEqualTo(CustomerTask.Status.COMPLETED);
        }
        assertThat(FaqAnswers.parse(List.of("店铺评论数在哪里看？"), mapping("detail"),
                details("店铺详情包含评论数。")).status()).isEqualTo(CustomerTask.Status.COMPLETED);
    }

    @Test
    void navigationCannotFillMissingPolicyFactsInTheSameQuestion() {
        var answer = FaqAnswers.parse(List.of("退款手续费多少，在哪看退款进度？"), mapping("detail"),
                details("退款进度在退款页面查看。"));
        assertThat(answer.status()).isEqualTo(CustomerTask.Status.PARTIAL);
        assertThat(answer.sourceIds()).containsExactly("detail");
        assertThat(answer.items().get(0).missingDetails()).containsExactly("退款费用");
    }

    @Test
    void refundTopicOrUnrelatedNumbersCannotProveSettlementTime() {
        for (String body : List.of("退款页面展示到账进度。", "退款申请可查看进度；登录验证码5分钟有效。",
                "退款进度可咨询客服，客服24小时在线。", "客服承诺5分钟回复退款到账进度。")) {
            var answer = FaqAnswers.parse(List.of("退款多久能到账"), mapping("detail"), details(body));
            assertThat(answer.status()).as(body).isEqualTo(CustomerTask.Status.UNKNOWN);
            assertThat(answer.items().get(0).missingDetails()).containsExactly("到账时限");
            assertThat(answer.sourceIds()).isEmpty();
        }
        var navigation = FaqAnswers.parse(List.of("退款到账时间在哪里查看"), mapping("detail"), details("退款到账时间请在退款页面查看。"));
        assertThat(navigation.status()).isEqualTo(CustomerTask.Status.COMPLETED);
    }

    @Test
    void ordinaryTimingIsPartialForAGuaranteeAndDoesNotLoseTheKnownEvidence() {
        var snapshot = details("退款款项通常在3至5个工作日内原路退回。");
        var partial = FaqAnswers.parse(List.of("退款保证几分钟到账"), mapping("detail"), snapshot);
        assertThat(partial.status()).isEqualTo(CustomerTask.Status.PARTIAL);
        assertThat(partial.sourceIds()).containsExactly("detail");
        assertThat(partial.items().get(0).missingDetails()).containsExactly("到账保证");
        assertThat(partial.unresolvedNotes()).singleElement().asString().contains("缺少到账保证的依据");
        assertThat(FaqAnswers.parse(List.of("退款多久到账"), mapping("detail"), snapshot).status()).isEqualTo(CustomerTask.Status.COMPLETED);
        assertThat(FaqAnswers.parse(List.of("退款最迟多久能到账"), mapping("detail"),
                details("退款款项承诺最迟在5个工作日内到账。")).status()).isEqualTo(CustomerTask.Status.COMPLETED);
    }

    @Test
    void eachRequestedDetailNeedsItsOwnFactAndOnlySupportingSourcesAreApproved() {
        var sources = new AiKnowledgeRetrievalResult("", List.of(
                details("退款款项通常在3个工作日内到账。").sources().get(0),
                new AiKnowledgeRetrievalResult.Source("other", "其他说明", "发布来源", "v1", 1, "退款进度请在页面查看。")),
                AiKnowledgeRetrievalResult.RetrievalMode.KEYWORD);
        var partial = FaqAnswers.parse(List.of("退款手续费多少，多久能到账"),
                "{\"answers\":[{\"questionIndex\":0,\"sourceIds\":[\"detail\",\"other\"]}]}", sources);
        assertThat(partial.status()).isEqualTo(CustomerTask.Status.PARTIAL);
        assertThat(partial.items().get(0).missingDetails()).containsExactly("退款费用");
        assertThat(partial.sourceIds()).containsExactly("detail");
        assertThat(FaqAnswers.parse(List.of("退款手续费多少，多久能到账"), mapping("detail"),
                details("退款款项通常在3个工作日内到账；退款不收取手续费。")).status()).isEqualTo(CustomerTask.Status.COMPLETED);
        var amountIsNotFee = FaqAnswers.parse(List.of("退款手续费多少"), mapping("detail"), details("退款金额为100元；退款不扣本金。"));
        assertThat(amountIsNotFee.status()).isEqualTo(CustomerTask.Status.UNKNOWN);
    }

    @Test
    void aPolicyNavigationSentenceCannotBecomeAnAllowOrDenyDecision() {
        for (var pair : List.of(List.of("优惠券可以叠加吗", "优惠券页面展示是否可叠加", "叠加限制"),
                List.of("券可以转赠给别人吗", "转赠规则请查看券详情", "转赠限制"),
                List.of("两人可以使用吗", "人数限制请查看店铺页面；登录验证码六位", "人数限制"),
                List.of("券的核销有效期多久", "有效期以券详情页面为准", "有效期限"))) {
            var answer = FaqAnswers.parse(List.of(pair.get(0)), mapping("detail"), details(pair.get(1)));
            assertThat(answer.status()).isEqualTo(CustomerTask.Status.UNKNOWN);
            assertThat(answer.items().get(0).missingDetails()).containsExactly(pair.get(2));
        }
        for (var pair : List.of(List.of("优惠券可以叠加吗", "优惠券不可与其他优惠叠加。"),
                List.of("券可以转赠给别人吗", "优惠券仅限本人使用，不允许转赠。"),
                List.of("两人可以使用吗", "双人券仅限两人使用。"),
                List.of("券的核销有效期多久", "优惠券核销有效期为30天。"))) {
            assertThat(FaqAnswers.parse(List.of(pair.get(0)), mapping("detail"), details(pair.get(1))).status())
                    .as(pair.toString()).isEqualTo(CustomerTask.Status.COMPLETED);
        }
    }

    @Test
    void askingCodeLengthIsDifferentFromMentioningAnAlreadyReceivedCode() {
        var absent = FaqAnswers.parse(List.of("登录验证码是几位"), mapping("detail"), details("支持手机号验证码登录。"));
        assertThat(absent.status()).isEqualTo(CustomerTask.Status.UNKNOWN);
        assertThat(absent.items().get(0).missingDetails()).containsExactly("验证码长度");
        assertThat(FaqAnswers.parse(List.of("登录验证码是几位"), mapping("detail"), details("登录使用6位数字验证码。")).status())
                .isEqualTo(CustomerTask.Status.COMPLETED);
    }

    @Test
    void recognisedFieldsAndReviewContentRemoveNonContributingSources() {
        var shop = source("shop", "店铺详情包含地址、营业时间、人均和评分，可辅助消费决策。");
        var note = source("note", "探店笔记记录用户体验，可结合店铺信息辅助消费决策。");
        var candidates = snapshot(shop, note);
        assertThat(FaqAnswers.parse(List.of("人均和评分可以帮助我做什么决定？"), mapping("shop", "note"), candidates).sourceIds())
                .containsExactly("shop");
        assertThat(FaqAnswers.parse(List.of("可以参考评论和达人分享决定去哪家店吗？"), mapping("note", "shop"), candidates).sourceIds())
                .containsExactly("note");
        for (var pair : List.of(List.of("人均和评分可以帮助我做什么决定？", "note"),
                List.of("可以参考评论和达人分享决定去哪家店吗？", "shop"))) {
            var absent = FaqAnswers.parse(List.of(pair.get(0)), mapping(pair.get(1)), candidates);
            assertThat(absent.status()).isEqualTo(CustomerTask.Status.UNKNOWN);
            assertThat(absent.sourceIds()).isEmpty();
            assertThat(absent.items().get(0).reason()).isEqualTo("FAQ_SUBJECT_NOT_COVERED");
        }
    }

    @Test
    void logoutOrderLimitAndRankingNeedExplicitRelationalFacts() {
        for (var sample : List.of(
                List.of("退出登录后原来的凭证还有效吗？", "登录成功后返回token，凭证在请求头中携带。", "退出后登录态"),
                List.of("退出登录后原来的凭证还有效吗？", "退出登录后，重新登录的token有效。", "退出后登录态"),
                List.of("同一个用户对同一张秒杀券能下单两次吗？", "秒杀下单后生成订单，可在订单页面查询。", "单用户下单次数"),
                List.of("探店笔记怎么排热门？", "探店笔记用于分享用户体验。", "热门排序规则"))) {
            var result = FaqAnswers.parse(List.of(sample.get(0)), mapping("detail"), details(sample.get(1)));
            assertThat(result.status()).as(sample.toString()).isEqualTo(CustomerTask.Status.UNKNOWN);
            assertThat(result.items().get(0).missingDetails()).containsExactly(sample.get(2));
            assertThat(result.sourceIds()).isEmpty();
        }
        for (var sample : List.of(
                List.of("退出登录后原来的凭证还有效吗？", "退出登录会删除Redis中的登录态。"),
                List.of("退出登录后原来的凭证还有效吗？", "退出登录后，旧token仍然有效。"),
                List.of("同一个用户对同一张秒杀券能下单两次吗？", "同一用户对同一张秒杀券只能下单一次。"),
                List.of("同一个用户对同一张秒杀券能下单两次吗？", "同一用户对同一张券，最多可下单2次。"),
                List.of("同一个用户对同一张秒杀券能下单两次吗？", "同一用户每张券购买次数不限。"),
                List.of("探店笔记怎么排热门？", "探店笔记按点赞数形成热门内容。"),
                List.of("点赞多的内容更容易进入热门吗？", "热门笔记排名根据点赞数决定。"))) {
            assertThat(FaqAnswers.parse(List.of(sample.get(0)), mapping("detail"), details(sample.get(1))).status())
                    .as(sample.toString()).isEqualTo(CustomerTask.Status.COMPLETED);
        }
    }

    @Test
    void complementarySourcesAndPartialFactsRemainAvailable() {
        var fields = snapshot(source("average", "人均用于了解消费水平。"), source("rating", "评分可辅助选择店铺。"));
        assertThat(FaqAnswers.parse(List.of("人均和评分可以帮助我做什么决定？"), mapping("average", "rating"), fields).sourceIds())
                .containsExactly("average", "rating");
        var oneField = FaqAnswers.parse(List.of("人均和评分可以帮助我做什么决定？"), mapping("average"), fields);
        assertThat(oneField.status()).isEqualTo(CustomerTask.Status.PARTIAL);
        assertThat(oneField.sourceIds()).containsExactly("average");
        assertThat(oneField.items().get(0).missingDetails()).containsExactly("评分信息");
        var refund = snapshot(source("fee", "退款不收取手续费。"), source("time", "退款款项通常在3个工作日内到账。"),
                source("weak", "退款进度请在页面查看。"));
        var complete = FaqAnswers.parse(List.of("退款手续费多少，多久能到账？"), mapping("fee", "time"), refund);
        assertThat(complete.status()).isEqualTo(CustomerTask.Status.COMPLETED);
        assertThat(complete.sourceIds()).containsExactly("time", "fee");
        var partial = FaqAnswers.parse(List.of("退款手续费多少，多久能到账？"), mapping("fee", "weak"), refund);
        assertThat(partial.status()).isEqualTo(CustomerTask.Status.PARTIAL);
        assertThat(partial.sourceIds()).containsExactly("fee");
        assertThat(partial.items().get(0).missingDetails()).containsExactly("到账时限");
        var voucher = snapshot(source("types", "普通券展示购买金额和抵扣金额，秒杀券还展示库存与活动时间。"),
                source("orders", "同一用户对同一张秒杀券只能下单一次。"));
        assertThat(FaqAnswers.parse(List.of("普通券与秒杀券有哪些信息差别？"), mapping("types", "orders"), voucher).sourceIds())
                .containsExactly("types", "orders");
    }

    @Test
    void navigationAndProcedureCannotBorrowAnExplicitlyDifferentAction() {
        var login = source("login", "在登录页面填写手机号和验证码，点击登录按钮；退出登录删除登录态。");
        var logout = source("logout", "退出登录：打开个人中心页面，点击退出登录按钮。");
        var notes = source("notes", "在发现页面点击探店笔记封面；先完成登录再打开笔记详情。");
        var shop = source("shop", "店铺详情包含地址和营业时间。");
        var candidates = snapshot(login, logout, notes, shop);
        var missingLogout = FaqAnswers.parse(List.of("如何退出登录？"), mapping("login"), candidates);
        assertThat(missingLogout.status()).isEqualTo(CustomerTask.Status.UNKNOWN);
        assertThat(missingLogout.items().get(0).missingDetails()).containsExactly("操作步骤");
        assertThat(FaqAnswers.parse(List.of("如何退出登录？"), mapping("login", "logout"), candidates).sourceIds())
                .containsExactly("logout");
        var missingLogin = FaqAnswers.parse(List.of("如何登录？"), mapping("notes"), candidates);
        assertThat(missingLogin.status()).isEqualTo(CustomerTask.Status.UNKNOWN);
        assertThat(missingLogin.items().get(0).missingDetails()).containsExactly("操作步骤");
        var wrongLocation = FaqAnswers.parse(List.of("在哪里查看店铺的营业时间和地址？"), mapping("notes"), candidates);
        assertThat(wrongLocation.status()).isEqualTo(CustomerTask.Status.UNKNOWN);
        assertThat(wrongLocation.items().get(0).missingDetails()).containsExactly("查看位置");
        assertThat(FaqAnswers.parse(List.of("在哪里查看店铺的营业时间和地址？"), mapping("notes", "shop"), candidates).sourceIds())
                .containsExactly("shop");
    }

    @Test
    void amountMeaningCannotBeProvedByFieldNamesNumbersOrSwappedDefinitions() {
        String question = "付款数额和抵扣数额是一回事吗？";
        for (String body : List.of("普通券展示购买金额、抵扣金额和使用规则。", "购买金额80元，抵扣金额100元。",
                "购买金额是用券时可抵扣的金额；抵扣金额是买券时支付的价格。",
                "购买金额不是买券时支付的价格；抵扣金额不是用券时可抵扣的金额。")) {
            var result = FaqAnswers.parse(List.of(question), mapping("detail"), details(body));
            assertThat(result.status()).as(body).isEqualTo(CustomerTask.Status.UNKNOWN);
            assertThat(result.sourceIds()).isEmpty();
            assertThat(result.items().get(0).missingDetails()).containsExactly("购买金额含义", "抵扣金额含义");
        }
        assertThat(FaqAnswers.parse(List.of("普通券展示哪些金额？"), mapping("detail"),
                details("普通券展示购买金额、抵扣金额和使用规则。")).status()).isEqualTo(CustomerTask.Status.COMPLETED);
    }

    @Test
    void amountDefinitionsKeepComplementaryAndPartialEvidence() {
        var payment = source("payment", "购买金额是买券时需要支付的价格。");
        var deduction = source("deduction", "抵扣金额是用券时按使用规则可抵扣的金额。");
        var sources = snapshot(payment, deduction);
        String question = "购买金额与抵扣金额有什么区别？";
        var complete = FaqAnswers.parse(List.of(question), mapping("payment", "deduction"), sources);
        assertThat(complete.status()).isEqualTo(CustomerTask.Status.COMPLETED);
        assertThat(complete.sourceIds()).containsExactly("payment", "deduction");
        var partial = FaqAnswers.parse(List.of(question), mapping("payment"), sources);
        assertThat(partial.status()).isEqualTo(CustomerTask.Status.PARTIAL);
        assertThat(partial.sourceIds()).containsExactly("payment");
        assertThat(partial.items().get(0).missingDetails()).containsExactly("抵扣金额含义");
        assertThat(FaqAnswers.parse(List.of("支付金额是什么？"), mapping("detail"),
                details("支付金额是购买券所需的金额。")).status()).isEqualTo(CustomerTask.Status.COMPLETED);
    }

    private AiKnowledgeRetrievalResult.Source source(String id, String body) {
        return new AiKnowledgeRetrievalResult.Source(id, id, "合成公开对照", "test-v1", 1, body);
    }

    private AiKnowledgeRetrievalResult snapshot(AiKnowledgeRetrievalResult.Source... sources) {
        return new AiKnowledgeRetrievalResult("", List.of(sources), AiKnowledgeRetrievalResult.RetrievalMode.KEYWORD);
    }

    private AiKnowledgeRetrievalResult details(String body) {
        return new AiKnowledgeRetrievalResult("", List.of(new AiKnowledgeRetrievalResult.Source(
                "detail", "细节说明", "测试发布快照", "test-v1", 1, body)), AiKnowledgeRetrievalResult.RetrievalMode.KEYWORD);
    }

    private String mapping(String... ids) {
        return "{\"answers\":[{\"questionIndex\":0,\"sourceIds\":[" + java.util.Arrays.stream(ids)
                .map(id -> "\"" + id + "\"").collect(java.util.stream.Collectors.joining(",")) + "]}]}";
    }
}
