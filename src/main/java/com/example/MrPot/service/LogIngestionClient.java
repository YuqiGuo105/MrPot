package com.example.MrPot.service;

import com.example.MrPot.model.RagRunEvent;

public interface LogIngestionClient {
    void ingestAsync(RagRunEvent event);
}
