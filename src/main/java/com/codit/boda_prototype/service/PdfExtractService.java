package com.codit.boda_prototype.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * PDF 텍스트 추출 + 유효성 검사 (4종).
 *
 * PDFBox 3.0 변경사항:
 *   PDDocument.load(InputStream)
 *   → Loader.loadPDF(new RandomAccessReadBuffer(InputStream))
 *
 * 추출 후 원본은 즉시 메모리에서 해제 (개인정보 보호).
 */
@Service
public class PdfExtractService {

    private static final long MAX_SIZE = 20 * 1024 * 1024L; // 20MB

    public record ExtractResult(boolean success, String text, String errorCode, String errorMessage) {}

    public ExtractResult extract(MultipartFile file) {
        // 1. 파일 존재 여부
        if (file == null || file.isEmpty())
            return fail("NO_FILE", "파일을 선택해주세요.");

        // 2. 용량 초과
        if (file.getSize() > MAX_SIZE)
            return fail("SIZE_EXCEEDED", "20MB 이하 파일만 올릴 수 있어요.");

        // 3. PDF 형식 검사
        String name = file.getOriginalFilename();
        String type = file.getContentType();
        if (!"application/pdf".equals(type) && (name == null || !name.toLowerCase().endsWith(".pdf")))
            return fail("NOT_PDF", "PDF 파일만 올릴 수 있어요.");

        // 4. 텍스트 추출
        try (var is = file.getInputStream();
             // PDFBox 3.0: Loader.loadPDF + RandomAccessReadBuffer
             PDDocument doc = Loader.loadPDF(new RandomAccessReadBuffer(is))) {

            String text = new PDFTextStripper().getText(doc);

            // 5. 스캔본 품질 검사 (텍스트가 너무 짧으면 이미지 스캔본)
            if (text == null || text.trim().length() < 200)
                return fail("LOW_QUALITY", "스캔 품질이 낮아요. 보험사 앱에서 다시 받아 올려봐요.");

            // 6. 보험 서류 여부 키워드 검사
            String lower = text.toLowerCase();
            boolean hasKeyword = lower.contains("보험") || lower.contains("증권")
                    || lower.contains("약관") || lower.contains("피보험자")
                    || lower.contains("보장") || lower.contains("보험료");
            if (!hasKeyword)
                return fail("NOT_INSURANCE", "보험증권 또는 약관 파일을 올려봐요.");

            return new ExtractResult(true, text.trim(), null, null);

        } catch (IOException e) {
            return fail("PARSE_ERROR", "파일을 읽을 수 없어요. 다시 시도해주세요.");
        }
    }

    private ExtractResult fail(String code, String msg) {
        return new ExtractResult(false, null, code, msg);
    }
}
