package com.chandrakishorsingh.remotecompiler.execution;

import java.util.List;

public enum Language {
    PYTHON("python", "main.py", List.of("python3", "main.py"));

    private final String id;
    private final String filename;
    private final List<String> command;

    Language(String id, String filename, List<String> command) {
        this.id = id;
        this.filename = filename;
        this.command = command;
    }

    public String getId() {
        return id;
    }

    public String getFilename() {
        return filename;
    }

    public List<String> getCommand() {
        return command;
    }

    static Language fromId(String id) {
        for (Language language: values()) {
            if (language.id.equalsIgnoreCase(id)) {
                return language;
            }
        }

        throw new IllegalArgumentException("unsupported language: " + id);
    }
}
