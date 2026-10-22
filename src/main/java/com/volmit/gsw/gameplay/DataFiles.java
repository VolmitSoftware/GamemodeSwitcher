package com.volmit.gsw.gameplay;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

final class DataFiles {
    private DataFiles() {
    }

    static Path quarantine(Path file, Logger logger, String description, Exception failure) throws IOException {
        Path target = file.resolveSibling(file.getFileName() + ".broken-" + System.currentTimeMillis());
        Files.move(file, target);
        logger.log(Level.SEVERE, "Could not read " + description + " at " + file + "; moved it to " + target.getFileName()
                + " and started with none saved", failure);
        return target;
    }
}
