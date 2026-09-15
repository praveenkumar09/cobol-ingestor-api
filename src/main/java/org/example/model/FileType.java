package org.example.model;

public enum FileType {
    COBOL_PROGRAM("cbl", "ILE COBOL Program"),
    COPYBOOK("cpy", "COBOL Copybook"),
    JCL("jcl", "Job Control Language");

    public final String extension;
    public final String label;

    FileType(String extension, String label) {
        this.extension = extension;
        this.label = label;
    }

    public static FileType fromExtension(String ext) {
        for (FileType ft : values()) {
            if (ft.extension.equalsIgnoreCase(ext)) return ft;
        }
        throw new IllegalArgumentException("Unknown extension: " + ext);
    }
}