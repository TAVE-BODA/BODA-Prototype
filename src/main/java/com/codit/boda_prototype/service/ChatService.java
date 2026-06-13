package com.codit.boda_prototype.service;

import com.codit.boda_prototype.model.UserSession;
import com.codit.boda_prototype.model.UserSession.ChatMessage;
import com.codit.boda_prototype.model.UserSession.InsuranceCondition;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 챗봇 답변 생성 — Confidence-based 모델 라우팅
 *
 * 파이프라인:
 *   질문 → RAG 검색 → mini 모델 응답 → Confidence Check
 *     ├ PASS (score >= threshold) → 반환
 *     └ FAIL                     → full 모델로 재생성 후 반환
 *
 * Confidence 판단:
 *   mini 모델에게 자신의 답변에 대한 신뢰도를 0.0~1.0으로 자가 평가하도록 요청.
 *   보험 도메인 특성상 "모르겠다", "확인 필요", "약관 없음" 등의 표현도 감지.
 */
@Service
public class ChatService {

    private final OpenAiChatModel chatModel;
    private final RagService ragService;
    private final boolean mockMode;

    @Value("${app.llm.mini-model:gpt-4o-mini}")
    private String miniModel;

    @Value("${app.llm.full-model:gpt-4o}")
    private String fullModel;

    @Value("${app.llm.confidence-threshold:0.75}")
    private double confidenceThreshold;

    public ChatService(OpenAiChatModel chatModel,
                       RagService ragService,
                       @Value("${app.mock-mode:true}") boolean mockMode) {
        this.chatModel = chatModel;
        this.ragService = ragService;
        this.mockMode = mockMode;
    }

    public record AnswerResult(String answer, String evidence, boolean usedFallback) {}

    // ── 공개 API ─────────────────────────────────────────────────

    /** 자유 입력 질문 처리 */
    public AnswerResult chat(UserSession session, String question) {
        if (mockMode) return mockAnswer(question);
        return routedAnswer(session, question);
    }

    /** 칩 선택 → 초기 답변 */
    public AnswerResult initialAnswer(UserSession session, String chipType) {
        String question = switch (chipType) {
            case "CLAIM"     -> "제 상황에서 보험금 청구가 가능한가요?";
            case "AMOUNT"    -> "보험금을 얼마나 받을 수 있나요?";
            case "DOCUMENTS" -> "보험금 청구에 필요한 서류가 무엇인가요?";
            case "OVERVIEW"  -> "내 보험 전체 보장 내역을 한눈에 보여주세요.";
            default          -> chipType;
        };
        return chat(session, question);
    }

    public void addToHistory(UserSession session, String role, String content, String evidence) {
        ChatMessage msg = new ChatMessage();
        msg.setRole(role);
        msg.setContent(content);
        msg.setEvidence(evidence);
        msg.setTimestamp(LocalDateTime.now());
        session.getChatHistory().add(msg);
    }

    // ── Confidence-based 라우팅 ───────────────────────────────────

    private AnswerResult routedAnswer(UserSession session, String question) {
        // 1. RAG 검색
        List<String> chunks = ragService.search(session.getSessionId(), question);
        String ragContext = String.join("\n\n---\n\n", chunks);
        String systemPrompt = buildSystemPrompt(session, ragContext);
        String evidence = chunks.isEmpty() ? null : "📋 관련 약관 조항:\n" + chunks.get(0);

        // 2. mini 모델로 1차 답변
        String miniAnswer = callModel(miniModel, systemPrompt, question);

        // 3. Confidence Check
        double score = evaluateConfidence(miniAnswer, question, ragContext);

        if (score >= confidenceThreshold) {
            // PASS: mini 답변 그대로 반환
            return new AnswerResult(miniAnswer, evidence, false);
        } else {
            // FAIL: full 모델로 재생성
            String fullAnswer = callModel(fullModel, systemPrompt, question);
            return new AnswerResult(fullAnswer, evidence, true);
        }
    }

    /**
     * mini 모델의 답변 품질을 자가 평가 (0.0 ~ 1.0).
     * 별도 LLM 호출로 판단 — 실제 사용 토큰은 미미함.
     */
    private double evaluateConfidence(String answer, String question, String ragContext) {
        String evalPrompt = String.format("""
                다음은 보험 관련 AI 답변이야. 이 답변의 품질을 0.0~1.0 숫자 하나로만 평가해줘.
                
                평가 기준:
                - 약관/증권 근거가 명확히 포함되어 있으면 높은 점수
                - "모르겠다", "확인이 필요하다", "판단하기 어렵다" 등 불확실한 표현이 있으면 낮은 점수
                - 보험금 금액이나 청구 가능 여부에 대한 구체적 답변이 없으면 낮은 점수
                - 제공된 약관 컨텍스트에 없는 내용을 창작했다면 낮은 점수
                
                [질문]
                %s
                
                [제공된 약관 컨텍스트]
                %s
                
                [답변]
                %s
                
                숫자만 반환 (예: 0.82):
                """, question,
                ragContext.isEmpty() ? "없음" : ragContext.substring(0, Math.min(500, ragContext.length())),
                answer);

        try {
            String scoreStr = callModel(miniModel, "You are a strict quality evaluator.", evalPrompt)
                    .trim().replaceAll("[^0-9.]", "");
            return Double.parseDouble(scoreStr);
        } catch (Exception e) {
            // 파싱 실패 시 안전하게 full 모델 사용
            return 0.0;
        }
    }

    /** 모델명을 동적으로 지정해서 ChatModel 호출 */
    private String callModel(String model, String systemPrompt, String userMessage) {
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(model)
                .temperature(0.2)
                .build();

        return ChatClient.builder(chatModel)
                .build()
                .prompt()
                .options(options)
                .system(systemPrompt)
                .user(userMessage)
                .call()
                .content();
    }

    // ── 시스템 프롬프트 ───────────────────────────────────────────

    private String buildSystemPrompt(UserSession session, String ragContext) {
        var sb = new StringBuilder();
        sb.append("""
                당신은 '보다'입니다. 보험생이 서비스의 AI 보험 분석 어시스턴트예요.
                
                역할:
                - 사용자 보험증권과 약관을 분석해 청구 가능 여부, 예상 금액, 필요 서류를 안내합니다.
                - 친근하고 쉬운 말투로 답변하되 금액/조건은 정확하게 전달합니다.
                - 반드시 제공된 약관/증권 텍스트에 근거해 답변합니다.
                - 근거가 없는 내용은 추측하지 말고 "보험사에 직접 확인해봐요"라고 안내하세요.
                - 중복 보상, 비례 보상 등 복잡한 케이스는 보험사 고객센터 확인을 권장합니다.
                
                답변 형식: 결론 → 근거 → 다음 단계
                """);

        InsuranceCondition cond = session.getCondition();
        if (cond != null) {
            sb.append("\n[사용자 보험 조건]\n");
            sb.append("- 치료/사고 유형: ").append(cond.getTreatmentType()).append("\n");
            sb.append("- 병원 이용 방식: ").append(cond.getHospitalUsage()).append("\n");
            if (cond.getTreatmentDate() != null)
                sb.append("- 치료 시기: ").append(cond.getTreatmentDate()).append("\n");
            if (cond.getEstimatedCost() != null)
                sb.append("- 진료비: ").append(cond.getEstimatedCost()).append("\n");
        }

        if (session.getPolicyText() != null) {
            String snippet = session.getPolicyText().substring(0, Math.min(3000, session.getPolicyText().length()));
            sb.append("\n[보험증권 내용]\n").append(snippet).append("\n");
        }

        if (!ragContext.isBlank())
            sb.append("\n[관련 약관 조항 (RAG 검색 결과)]\n").append(ragContext).append("\n");

        sb.append("\n위 내용에만 근거하여 답변하세요. 모르는 내용은 솔직하게 모른다고 하세요.");
        return sb.toString();
    }

    // ── Mock 응답 ─────────────────────────────────────────────────

    private AnswerResult mockAnswer(String question) {
        String q = question.toLowerCase();
        if (q.contains("청구") || q.contains("가능")) {
            return new AnswerResult(
                "✅ **청구 가능해요!**\n\n" +
                "이번 입원 치료는 실손의료비 보험금 청구가 가능해요.\n\n" +
                "**예상 수령액:** 약 45만원 (본인부담금 80% 기준)\n\n" +
                "**필요 서류:**\n1. 진단서 또는 입퇴원 확인서\n2. 영수증 (세부 내역서 포함)\n3. 보험금 청구서\n\n" +
                "📞 삼성생명 고객센터: 1588-3114",
                "📋 관련 약관 조항:\n제5조 (실손의료비) 피보험자가 질병으로 인하여 입원한 경우 본인부담금의 80%를 지급합니다.",
                false
            );
        } else if (q.contains("얼마") || q.contains("금액")) {
            return new AnswerResult(
                "💰 **예상 수령액: 약 45만원**\n\n" +
                "- 실손의료비: 36만원 (본인부담 45만원의 80%)\n" +
                "- 입원일당: 5만원 × 3일 = 15만원\n\n" +
                "⚠️ 정확한 금액은 청구 심사 후 확정돼요.",
                null, false
            );
        } else if (q.contains("서류") || q.contains("필요")) {
            return new AnswerResult(
                "📄 **필요 서류 목록이에요**\n\n" +
                "1. 보험금 청구서 (보험사 양식)\n2. 진단서 또는 입퇴원 확인서\n" +
                "3. 진료비 영수증 + 세부 내역서\n4. 신분증 사본",
                null, false
            );
        } else {
            return new AnswerResult(
                "보다가 질문을 읽어봤어요 😊\n\n" +
                "조금 더 구체적으로 말씀해주시면 더 정확하게 안내드릴 수 있어요.",
                null, false
            );
        }
    }
}
