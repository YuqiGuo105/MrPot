package com.example.MrPot.dto;

import com.example.MrPot.model.ExtractedFile;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class FileContentData {
    private final ExtractedFile file;
    private final String text;
    private final List<String> keywords;
}

