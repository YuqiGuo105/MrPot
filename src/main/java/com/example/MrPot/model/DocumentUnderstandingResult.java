package com.example.MrPot.model;

import lombok.*;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentUnderstandingResult {
    private String text;              // key content / extracted key info
    private List<String> keywords;    // keywords for retrieval
    private List<String> queries;     // suggested search queries/questions for KB search
}
