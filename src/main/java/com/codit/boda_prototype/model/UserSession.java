package com.codit.boda_prototype.model;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 로그인 없이 UUID 쿠키로 사용자를 식별하는 세션 모델.
 * 프로토타입: DB 없이 ConcurrentHashMap에 저장.
 */
@Data
public class UserSession {

    private String sessionId;
    private LocalDateTime createdAt;

    // 파일 분석 상태
    private AnalysisState policyAnalysisState = AnalysisState.NONE;  // 증권
    private AnalysisState termsAnalysisState  = AnalysisState.NONE;  // 약관

    // PDF 원본 파기 후 텍스트만 보관
    private String policyText;
    private String termsText;

    // 보험 조건 팝업 입력값
    private InsuranceCondition condition;

    // 챗봇 대화 히스토리
    private List<ChatMessage> chatHistory = new ArrayList<>();

    // 대시보드 분석 결과 캐시
    private DashboardResult dashboardResult;

    public enum AnalysisState {
        NONE, ANALYZING, DONE, ERROR
    }

    @Data
    public static class InsuranceCondition {
        private String treatmentType;   // 어떤 치료/사고
        private String hospitalUsage;   // 통원/입원/응급실/모르겠어요
        private String treatmentDate;   // 치료 시기 (선택)
        private String estimatedCost;   // 진료비 (선택)
    }

    @Data
    public static class ChatMessage {
        private String role;            // "user" | "assistant"
        private String content;
        private String evidence;        // 근거 약관 텍스트 (assistant일 때)
        private LocalDateTime timestamp;
    }
}
