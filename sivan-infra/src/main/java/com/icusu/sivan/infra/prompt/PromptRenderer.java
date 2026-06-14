package com.icusu.sivan.infra.prompt;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

@Component
public class PromptRenderer {
    public String render(String template, Map<String, Object> variables) {
        if (template == null || template.isBlank()) return template;
        String r = template;
        r = r.replace("{{date}}", LocalDate.now().toString());
        r = r.replace("{{datetime}}", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        r = r.replace("{{time}}", LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")));
        if (variables != null) {
            for (var e : variables.entrySet()) {
                r = r.replace("{{" + e.getKey() + "}}", e.getValue() != null ? e.getValue().toString() : "");
            }
        }
        return r;
    }
}
