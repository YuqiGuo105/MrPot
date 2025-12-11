package com.example.MrPot.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.function.Function;

/**
 * Web guide function exposed to Spring AI as a tool.
 * The real business logic can be moved to a separate service.
 */
@Component(WebGuideToolDefinition.NAME)
public class WebGuideToolFunction implements Function<WebGuideToolFunction.Request, WebGuideToolFunction.Response> {

    private static final Logger log = LoggerFactory.getLogger(WebGuideToolFunction.class);

    public record Request(
            String userQuestion
            // You can expand fields in future: currentPage, language, etc.
    ) {
    }

    public record Response(
            String currentStep,
            String nextStepHint,
            boolean completed
    ) {
    }

    @Override
    public Response apply(Request request) {
        String currentStep = "INIT";
        String nextStepHint = "Ask the user to reply 'Yes' if they want to start the web guide.";
        boolean completed = false;

        return new Response(currentStep, nextStepHint, completed);
    }
}
