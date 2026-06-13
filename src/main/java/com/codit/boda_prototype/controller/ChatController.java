package com.codit.boda_prototype.controller;

import com.codit.boda_prototype.model.UserSession;
import com.codit.boda_prototype.model.UserSession.InsuranceCondition;
import com.codit.boda_prototype.service.ChatService;
import com.codit.boda_prototype.service.SessionStore;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final SessionStore sessionStore;
    private final ChatService chatService;

    public ChatController(SessionStore sessionStore, ChatService chatService) {
        this.sessionStore = sessionStore;
        this.chatService = chatService;
    }

    /** POST /api/chat/condition — 보험 조건 팝업 저장 */
    @PostMapping("/condition")
    public ResponseEntity<Object> condition(
            @CookieValue("sid") String sid,
            @RequestBody Map<String, String> body) {

        UserSession session = sessionStore.get(sid);
        if (session == null) return bad("세션 없음");

        InsuranceCondition cond = new InsuranceCondition();
        cond.setTreatmentType(body.get("treatmentType"));
        cond.setHospitalUsage(body.get("hospitalUsage"));
        cond.setTreatmentDate(body.get("treatmentDate"));
        cond.setEstimatedCost(body.get("estimatedCost"));
        session.setCondition(cond);

        return ResponseEntity.ok(Map.of("status", "ok", "message", "알려주신 내용을 확인했어요!"));
    }

    /** POST /api/chat/message — 챗봇 질문 */
    @PostMapping("/message")
    public ResponseEntity<Object> message(
            @CookieValue("sid") String sid,
            @RequestBody Map<String, String> body) {

        UserSession session = sessionStore.get(sid);
        if (session == null) return bad("세션 없음");

        String question = body.get("message");
        String chipType = body.get("chipType");

        if (question == null || question.isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "질문을 입력해주세요."));

        chatService.addToHistory(session, "user", question, null);

        ChatService.AnswerResult result = (chipType != null)
                ? chatService.initialAnswer(session, chipType)
                : chatService.chat(session, question);

        chatService.addToHistory(session, "assistant", result.answer(), result.evidence());

        return ResponseEntity.ok(Map.of(
            "answer",       result.answer(),
            "evidence",     result.evidence() != null ? result.evidence() : "",
            "usedFallback", result.usedFallback()
        ));
    }

    /** GET /api/chat/history — 대화 히스토리 */
    @GetMapping("/history")
    public ResponseEntity<Object> history(@CookieValue("sid") String sid) {
        UserSession session = sessionStore.get(sid);
        if (session == null) return bad("세션 없음");
        return ResponseEntity.ok(session.getChatHistory());
    }

    private ResponseEntity<Object> bad(String msg) {
        return ResponseEntity.badRequest().body(Map.of("error", msg));
    }
}
