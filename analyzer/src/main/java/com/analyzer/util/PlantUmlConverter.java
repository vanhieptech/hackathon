package com.analyzer.util;

import net.sourceforge.plantuml.SourceStringReader;
import net.sourceforge.plantuml.FileFormat;
import net.sourceforge.plantuml.FileFormatOption;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

public class PlantUmlConverter {

    public static String convertToPlantUml(String umlContent) {
        return "@startuml\n" + umlContent + "\n@enduml";
    }

    public static byte[] generateDiagramImage(String plantUmlContent) throws IOException {
        SourceStringReader reader = new SourceStringReader(plantUmlContent);
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        reader.outputImage(os, new FileFormatOption(FileFormat.SVG));
        return os.toByteArray();
    }
}