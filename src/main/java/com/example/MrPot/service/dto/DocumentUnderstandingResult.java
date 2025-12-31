package com.example.MrPot.service.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class DocumentUnderstandingResult {
    private String text;
    private List<String> keywords;
}
