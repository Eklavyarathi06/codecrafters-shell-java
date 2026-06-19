import java.io.*;
import java.nio.file.*;
import java.util.*;

public class Main {

    private static Path currentDirectory =
            Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();

    private static final Set<String> BUILTINS =
            Set.of("echo", "exit", "type", "pwd", "cd", "jobs");

    // =========================================================================
    // Background Job tracking
    // =========================================================================

    static class Job {
        final int number;
        final Process process;
        final String displayCommand;       // reconstructed from tokens for display

        Job(int number, Process process, String displayCommand) {
            this.number = number;
            this.process = process;
            this.displayCommand = displayCommand;
        }

        /** True once the process has finished. */
        boolean canReap() {
            return !process.isAlive();
        }
    }

    /** Live job table, ordered by insertion. */
    private static final List<Job> jobTable = new ArrayList<>();

    /** Allocate the lowest unused job number (recycled after reaping). */
    private static int allocateJobNumber() {
        Set<Integer> used = new HashSet<>();
        for (Job j : jobTable) used.add(j.number);
        for (int i = 1; ; i++) {
            if (!used.contains(i)) return i;
        }
    }

    /**
     * Call before every prompt.
     * Reaped jobs: print buffered stdout/stderr, then print "[N] done <cmd>",
     * then remove from table (freeing the job number).
     */
    private static void reapCompletedJobs() {
        List<Job> toRemove = new ArrayList<>();
        int size = jobTable.size();
        for (int i = 0; i < size; i++) {
            Job job = jobTable.get(i);
            if (job.canReap()) {
                char sign = ' ';
                if (i == size - 1) sign = '+';
                else if (i == size - 2) sign = '-';
                System.out.println("[" + job.number + "]" + sign + "  Done                 " + job.displayCommand);
                System.out.flush();
                toRemove.add(job);
            }
        }
        jobTable.removeAll(toRemove);
    }

    // =========================================================================
    // Entry point / REPL
    // =========================================================================

    public static void main(String[] args) throws Exception {
        Scanner scanner = new Scanner(System.in);

        while (true) {
            // Reap before every prompt (satisfies "Reap before the next prompt" stage)
            reapCompletedJobs();

            System.out.print("$ ");
            System.out.flush();

            if (!scanner.hasNextLine()) break;

            String input = scanner.nextLine();
            List<String> tokens = parseCommand(input);
            if (tokens.isEmpty()) continue;

            // Detect trailing & for background execution
            boolean background = false;
            if (!tokens.isEmpty() && tokens.get(tokens.size() - 1).equals("&")) {
                background = true;
                tokens = new ArrayList<>(tokens.subList(0, tokens.size() - 1));
                if (tokens.isEmpty()) continue;
            }

            // Split on pipes
            List<List<String>> segments = splitOnPipe(tokens);

            if (background) {
                startBackground(segments);
            } else if (segments.size() == 1) {
                executeSimple(segments.get(0));
            } else {
                executePipeline(segments);
            }
        }
    }

    // =========================================================================
    // Background execution
    // =========================================================================

    private static void startBackground(List<List<String>> segments) throws Exception {
        // Build display string from all segments (joined by " | ")
        StringBuilder displayCmd = new StringBuilder();
        for (int i = 0; i < segments.size(); i++) {
            if (i > 0) displayCmd.append(" | ");
            // Each segment's display excludes redirection operators but keeps command tokens
            Redirection r = parseRedirection(segments.get(i));
            displayCmd.append(String.join(" ", r.commandTokens));
        }

        if (segments.size() == 1) {
            Redirection redir = parseRedirection(segments.get(0));
            if (redir.commandTokens.isEmpty()) return;

            String command = redir.commandTokens.get(0);

            if (command.equals("exit")) { System.exit(0); }

            if (BUILTINS.contains(command)) {
                // Builtins finish instantly — run inline and print output immediately
                ByteArrayOutputStream capture = new ByteArrayOutputStream();
                PrintStream captureOut = new PrintStream(capture);
                executeBuiltin(redir, captureOut);
                byte[] out = capture.toByteArray();
                if (out.length > 0) {
                    System.out.write(out);
                    System.out.flush();
                }
                return;
            }

            String execPath = findExecutable(command);
            if (execPath == null) {
                writeErrorLine(redir, command + ": command not found");
                return;
            }

            ProcessBuilder pb = new ProcessBuilder(redir.commandTokens);
            pb.directory(currentDirectory.toFile());

            // Apply stdout/stderr file redirections if any
            if (redir.stdoutFile != null) {
                File f = new File(redir.stdoutFile);
                pb.redirectOutput(redir.stdoutAppend
                        ? ProcessBuilder.Redirect.appendTo(f)
                        : ProcessBuilder.Redirect.to(f));
            } else {
                pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
            }
            if (redir.stderrFile != null) {
                File f = new File(redir.stderrFile);
                pb.redirectError(redir.stderrAppend
                        ? ProcessBuilder.Redirect.appendTo(f)
                        : ProcessBuilder.Redirect.to(f));
            } else {
                pb.redirectError(ProcessBuilder.Redirect.INHERIT);
            }

            Process process = pb.start();
            int num = allocateJobNumber();
            Job job = new Job(num, process, displayCmd.toString());

            jobTable.add(job);
            System.out.println("[" + num + "] " + process.pid());
            System.out.flush();

        } else {
            // Pipeline in background — run it asynchronously in a thread
            String display = displayCmd.toString();
            List<List<String>> segCopy = segments;
            Thread pipeThread = new Thread(() -> {
                try { executePipeline(segCopy); }
                catch (Exception ignored) {}
            });
            pipeThread.setDaemon(true);

            // Wrap in a fake Process-like job using a dummy approach:
            // We start the pipeline in a thread and track it via a synthetic process
            // For simplicity, just run the pipeline in background thread (no job table entry)
            pipeThread.start();
        }
    }

    // =========================================================================
    // Pipeline execution
    // =========================================================================

    private static void executePipeline(List<List<String>> segments) throws Exception {
        byte[] stdinBytes = new byte[0];

        for (int i = 0; i < segments.size(); i++) {
            boolean isLast = (i == segments.size() - 1);
            Redirection redir = parseRedirection(segments.get(i));

            if (redir.commandTokens.isEmpty()) continue;

            String command = redir.commandTokens.get(0);

            if (command.equals("exit")) { System.exit(0); }

            if (BUILTINS.contains(command)) {
                ByteArrayOutputStream capture = new ByteArrayOutputStream();
                PrintStream captureOut = new PrintStream(capture);
                executeBuiltin(redir, captureOut);

                if (isLast) {
                    byte[] out = capture.toByteArray();
                    if (redir.stdoutFile != null) {
                        writeToFile(redir.stdoutFile, out, redir.stdoutAppend);
                    } else {
                        System.out.write(out);
                        System.out.flush();
                    }
                } else {
                    stdinBytes = capture.toByteArray();
                }
            } else {
                String executablePath = findExecutable(command);
                if (executablePath == null) {
                    try { writeErrorLine(redir, command + ": command not found"); }
                    catch (IOException ioe) { System.err.println(command + ": command not found"); }
                    stdinBytes = new byte[0];
                    continue;
                }

                ProcessBuilder pb = new ProcessBuilder(redir.commandTokens);
                pb.directory(currentDirectory.toFile());

                if (redir.stderrFile != null) {
                    File sf = new File(redir.stderrFile);
                    pb.redirectError(redir.stderrAppend
                            ? ProcessBuilder.Redirect.appendTo(sf)
                            : ProcessBuilder.Redirect.to(sf));
                } else {
                    pb.redirectError(ProcessBuilder.Redirect.INHERIT);
                }

                if (isLast) {
                    if (redir.stdoutFile != null) {
                        File of = new File(redir.stdoutFile);
                        pb.redirectOutput(redir.stdoutAppend
                                ? ProcessBuilder.Redirect.appendTo(of)
                                : ProcessBuilder.Redirect.to(of));
                    } else {
                        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
                    }
                    Process process = pb.start();
                    if (stdinBytes.length > 0) process.getOutputStream().write(stdinBytes);
                    process.getOutputStream().close();
                    process.waitFor();
                } else {
                    Process process = pb.start();
                    if (stdinBytes.length > 0) process.getOutputStream().write(stdinBytes);
                    process.getOutputStream().close();
                    stdinBytes = process.getInputStream().readAllBytes();
                    process.waitFor();
                }
            }
        }
    }

    // =========================================================================
    // Simple (non-pipeline, non-background) command execution
    // =========================================================================

    private static void executeSimple(List<String> tokens) throws Exception {
        Redirection redir = parseRedirection(tokens);
        if (redir.commandTokens.isEmpty()) return;

        String command = redir.commandTokens.get(0);

        if (command.equals("exit")) { System.exit(0); }

        if (BUILTINS.contains(command)) {
            executeBuiltin(redir, System.out);
        } else {
            executeExternal(redir);
        }
    }

    // =========================================================================
    // Builtin execution
    // =========================================================================

    private static void executeBuiltin(Redirection redir, PrintStream captureOut)
            throws Exception {

        String command = redir.commandTokens.get(0);
        PrintStream stdoutTarget = resolveStdoutTarget(redir, captureOut);

        switch (command) {

            case "echo" -> {
                String output = redir.commandTokens.size() > 1
                        ? String.join(" ", redir.commandTokens.subList(1, redir.commandTokens.size()))
                        : "";
                stdoutTarget.println(output);
            }

            case "pwd" -> stdoutTarget.println(currentDirectory.toString());

            case "cd" -> {
                if (redir.commandTokens.size() < 2) return;
                String pathArg = redir.commandTokens.get(1);
                Path target;

                if (pathArg.equals("~")) {
                    String home = System.getenv("HOME");
                    if (home == null) home = System.getProperty("user.home");
                    target = Paths.get(home);
                } else {
                    Path p = Paths.get(pathArg);
                    target = p.isAbsolute()
                            ? p.normalize()
                            : currentDirectory.resolve(p).normalize();
                }

                if (Files.exists(target) && Files.isDirectory(target)) {
                    currentDirectory = target;
                } else {
                    writeErrorLine(redir, "cd: " + pathArg + ": No such file or directory");
                }
            }

            case "type" -> {
                if (redir.commandTokens.size() < 2) return;
                String cmd = redir.commandTokens.get(1);
                String output;
                if (BUILTINS.contains(cmd)) {
                    output = cmd + " is a shell builtin";
                } else {
                    String ep = findExecutable(cmd);
                    output = ep != null ? cmd + " is " + ep : cmd + ": not found";
                }
                stdoutTarget.println(output);
            }

            case "jobs" -> {
                int size = jobTable.size();
                List<Job> toRemove = new ArrayList<>();
                for (int i = 0; i < size; i++) {
                    Job job = jobTable.get(i);
                    char sign = ' ';
                    if (i == size - 1) sign = '+';
                    else if (i == size - 2) sign = '-';
                    
                    if (job.canReap()) {
                        stdoutTarget.println("[" + job.number + "]" + sign + "  Done                 " + job.displayCommand);
                        toRemove.add(job);
                    } else {
                        stdoutTarget.println("[" + job.number + "]" + sign + "  Running                 " + job.displayCommand + " &");
                    }
                }
                jobTable.removeAll(toRemove);
            }

            default -> System.err.println(command + ": command not found");
        }

        if (stdoutTarget != captureOut && stdoutTarget != System.out) {
            stdoutTarget.flush();
            stdoutTarget.close();
        }
    }

    // =========================================================================
    // External command execution
    // =========================================================================

    private static void executeExternal(Redirection redir) throws Exception {
        String command = redir.commandTokens.get(0);
        String executablePath = findExecutable(command);

        if (executablePath == null) {
            writeErrorLine(redir, command + ": command not found");
            return;
        }

        try {
            ProcessBuilder pb = new ProcessBuilder(redir.commandTokens);
            pb.directory(currentDirectory.toFile());

            if (redir.stdoutFile != null) {
                File f = new File(redir.stdoutFile);
                pb.redirectOutput(redir.stdoutAppend
                        ? ProcessBuilder.Redirect.appendTo(f)
                        : ProcessBuilder.Redirect.to(f));
            }
            if (redir.stderrFile != null) {
                File f = new File(redir.stderrFile);
                pb.redirectError(redir.stderrAppend
                        ? ProcessBuilder.Redirect.appendTo(f)
                        : ProcessBuilder.Redirect.to(f));
            }

            Process process = pb.start();

            if (redir.stderrFile == null) {
                BufferedReader errReader =
                        new BufferedReader(new InputStreamReader(process.getErrorStream()));
                String line;
                while ((line = errReader.readLine()) != null) System.err.println(line);
            }
            if (redir.stdoutFile == null) {
                BufferedReader outReader =
                        new BufferedReader(new InputStreamReader(process.getInputStream()));
                String line;
                while ((line = outReader.readLine()) != null) System.out.println(line);
            }

            process.waitFor();

        } catch (Exception e) {
            System.err.println(command + ": " + e.getMessage());
        }
    }

    // =========================================================================
    // Redirection parsing
    // =========================================================================

    static class Redirection {
        final List<String> commandTokens;
        final String stdoutFile;
        final boolean stdoutAppend;
        final String stderrFile;
        final boolean stderrAppend;

        Redirection(List<String> commandTokens,
                    String stdoutFile, boolean stdoutAppend,
                    String stderrFile, boolean stderrAppend) {
            this.commandTokens = commandTokens;
            this.stdoutFile    = stdoutFile;
            this.stdoutAppend  = stdoutAppend;
            this.stderrFile    = stderrFile;
            this.stderrAppend  = stderrAppend;
        }
    }

    private static Redirection parseRedirection(List<String> tokens) {
        List<String> commandTokens = new ArrayList<>();
        String  stdoutFile   = null;
        boolean stdoutAppend = false;
        String  stderrFile   = null;
        boolean stderrAppend = false;

        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);

            if ((token.equals(">>") || token.equals("1>>")) && i + 1 < tokens.size()) {
                stdoutFile   = tokens.get(++i);
                stdoutAppend = true;
            } else if ((token.equals(">") || token.equals("1>")) && i + 1 < tokens.size()) {
                stdoutFile   = tokens.get(++i);
                stdoutAppend = false;
            } else if (token.equals("2>>") && i + 1 < tokens.size()) {
                stderrFile   = tokens.get(++i);
                stderrAppend = true;
            } else if (token.equals("2>") && i + 1 < tokens.size()) {
                stderrFile   = tokens.get(++i);
                stderrAppend = false;
            } else {
                commandTokens.add(token);
            }
        }

        // POSIX: truncating redirections must create/truncate the file immediately,
        // even if nothing is ever written to it.
        try {
            if (stdoutFile != null && !stdoutAppend) {
                Files.newOutputStream(Path.of(stdoutFile),
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING).close();
            }
            if (stderrFile != null && !stderrAppend) {
                Files.newOutputStream(Path.of(stderrFile),
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING).close();
            }
        } catch (IOException ignored) {}

        return new Redirection(commandTokens, stdoutFile, stdoutAppend, stderrFile, stderrAppend);
    }

    // =========================================================================
    // Pipe splitting
    // =========================================================================

    private static List<List<String>> splitOnPipe(List<String> tokens) {
        List<List<String>> segments = new ArrayList<>();
        List<String> current = new ArrayList<>();
        for (String token : tokens) {
            if (token.equals("|")) {
                segments.add(current);
                current = new ArrayList<>();
            } else {
                current.add(token);
            }
        }
        segments.add(current);
        return segments;
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static PrintStream resolveStdoutTarget(Redirection redir, PrintStream fallback)
            throws IOException {
        if (redir.stdoutFile != null) {
            OpenOption[] opts = redir.stdoutAppend
                    ? new OpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.APPEND}
                    : new OpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING};
            return new PrintStream(Files.newOutputStream(Path.of(redir.stdoutFile), opts));
        }
        return fallback;
    }

    private static void writeErrorLine(Redirection redir, String message) throws IOException {
        if (redir.stderrFile != null) {
            OpenOption[] opts = redir.stderrAppend
                    ? new OpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.APPEND}
                    : new OpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING};
            Files.writeString(Path.of(redir.stderrFile), message + System.lineSeparator(), opts);
        } else {
            System.err.println(message);
        }
    }

    private static void writeToFile(String path, byte[] data, boolean append) throws IOException {
        OpenOption[] opts = append
                ? new OpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.APPEND}
                : new OpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING};
        Files.write(Path.of(path), data, opts);
    }

    // =========================================================================
    // PATH lookup
    // =========================================================================

    private static String findExecutable(String command) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null || pathEnv.isEmpty()) return null;

        for (String dir : pathEnv.split(File.pathSeparator)) {
            Path fp = Paths.get(dir, command);
            if (Files.exists(fp) && Files.isRegularFile(fp) && Files.isExecutable(fp)) {
                return fp.toString();
            }
        }
        return null;
    }

    // =========================================================================
    // Tokenizer / parser
    // =========================================================================

    /**
     * Tokenize a shell input line respecting:
     *   single quotes  — no escaping inside
     *   double quotes  — backslash escapes \", \\, \$, \`
     *   backslash      — escapes next character outside quotes
     *   |              — pipe operator, emitted as its own token
     *   &              — background operator, emitted as its own token
     */
    private static List<String> parseCommand(String input) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        boolean tokenStarted  = false;

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);

            if (inSingleQuote) {
                if (c == '\'') { inSingleQuote = false; }
                else           { current.append(c); }
            }

            else if (inDoubleQuote) {
                if (c == '"') {
                    inDoubleQuote = false;
                } else if (c == '\\' && i + 1 < input.length()) {
                    char next = input.charAt(i + 1);
                    if (next == '"' || next == '\\' || next == '$' || next == '`') {
                        current.append(next); i++;
                    } else {
                        current.append('\\');
                    }
                } else {
                    current.append(c);
                }
            }

            else {
                if (c == '\'') {
                    inSingleQuote = true;
                    tokenStarted  = true;
                } else if (c == '"') {
                    inDoubleQuote = true;
                    tokenStarted  = true;
                } else if (c == '\\') {
                    if (i + 1 < input.length()) {
                        current.append(input.charAt(i + 1)); i++;
                        tokenStarted = true;
                    }
                } else if (c == '|' || c == '&') {
                    // Flush current token then emit operator
                    if (current.length() > 0 || tokenStarted) {
                        tokens.add(current.toString());
                        current.setLength(0);
                        tokenStarted = false;
                    }
                    tokens.add(String.valueOf(c));
                } else if (Character.isWhitespace(c)) {
                    if (current.length() > 0 || tokenStarted) {
                        tokens.add(current.toString());
                        current.setLength(0);
                        tokenStarted = false;
                    }
                } else {
                    current.append(c);
                    tokenStarted = true;
                }
            }
        }

        if (current.length() > 0 || tokenStarted) {
            tokens.add(current.toString());
        }
        return tokens;
    }
}