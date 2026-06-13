package com.codit.boda_prototype.service;

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
 * [오프라인] 약관 텍스트 → 조항 단위 청킹 → 임베딩 → SimpleVectorStore 저장
 * [실시간]  질문 → 임베딩 → 코사인 유사도 검색 → 상위 K개 청크 반환
 *
 * Spring AI 1.0.0 변경사항:
 *   - SimpleVectorStore 생성: new SimpleVectorStore(model) → SimpleVectorStore.builder(model).build()
 *   - SearchRequest 생성: SearchRequest.query(q).withTopK(k) → SearchRequest.builder().query(q).topK(k).build()
 *   - 의존성: spring-ai-core + spring-ai-vector-store 명시적 추가 필요
 *
 * 프로토타입: SimpleVectorStore (메모리)
 * 실제 서비스: PgVectorStore (pgvector) 로 교체 예정
 */
@Service
public class RagService {

    private final EmbeddingModel embeddingModel;
    private final boolean mockMode;

    @Value("${app.rag.chunk-size:800}")
    private int chunkSize;

    @Value("${app.rag.chunk-overlap:100}")
    private int chunkOverlap;

    @Value("${app.rag.top-k:5}")
    private int topK;

    // 세션별 독립 VectorStore (sessionId → SimpleVectorStore)
    private final Map<String, SimpleVectorStore> storeMap = new HashMap<>();

    public RagService(EmbeddingModel embeddingModel,
                      @Value("${app.mock-mode:true}") boolean mockMode) {
        this.embeddingModel = embeddingModel;
        this.mockMode = mockMode;
    }

    /**
     * 약관 텍스트를 청킹 후 벡터 저장소에 인덱싱.
     * 사용자가 약관 PDF를 업로드하는 순간 호출됨.
     */
    public void indexTerms(String sessionId, String termsText) {
        if (mockMode) return;

        List<Document> chunks = chunkByArticle(termsText, sessionId);

        // Spring AI 1.0.0: 빌더 패턴으로 생성
        SimpleVectorStore store = SimpleVectorStore.builder(embeddingModel).build();
        store.add(chunks);
        storeMap.put(sessionId, store);
    }

    /**
     * 질문과 유사한 약관 청크를 검색하여 반환.
     * ChatService에서 프롬프트 조립에 사용.
     */
    public List<String> search(String sessionId, String query) {
        if (mockMode) {
            return List.of(
                "[mock] 제3조 (보장내용) 피보험자가 질병으로 인해 입원한 경우 1일당 50,000원을 지급합니다.",
                "[mock] 제15조 (면책사항) 정신과 질환, 치과 치료는 보장에서 제외됩니다."
            );
        }

        SimpleVectorStore store = storeMap.get(sessionId);
        if (store == null) return List.of();

        // Spring AI 1.0.0: SearchRequest 빌더 패턴
        SearchRequest request = SearchRequest.builder()
                .query(query)
                .topK(topK)
                .build();

        return store.similaritySearch(request)
                .stream()
                .map(Document::getText)
                .toList();
    }

    public boolean hasIndex(String sessionId) {
        return storeMap.containsKey(sessionId);
    }

    // ── 청킹: 보험 약관은 "제N조" 단위가 의미 단위로 최적 ─────────

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
                // 하나의 조항이 너무 길면 슬라이딩 윈도우로 추가 분할
                List<String> sub = slidingWindow(article);
                for (int j = 0; j < sub.size(); j++) {
                    chunks.add(makeDoc(sub.get(j), sessionId, i * 1000 + j));
                }
            }
        }

        // 조항 패턴이 없는 문서(보험증권 등) → 전체 슬라이딩 윈도우
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
        for (int s = 0; s < text.length(); s += (chunkSize - chunkOverlap)) {
            result.add(text.substring(s, Math.min(s + chunkSize, text.length())));
        }
        return result;
    }

    private Document makeDoc(String content, String sessionId, int index) {
        return new Document(content, Map.of("sessionId", sessionId, "chunkIndex", index));
    }
}
