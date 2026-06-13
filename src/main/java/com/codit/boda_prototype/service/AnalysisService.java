package com.codit.boda_prototype.service;

import com.codit.boda_prototype.model.DashboardResult;
import com.codit.boda_prototype.model.DashboardResult.CoverageCard;
import com.codit.boda_prototype.model.UserSession;
import com.codit.boda_prototype.model.UserSession.AnalysisState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 증권/약관 분석 오케스트레이터 — 단계별 시간 측정 포함
 */
@Service
public class AnalysisService {

    private static final Logger log = LoggerFactory.getLogger(AnalysisService.class);

    private final RagService ragService;
    private final boolean mockMode;

    public AnalysisService(RagService ragService,
                           @Value("${app.mock-mode:false}") boolean mockMode) {
        this.ragService = ragService;
        this.mockMode = mockMode;
    }

    /** 보험증권 분석 (비동기) */
    @Async
    public void analyzePolicy(UserSession session) {
        long start = System.currentTimeMillis();
        log.info("[ANALYSIS] 증권 분석 시작 | session={}", session.getSessionId());
        session.setPolicyAnalysisState(AnalysisState.ANALYZING);
        try {
            if (mockMode) {
                Thread.sleep(1200);
            }
            // 실제 서비스: LLM으로 증권에서 보험사명, 증권번호, 보장 요약 추출
            session.setPolicyAnalysisState(AnalysisState.DONE);
            log.info("[ANALYSIS] 증권 분석 완료 | 소요={}ms", System.currentTimeMillis() - start);
        } catch (Exception e) {
            session.setPolicyAnalysisState(AnalysisState.ERROR);
            log.error("[ANALYSIS] 증권 분석 실패 | error={}", e.getMessage());
        }
    }

    /** 보험약관 분석 (비동기) — RAG 인덱싱 + 대시보드 카드 생성 */
    @Async
    public void analyzeTerms(UserSession session) {
        long totalStart = System.currentTimeMillis();
        log.info("[ANALYSIS] ===== 약관 분석 시작 | session={} =====", session.getSessionId());
        session.setTermsAnalysisState(AnalysisState.ANALYZING);

        try {
            if (mockMode) {
                Thread.sleep(2500);
                session.setDashboardResult(mockDashboard());
            } else {

                // ── 1단계: RAG 인덱싱 ──────────────────────────────
                long t1 = System.currentTimeMillis();
                log.info("[ANALYSIS] 1단계 RAG 인덱싱 시작...");
                ragService.indexTerms(session.getSessionId(), session.getTermsText());
                log.info("[ANALYSIS] 1단계 RAG 인덱싱 완료 | 소요={}ms",
                        System.currentTimeMillis() - t1);

                // ── 2단계: 대시보드 카드 생성 ──────────────────────
                long t2 = System.currentTimeMillis();
                log.info("[ANALYSIS] 2단계 대시보드 카드 생성 시작...");
                DashboardResult dashboard = buildDashboard(session);
                session.setDashboardResult(dashboard);
                log.info("[ANALYSIS] 2단계 대시보드 카드 생성 완료 | 카드={}개 | 소요={}ms",
                        dashboard.getCards() != null ? dashboard.getCards().size() : 0,
                        System.currentTimeMillis() - t2);
            }

            session.setTermsAnalysisState(AnalysisState.DONE);
            log.info("[ANALYSIS] ===== 약관 분석 전체 완료 | 총 소요={}ms =====",
                    System.currentTimeMillis() - totalStart);

        } catch (Exception e) {
            session.setTermsAnalysisState(AnalysisState.ERROR);
            log.error("[ANALYSIS] 약관 분석 실패 | 소요={}ms | error={}",
                    System.currentTimeMillis() - totalStart, e.getMessage(), e);
        }
    }

    private DashboardResult buildDashboard(UserSession session) {
        List<CoverageCard> cards = new ArrayList<>();
        String[] coverageTypes = {"진단비", "수술비", "입원비", "골절·재해", "생활·특수", "치아"};

        for (String type : coverageTypes) {
            long t = System.currentTimeMillis();
            List<String> chunks = ragService.search(session.getSessionId(), type + " 보장 조건 금액");
            log.info("[ANALYSIS] 보장 카드 검색 | type={} | 결과={}개 | 소요={}ms",
                    type, chunks.size(), System.currentTimeMillis() - t);

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

    // ── Mock ────────────────────────────────────────────────────

    private DashboardResult mockDashboard() {
        DashboardResult result = new DashboardResult();
        result.setInsurerName("삼성생명");
        result.setEstimatedAmount("4,000만원");

        List<CoverageCard> cards = new ArrayList<>();
        cards.add(card("진단비",  "삼성생명", "암: 3,000만원\n뇌혈관: 500만원",
                List.of("선천성 질환", "정신과 질환"),
                "제12조 (진단비) 피보험자가 암으로 진단 확정된 경우 가입금액 전액을 지급합니다."));
        cards.add(card("수술비",  "삼성생명", "1종: 100만원\n2종: 50만원",
                List.of("미용 목적 수술", "치과 수술"),
                "제15조 (수술비) 질병 또는 재해로 인한 수술 시 종류에 따라 지급합니다."));
        cards.add(card("입원비",  "삼성생명", "1일당 5만원 (180일 한도)",
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
