/*
 * CDDL HEADER START
 *
 * The contents of this file are subject to the terms of the
 * Common Development and Distribution License (the "License").
 * You may not use this file except in compliance with the License.
 *
 * See LICENSE.txt included in this distribution for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing Covered Code, include this CDDL HEADER in each
 * file and include the License file at LICENSE.txt.
 * If applicable, add the following below this CDDL HEADER, with the
 * fields enclosed by brackets "[]" replaced with your own identifying
 * information: Portions Copyright [yyyy] [name of copyright owner]
 *
 * CDDL HEADER END
 */

/*
 * Copyright (c) 2013, 2015, Oracle and/or its affiliates. All rights reserved.
 */
package org.opensolaris.opengrok.history;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Scanner;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.opensolaris.opengrok.OpenGrokLogger;
import org.opensolaris.opengrok.util.Executor;

/**
 * Access to Surround SCM repository.
 *
 */
public class SSCMRepository extends Repository {

    private static final long serialVersionUID = 1L;

    /**
     * The property name used to obtain the client command for this repository.
     */
    public static final String CMD_PROPERTY_KEY
            = "org.opensolaris.opengrok.history.sscm";
    /**
     * The command to use to access the repository if none was given explicitly
     */
    public static final String CMD_FALLBACK = "sscm";

    private static final Pattern ANNOTATE_PATTERN = Pattern.compile("^(\\w+)\\s+(\\d+)\\s+.*$");

    private static final String MYSCMSERVERINFO_FILE = ".MySCMServerInfo";
    private static final String BRANCH_PROPERTY = "SCMBranch";
    private static final String REPOSITORY_PROPERTY = "SCMRepository";

    public SSCMRepository() {
        setType("SSCM");
        setDatePattern("M/d/yyyy h:mm a");
        setRemote(true);
    }

    @Override
    boolean fileHasHistory(File file) {
        return true;
    }

    @Override
    boolean hasHistoryForDirectories() {
        return false;
    }

    @Override
    public boolean isWorking() {
        if (working == null) {
            ensureCommand(CMD_PROPERTY_KEY, CMD_FALLBACK);
            working = checkCmd(RepoCommand, "version");
        }
        return working;
    }

    private Properties getProperties(File file) {
        Properties props = new Properties();
        File propFile;
        if (file.isDirectory()) {
            propFile = new File(file, MYSCMSERVERINFO_FILE);
        } else {
            propFile = new File(file.getParent(), MYSCMSERVERINFO_FILE);
        }

        if (propFile.isFile()) {
            try (BufferedReader br = new BufferedReader(new FileReader(propFile))) {
                props.load(br);
            } catch (IOException ex) {
                OpenGrokLogger.getLogger().log(Level.WARNING,
                        "Failed to work with {0} file of {1}: {2}", new Object[]{
                            MYSCMSERVERINFO_FILE,
                            getDirectoryName(), ex.getClass().toString()});
            }
        }

        return props;
    }

    Executor getHistoryLogExecutor(final File file, String sinceRevision) throws IOException {

        List<String> argv = new ArrayList<>();
        ensureCommand(CMD_PROPERTY_KEY, CMD_FALLBACK);
        argv.add(RepoCommand);
        argv.add("history");
        if (file.isDirectory()) {
            argv.add("/");
        } else {
            argv.add(file.getName());
        }
        if (sinceRevision != null && new Scanner(sinceRevision).hasNextInt()) {
            argv.add("-v" + (Integer.parseInt(sinceRevision) + 1) + ":" + Integer.MAX_VALUE);
        }
        argv.add("-w-");

        Properties props = getProperties(file);
        String branch = props.getProperty(BRANCH_PROPERTY);
        if (branch != null && !branch.isEmpty()) {
            argv.add("-b" + branch);
        }
        String repo = props.getProperty(REPOSITORY_PROPERTY);
        if (repo != null && !repo.isEmpty()) {
            argv.add("-p" + repo);
        }

        return new Executor(argv, new File(getDirectoryName()));
    }

    @Override
    History getHistory(File file) throws HistoryException {
        return getHistory(file, null);
    }

    @Override
    History getHistory(File file, String sinceRevision)
            throws HistoryException {
        return new SSCMHistoryParser(this).parse(file, sinceRevision);
    }

    @Override
    InputStream getHistoryGet(String parent, final String basename, String rev) {
        InputStream ret = null;

        File directory = new File(parent);

        Process process = null;
        try {
            final File tmp = File.createTempFile("opengrok", "tmp");
            String tmpName = tmp.getCanonicalPath();

            // cleartool can't get to a previously existing file
            if (tmp.exists() && !tmp.delete()) {
                OpenGrokLogger.getLogger().log(Level.WARNING,
                        "Failed to remove temporary file used by history cache");
            }

            if (!tmp.mkdir()) {
                OpenGrokLogger.getLogger().log(Level.WARNING,
                        "Failed to create temporary directory used by history cache");
            }

            List<String> argv = new ArrayList<>();
            ensureCommand(CMD_PROPERTY_KEY, CMD_FALLBACK);
            argv.add(RepoCommand);
            argv.add("get");
            argv.add(basename);
            argv.add("-d" + tmpName);
            Properties props = getProperties(directory);
            String branch = props.getProperty(BRANCH_PROPERTY);
            if (branch != null && !branch.isEmpty()) {
                argv.add("-b" + branch);
            }
            String repo = props.getProperty(REPOSITORY_PROPERTY);
            if (repo != null && !repo.isEmpty()) {
                argv.add("-p" + repo);
            }
            if (rev != null) {
                argv.add("-v" + rev);
            }
            argv.add("-q");
            argv.add("-tmodify");
            argv.add("-wreplace");
            Executor exec = new Executor(argv, directory);
            int status = exec.exec();

            if (status != 0) {
                OpenGrokLogger.getLogger().log(Level.WARNING,
                        "Failed get revision {2} for: \"{0}\" Exit code: {1}",
                        new Object[]{new File(parent, basename).getAbsolutePath(), String.valueOf(status), rev});
                return null;
            }

            ret = new BufferedInputStream(new FileInputStream(new File(tmp, basename))) {

                @Override
                public void close() throws IOException {
                    super.close();
                    boolean deleteOnExit = false;
                    File tmpFile = new File(tmp, basename);
                    // delete the temporary file on close
                    if (!tmpFile.delete()) {
                        // try on JVM exit
                        deleteOnExit = true;
                        tmpFile.deleteOnExit();
                    }
                    // delete the temporary directory on close
                    if (deleteOnExit || !tmp.delete()) {
                        // try on JVM exit
                        tmp.deleteOnExit();
                    }
                }
            };
        } catch (IOException exp) {
            OpenGrokLogger.getLogger().log(Level.SEVERE,
                    "Failed to get file: " + exp.getClass().toString(), exp);
        } finally {
            // Clean up zombie-processes...
            if (process != null) {
                try {
                    process.exitValue();
                } catch (IllegalThreadStateException exp) {
                    // the process is still running??? just kill it..
                    process.destroy();
                }
            }
        }

        return ret;
    }

    @Override
    boolean fileHasAnnotation(File file) {
        File propFile = new File(file.getParent(), MYSCMSERVERINFO_FILE);
        if (propFile.isFile()) {
            try (BufferedReader br = new BufferedReader(new FileReader(propFile))) {
                // The bottom part is formatted:
                //  file name.ext;date;version;crc;
                String line;
                while ((line = br.readLine()) != null) {
                    String[] parts = line.split(";");
                    // Check if the filename matches
                    if (parts[0].equals(file.getName())) {
                        // Check if the version field is greater than 1
                        //  which indicates that annotate will work
                        if (parts.length > 2 && new Scanner(parts[2]).hasNextInt()) {
                            return Integer.parseInt(parts[2]) > 1;
                        }
                        break;
                    }
                }
            } catch (IOException ex) {
                OpenGrokLogger.getLogger().log(Level.WARNING,
                        "Failed to work with {0} file of {1}: {2}", new Object[]{
                            MYSCMSERVERINFO_FILE,
                            getDirectoryName(), ex.getClass().toString()});
            }
        }
        return false;
    }

    /**
     * Annotate the specified file/revision.
     *
     * @param file file to annotate
     * @param revision revision to annotate
     * @return file annotation
     */
    @Override
    Annotation annotate(File file, String revision) throws IOException {
        ArrayList<String> argv = new ArrayList<>();

        ensureCommand(CMD_PROPERTY_KEY, CMD_FALLBACK);
        argv.add(RepoCommand);
        argv.add("annotate");
        argv.add(file.getName());
        Properties props = getProperties(file);
        String branch = props.getProperty(BRANCH_PROPERTY);
        if (branch != null && !branch.isEmpty()) {
            argv.add("-b" + branch);
        }
        String repo = props.getProperty(REPOSITORY_PROPERTY);
        if (repo != null && !repo.isEmpty()) {
            argv.add("-p" + repo);
        }
        if (revision != null) {
            argv.add("-aV:" + revision);
        }
        Executor exec = new Executor(argv, file.getParentFile());
        int status = exec.exec();

        if (status != 0) {
            OpenGrokLogger.getLogger().log(Level.WARNING,
                    "Failed annotate for: {2} \"{0}\" Exit code: {1}",
                    new Object[]{file.getAbsolutePath(), String.valueOf(status), revision});
        }

        return parseAnnotation(exec.getOutputReader(), file.getName());
    }

    protected Annotation parseAnnotation(Reader input, String fileName)
            throws IOException {
        BufferedReader in = new BufferedReader(input);
        Annotation ret = new Annotation(fileName);
        String line = "";
        int lineno = 0;
        boolean hasStarted = false;
        Matcher matcher = ANNOTATE_PATTERN.matcher(line);
        while ((line = in.readLine()) != null) {
            // For some reason there are empty lines.  Line ends may not be applied correctly.
            if (line.isEmpty()) {
                continue;
            }
            ++lineno;
            matcher.reset(line);
            if (matcher.find()) {
                hasStarted = true;
                String rev = matcher.group(2);
                String author = matcher.group(1);
                ret.addLine(rev, author, true);
            } else if (hasStarted) {
                OpenGrokLogger.getLogger().log(Level.SEVERE,
                        "Error: did not find annotation in line {0}: [{1}]",
                        new Object[]{String.valueOf(lineno), line});
            }
        }
        return ret;
    }

    @Override
    void update() throws IOException {
        File directory = new File(getDirectoryName());
        List<String> argv = new ArrayList<>();

        ensureCommand(CMD_PROPERTY_KEY, CMD_FALLBACK);
        argv.add(RepoCommand);
        argv.add("get");
        argv.add("/");
        Properties props = getProperties(directory);
        String branch = props.getProperty(BRANCH_PROPERTY);
        if (branch != null && !branch.isEmpty()) {
            argv.add("-b" + branch);
        }
        String repo = props.getProperty(REPOSITORY_PROPERTY);
        if (repo != null && !repo.isEmpty()) {
            argv.add("-p" + repo);
        }
        argv.add("-r");
        argv.add("-q");
        argv.add("-tmodify");
        argv.add("-wreplace");

        Executor executor = new Executor(argv, directory);
        if (executor.exec() != 0) {
            throw new IOException(executor.getErrorString());
        }
    }

    @Override
    boolean isRepositoryFor(File file) {
        if (file.isDirectory()) {
            File f = new File(file, MYSCMSERVERINFO_FILE);
            return f.exists() && f.isFile();
        }
        return false;
    }

    @Override
    String determineParent() throws IOException {
        return null;
    }

    @Override
    String determineBranch() {
        return null;
    }
}
