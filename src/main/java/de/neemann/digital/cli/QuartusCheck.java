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
import de.neemann.digital.quartus.ProjectChecker;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Imports and simulates all Quartus projects of a folder and reports the problems
 */
public class QuartusCheck extends BasicCommand {
    private final Argument<String> dir;
    private final Argument<String> report;

    /**
     * Creates the check command
     */
    public QuartusCheck() {
        super("quartusCheck");
        dir = addArgument(new Argument<>("dir", "", false));
        report = addArgument(new Argument<>("report", "", true));
    }

    @Override
    protected void execute() throws CLIException {
        File d = new File(dir.get());
        ProjectChecker checker = new ProjectChecker().check(d);
        String text = checker.createReport(d.isFile() ? d.getParentFile() : d);
        if (report.isSet()) {
            try {
                Files.write(new File(report.get()).toPath(), text.getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new CLIException(Lang.get("cli_errorImportingQuartus"), e);
            }
        } else
            System.out.print(text);
        int errors = checker.count(ProjectChecker.Status.ERROR);
        if (errors > 0)
            throw new CLIException(Lang.get("cli_quartusCheckFailed_N", errors), 1);
    }
}
