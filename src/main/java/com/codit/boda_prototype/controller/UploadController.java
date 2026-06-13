package com.codit.boda_prototype.controller;

import com.codit.boda_prototype.model.UserSession;
import com.codit.boda_prototype.model.UserSession.AnalysisState;
import com.codit.boda_prototype.service.AnalysisService;
import com.codit.boda_prototype.service.PdfExtractService;
import com.codit.boda_prototype.service.SessionStore;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequestMapping("/api/upload")
public class UploadController {

    private final SessionStore sessionStore;
    private final PdfExtractService pdfExtractService;
    private final AnalysisService analysisService;

    public UploadController(SessionStore sessionStore,
                            PdfExtractService pdfExtractService,
                            AnalysisService analysisService) {
        this.sessionStore = sessionStore;
        this.pdfExtractService = pdfExtractService;
        this.analysisService = analysisService;
    }

    /** POST /api/upload/policy — 보험증권 업로드 */
    @PostMapping("/policy")
    public ResponseEntity<Object> policy(
            @RequestParam("file") MultipartFile file,
            @CookieValue("sid") String sid) {

        UserSession session = sessionStore.get(sid);
        if (session == null) return bad("세션이 없어요.");

        PdfExtractService.ExtractResult r = pdfExtractService.extract(file);
        if (!r.success()) return ResponseEntity.badRequest()
                .body(Map.of("error", r.errorMessage(), "code", r.errorCode()));

        session.setPolicyText(r.text());
        session.setPolicyAnalysisState(AnalysisState.ANALYZING);
        analysisService.analyzePolicy(session);

        return ResponseEntity.ok(Map.of("status", "ANALYZING", "message", "증권 분석을 시작했어요!"));
    }

    /** POST /api/upload/terms — 보험약관 업로드 */
    @PostMapping("/terms")
    public ResponseEntity<Object> terms(
            @RequestParam("file") MultipartFile file,
            @CookieValue("sid") String sid) {

        UserSession session = sessionStore.get(sid);
        if (session == null) return bad("세션이 없어요.");

        PdfExtractService.ExtractResult r = pdfExtractService.extract(file);
        if (!r.success()) return ResponseEntity.badRequest()
                .body(Map.of("error", r.errorMessage(), "code", r.errorCode()));

        session.setTermsText(r.text());
        session.setTermsAnalysisState(AnalysisState.ANALYZING);
        analysisService.analyzeTerms(session);

        return ResponseEntity.ok(Map.of("status", "ANALYZING", "message", "약관을 읽는 데 시간이 걸려요. 다른 거 하고 와도 괜찮아요 😊"));
    }

    /** GET /api/upload/status — 분석 상태 폴링 */
    @GetMapping("/status")
    public ResponseEntity<Object> status(@CookieValue("sid") String sid) {
        UserSession session = sessionStore.get(sid);
        if (session == null) return bad("세션이 없어요.");

        return ResponseEntity.ok(Map.of(
            "policyState",  session.getPolicyAnalysisState().name(),
            "termsState",   session.getTermsAnalysisState().name(),
            "hasDashboard", session.getDashboardResult() != null
        ));
    }

    private ResponseEntity<Object> bad(String msg) {
        return ResponseEntity.badRequest().body(Map.of("error", msg));
    }
}
