package com.codit.boda_prototype.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Pattern;

/**
 * RAG 파이프라인
 *
 * 개선사항:
 *   1. 이중 임베딩 제거: embeddingModel.embed() 수동 호출 삭제
 *      → store.add() 한 번만 호출 (SimpleVectorStore 내부 배치 처리 위임)
 *   2. 청크 overlap 제거: 보험 약관은 조항 단위로 자연 분리되므로 overlap 불필요
 *   3. 단계별 시간 측정 로그 추가: 병목 구간 파악
 */
@Service
public class RagService {

    private static final Logger log = LoggerFactory.getLogger(RagService.class);

    private final EmbeddingModel embeddingModel;
    private final boolean mockMode;

    @Value("${app.rag.chunk-size:3000}")
    private int chunkSize;

    @Value("${app.rag.chunk-overlap:0}")
    private int chunkOverlap;

    @Value("${app.rag.top-k:5}")
    private int topK;

    private final Map<String, SimpleVectorStore> storeMap = new HashMap<>();

    public RagService(EmbeddingModel embeddingModel,
                      @Value("${app.mock-mode:false}") boolean mockMode) {
        this.embeddingModel = embeddingModel;
        this.mockMode = mockMode;
    }

    /**
     * 약관 텍스트 인덱싱 — 단계별 시간 측정
     */
    public void indexTerms(String sessionId, String termsText) {
        if (mockMode) return;

        long totalStart = System.currentTimeMillis();
        log.info("[RAG] ===== 약관 인덱싱 시작 | session={} | 텍스트 길이={} =====",
                sessionId, termsText.length());

        // ── 1단계: 청킹 ──────────────────────────────────────────
        long t1 = System.currentTimeMillis();
        List<Document> chunks = chunkByArticle(termsText, sessionId);
        log.info("[RAG] 1단계 청킹 완료 | 청크 수={} | 소요={}ms",
                chunks.size(), System.currentTimeMillis() - t1);

        // ── 2단계: VectorStore 생성 + 임베딩 + 저장 (한 번에) ────
        // 이전: embeddingModel.embed() + store.add() 이중 호출 → 임베딩 2회 발생
        // 개선: store.add() 한 번만 → SimpleVectorStore 내부에서 배치 임베딩 1회 처리
        long t2 = System.currentTimeMillis();
        log.info("[RAG] 2단계 임베딩 시작 | OpenAI API 호출 중...");

        SimpleVectorStore store = SimpleVectorStore.builder(embeddingModel).build();
        store.add(chunks);  // 내부적으로 EmbeddingModel 호출 (배치 처리)

        log.info("[RAG] 2단계 임베딩+저장 완료 | 소요={}ms", System.currentTimeMillis() - t2);

        // ── 3단계: 세션 등록 ─────────────────────────────────────
        long t3 = System.currentTimeMillis();
        storeMap.put(sessionId, store);
        log.info("[RAG] 3단계 세션 등록 완료 | 소요={}ms", System.currentTimeMillis() - t3);

        log.info("[RAG] ===== 인덱싱 전체 완료 | 총 소요={}ms =====",
                System.currentTimeMillis() - totalStart);
    }

    /**
     * 유사 청크 검색 — 검색 시간 측정
     */
    public List<String> search(String sessionId, String query) {
        if (mockMode) {
            return List.of(
                "[mock] 제3조 (보장내용) 피보험자가 질병으로 인해 입원한 경우 1일당 50,000원을 지급합니다.",
                "[mock] 제15조 (면책사항) 정신과 질환, 치과 치료는 보장에서 제외됩니다."
            );
        }

        SimpleVectorStore store = storeMap.get(sessionId);
        if (store == null) {
            log.warn("[RAG] 검색 실패 — 인덱스 없음 | session={}", sessionId);
            return List.of();
        }

        long t = System.currentTimeMillis();
        List<String> results = store.similaritySearch(
                SearchRequest.builder().query(query).topK(topK).build()
        ).stream().map(Document::getText).toList();

        log.info("[RAG] 검색 완료 | 결과={}개 | 소요={}ms", results.size(), System.currentTimeMillis() - t);
        return results;
    }

    public boolean hasIndex(String sessionId) {
        return storeMap.containsKey(sessionId);
    }

    // ── 청킹 ────────────────────────────────────────────────────

    private List<Document> chunkByArticle(String text, String sessionId) {
        List<Document> chunks = new ArrayList<>();
        Pattern p = Pattern.compile("(?=제\\s*\\d+\\s*조)");
        String[] articles = p.split(text);

        for (int i = 0; i < articles.length; i++) {
            String article = articles[i].trim();
            if (article.length() < 30) continue;

            if (article.length() <= chunkSize) {
                chunks.add(makeDoc(article, sessionId, i));
            } else {
                List<String> sub = slidingWindow(article);
                for (int j = 0; j < sub.size(); j++) {
                    chunks.add(makeDoc(sub.get(j), sessionId, i * 1000 + j));
                }
            }
        }

        // 조항 패턴 없는 문서 → 슬라이딩 윈도우
        if (chunks.isEmpty()) {
            List<String> windows = slidingWindow(text);
            for (int i = 0; i < windows.size(); i++) {
                chunks.add(makeDoc(windows.get(i), sessionId, i));
            }
        }

        return chunks;
    }

    private List<String> slidingWindow(String text) {
        List<String> result = new ArrayList<>();
        int step = chunkOverlap == 0 ? chunkSize : (chunkSize - chunkOverlap);
        for (int s = 0; s < text.length(); s += step) {
            result.add(text.substring(s, Math.min(s + chunkSize, text.length())));
        }
        return result;
    }

    private Document makeDoc(String content, String sessionId, int index) {
        return new Document(content, Map.of("sessionId", sessionId, "chunkIndex", index));
    }
}
