/*
 * Copyright (c) 2026 QuartusMACOpenSource contributors
 * Use of this source code is governed by the GPL v3 license
 * that can be found in the LICENSE file.
 */
package de.neemann.digital.cli;

import de.neemann.digital.cli.cli.Argument;
import de.neemann.digital.cli.cli.BasicCommand;
import de.neemann.digital.cli.cli.CLIException;
import de.neemann.digital.lang.Lang;
import de.neemann.digital.quartus.QuartusImporter;

import java.io.File;
import java.io.IOException;

/**
 * Converts a Quartus project to Digital circuits
 */
public class QuartusImport extends BasicCommand {
    private final Argument<String> file;
    private final Argument<String> out;

    /**
     * Creates the Quartus import command
     */
    public QuartusImport() {
        super("quartus");
        file = addArgument(new Argument<>("file", "", false));
        out = addArgument(new Argument<>("out", "", true));
    }

    @Override
    protected void execute() throws CLIException {
        try {
            File f = new File(file.get());
            QuartusImporter imp;
            if (out.isSet())
                imp = QuartusImporter.importFile(f, new File(out.get()));
            else
                imp = QuartusImporter.importFile(f);
            for (File c : imp.getCreatedFiles())
                System.out.println(c.getPath());
            for (String w : imp.getWarnings())
                System.err.println("warning: " + w);
        } catch (IOException e) {
            throw new CLIException(Lang.get("cli_errorImportingQuartus"), e);
        }
    }
}
