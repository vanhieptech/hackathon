package com.example.service;

import net.sourceforge.plantuml.code.TranscoderUtil;
import org.springframework.stereotype.Service;

@Service
public class PlantUMLService {
    public String encodeDiagram(String plantUmlSource) {
        try {
            String encoded = TranscoderUtil.getDefaultTranscoder().encode(plantUmlSource);
            return "http://www.plantuml.com/plantuml/img/" + encoded;
        } catch (Exception e) {
            throw new RuntimeException("Error encoding PlantUML diagram", e);
        }
    }
}
