package com.codit.boda_prototype.model;

import lombok.Data;
import java.util.List;

@Data
public class DashboardResult {

    private String insurerName;
    private String estimatedAmount;
    private List<CoverageCard> cards;

    @Data
    public static class CoverageCard {
        private String type;             // 진단비 / 수술비 / 입원비 / 골절재해 / 생활특수 / 치아
        private String insurerTag;
        private String coverageAmount;
        private List<String> exclusions; // 면책 키워드
        private String evidenceText;     // 근거 약관 원문
        private boolean termsUploaded;
    }
}
