package com.codit.boda_prototype.service;

import com.codit.boda_prototype.model.DashboardResult;
import com.codit.boda_prototype.model.DashboardResult.CoverageCard;
import com.codit.boda_prototype.model.UserSession;
import com.codit.boda_prototype.model.UserSession.AnalysisState;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 증권/약관 분석 오케스트레이터.
 * @Async 로 비동기 처리 → 분석 중 이탈 후 재접속 시 이어보기 가능.
 */
@Service
public class AnalysisService {

    private final RagService ragService;
    private final boolean mockMode;

    public AnalysisService(RagService ragService,
                           @Value("${app.mock-mode:true}") boolean mockMode) {
        this.ragService = ragService;
        this.mockMode = mockMode;
    }

    /** 보험증권 분석 (비동기) */
    @Async
    public void analyzePolicy(UserSession session) {
        session.setPolicyAnalysisState(AnalysisState.ANALYZING);
        try {
            if (mockMode) {
                Thread.sleep(1200);
            }
            // 실제 서비스: LLM으로 증권에서 보험사명, 증권번호, 보장 요약 추출
            session.setPolicyAnalysisState(AnalysisState.DONE);
        } catch (Exception e) {
            session.setPolicyAnalysisState(AnalysisState.ERROR);
        }
    }

    /** 보험약관 분석 (비동기) — RAG 인덱싱 + 대시보드 카드 생성 */
    @Async
    public void analyzeTerms(UserSession session) {
        session.setTermsAnalysisState(AnalysisState.ANALYZING);
        try {
            if (mockMode) {
                Thread.sleep(2500);
                session.setDashboardResult(mockDashboard());
            } else {
                ragService.indexTerms(session.getSessionId(), session.getTermsText());
                session.setDashboardResult(buildDashboard(session));
            }
            session.setTermsAnalysisState(AnalysisState.DONE);
        } catch (Exception e) {
            session.setTermsAnalysisState(AnalysisState.ERROR);
        }
    }

    private DashboardResult buildDashboard(UserSession session) {
        List<CoverageCard> cards = new ArrayList<>();
        for (String type : new String[]{"진단비", "수술비", "입원비", "골절·재해", "생활·특수", "치아"}) {
            List<String> chunks = ragService.search(session.getSessionId(), type + " 보장 조건 금액");
            if (!chunks.isEmpty()) {
                CoverageCard card = new CoverageCard();
                card.setType(type);
                card.setTermsUploaded(true);
                card.setEvidenceText(chunks.get(0).substring(0, Math.min(200, chunks.get(0).length())));
                cards.add(card);
            }
        }
        DashboardResult result = new DashboardResult();
        result.setCards(cards);
        return result;
    }

    // ── Mock 대시보드 ─────────────────────────────────────────────

    private DashboardResult mockDashboard() {
        DashboardResult result = new DashboardResult();
        result.setInsurerName("삼성생명");
        result.setEstimatedAmount("4,000만원");

        List<CoverageCard> cards = new ArrayList<>();
        cards.add(card("진단비",   "삼성생명", "암: 3,000만원\n뇌혈관: 500만원",
                List.of("선천성 질환", "정신과 질환"),
                "제12조 (진단비) 피보험자가 암으로 진단 확정된 경우 가입금액 전액을 지급합니다."));
        cards.add(card("수술비",   "삼성생명", "1종: 100만원\n2종: 50만원",
                List.of("미용 목적 수술", "치과 수술"),
                "제15조 (수술비) 질병 또는 재해로 인한 수술 시 종류에 따라 지급합니다."));
        cards.add(card("입원비",   "삼성생명", "1일당 5만원 (180일 한도)",
                List.of("입원 1일 이하", "자의 퇴원"),
                "제8조 (입원일당) 계속 입원 2일 이상 시 1일당 50,000원을 지급합니다."));
        result.setCards(cards);
        return result;
    }

    private CoverageCard card(String type, String insurer, String amount,
                               List<String> exclusions, String evidence) {
        CoverageCard c = new CoverageCard();
        c.setType(type); c.setInsurerTag(insurer); c.setCoverageAmount(amount);
        c.setExclusions(exclusions); c.setEvidenceText(evidence); c.setTermsUploaded(true);
        return c;
    }
}
