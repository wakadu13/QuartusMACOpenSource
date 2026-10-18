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
import de.neemann.digital.quartus.BdfExporter;

import java.io.File;
import java.io.IOException;

/**
 * Exports a Digital circuit to a Quartus block design file
 */
public class QuartusExport extends BasicCommand {
    private final Argument<String> digFile;
    private final Argument<String> bdfFile;

    /**
     * Creates the Quartus export command
     */
    public QuartusExport() {
        super("quartusExport");
        digFile = addArgument(new Argument<>("dig", "", false));
        bdfFile = addArgument(new Argument<>("bdf", "", true));
    }

    @Override
    protected void execute() throws CLIException {
        try {
            CircuitLoader loader = new CircuitLoader(digFile.get());
            File bdf;
            if (bdfFile.isSet())
                bdf = new File(bdfFile.get());
            else
                bdf = new File(digFile.get().replaceAll("\\.dig$", "") + ".bdf");
            BdfExporter exporter = new BdfExporter(loader.getLibrary());
            exporter.export(loader.getCircuit(), bdf);
            for (File c : exporter.getCreatedFiles())
                System.out.println(c.getPath());
            for (String w : exporter.getWarnings())
                System.err.println("warning: " + w);
        } catch (IOException e) {
            throw new CLIException(Lang.get("cli_errorExportingQuartus"), e);
        }
    }
}
