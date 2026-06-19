import java.io.*;
import java.nio.file.*;
import java.util.*;

public class Main {

    private static Path currentDirectory =
            Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();

    private static final Set<String> BUILTINS =
            Set.of("echo", "exit", "type", "pwd", "cd");

    // -------------------------------------------------------------------------
    // Entry point / REPL
    // -------------------------------------------------------------------------

    public static void main(String[] args) throws Exception {
        Scanner scanner = new Scanner(System.in);

        while (true) {
            System.out.print("$ ");
            System.out.flush();

            if (!scanner.hasNextLine()) {
                break;
            }

            String input = scanner.nextLine();
            List<String> tokens = parseCommand(input);

            if (tokens.isEmpty()) {
                continue;
            }

            // Split on pipe symbols to get pipeline segments
            List<List<String>> segments = splitOnPipe(tokens);

            if (segments.size() == 1) {
                // Simple command (no pipe) — existing fast path
                executeSimple(segments.get(0), System.out, System.in);
            } else {
                // Pipeline
                executePipeline(segments);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Pipeline execution
    // -------------------------------------------------------------------------

    /**
     * Execute a list of pipeline segments, chaining stdout of segment N into
     * stdin of segment N+1.  The first segment reads from the real stdin; the
     * last segment writes to the real stdout (unless it has its own redirection).
     */
    private static void executePipeline(List<List<String>> segments) throws Exception {
        // We convert every segment's output to a byte[] before feeding the next.
        // This keeps builtins and external commands on equal footing without
        // requiring ProcessBuilder.startPipeline trickery for builtins.

        byte[] stdinBytes = new byte[0]; // first segment reads empty stdin
        InputStream stdinStream = System.in; // only used conceptually; builtins don't read stdin

        for (int i = 0; i < segments.size(); i++) {
            boolean isLast = (i == segments.size() - 1);
            List<String> segment = segments.get(i);

            // Parse redirection for this segment
            Redirection redir = parseRedirection(segment);

            if (redir.commandTokens.isEmpty()) {
                continue;
            }

            String command = redir.commandTokens.get(0);

            if (command.equals("exit")) {
                // exit in a pipeline: just break out
                System.exit(0);
            }

            if (BUILTINS.contains(command)) {
                // Run builtin, capturing output into a byte array
                ByteArrayOutputStream capture = new ByteArrayOutputStream();
                PrintStream captureOut = new PrintStream(capture);

                executeBuiltin(redir, captureOut);

                if (isLast) {
                    // Last segment: write to real stdout (respecting its own redirection)
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
                // External command
                String executablePath = findExecutable(command);

                if (executablePath == null) {
                    try { writeErrorLine(redir, command + ": command not found"); }
                    catch (IOException ioe) { System.err.println(command + ": command not found"); }
                    stdinBytes = new byte[0];
                    continue;
                }

                ProcessBuilder pb = new ProcessBuilder(redir.commandTokens);
                pb.directory(currentDirectory.toFile());

                // Connect stderr
                if (redir.stderrFile != null) {
                    File sf = new File(redir.stderrFile);
                    pb.redirectError(redir.stderrAppend
                            ? ProcessBuilder.Redirect.appendTo(sf)
                            : ProcessBuilder.Redirect.to(sf));
                } else {
                    pb.redirectError(ProcessBuilder.Redirect.INHERIT);
                }

                if (isLast) {
                    // Last segment: stdout goes to file or real stdout
                    if (redir.stdoutFile != null) {
                        File of = new File(redir.stdoutFile);
                        pb.redirectOutput(redir.stdoutAppend
                                ? ProcessBuilder.Redirect.appendTo(of)
                                : ProcessBuilder.Redirect.to(of));
                    } else {
                        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
                    }

                    Process process = pb.start();
                    // Feed accumulated stdin
                    if (stdinBytes.length > 0) {
                        process.getOutputStream().write(stdinBytes);
                    }
                    process.getOutputStream().close();
                    process.waitFor();
                } else {
                    // Middle segment: capture stdout to feed next segment
                    Process process = pb.start();
                    if (stdinBytes.length > 0) {
                        process.getOutputStream().write(stdinBytes);
                    }
                    process.getOutputStream().close();

                    stdinBytes = process.getInputStream().readAllBytes();
                    process.waitFor();
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Simple (non-pipeline) command execution
    // -------------------------------------------------------------------------

    private static void executeSimple(List<String> tokens, PrintStream out, InputStream in)
            throws Exception {
        Redirection redir = parseRedirection(tokens);

        if (redir.commandTokens.isEmpty()) {
            return;
        }

        String command = redir.commandTokens.get(0);

        if (command.equals("exit")) {
            System.exit(0);
        }

        if (BUILTINS.contains(command)) {
            executeBuiltin(redir, System.out);
        } else {
            executeExternal(redir);
        }
    }

    // -------------------------------------------------------------------------
    // Builtin execution (shared by simple and pipeline paths)
    // -------------------------------------------------------------------------

    /**
     * Execute a builtin command.  All stdout output goes to {@code out}.
     * Stderr always goes to System.err (or to redir.stderrFile if set).
     */
    private static void executeBuiltin(Redirection redir, PrintStream captureOut)
            throws Exception {

        String command = redir.commandTokens.get(0);

        // Helper: resolve the actual stdout print stream
        // (file redirection overrides the captureOut passed in for simple commands)
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
                if (redir.commandTokens.size() < 2) {
                    return;
                }
                String pathArg = redir.commandTokens.get(1);
                Path target;

                if (pathArg.equals("~")) {
                    String home = System.getenv("HOME");
                    if (home == null) home = System.getProperty("user.home");
                    target = Paths.get(home);
                } else {
                    Path path = Paths.get(pathArg);
                    target = path.isAbsolute()
                            ? path.normalize()
                            : currentDirectory.resolve(path).normalize();
                }

                if (Files.exists(target) && Files.isDirectory(target)) {
                    currentDirectory = target;
                } else {
                    String error = "cd: " + pathArg + ": No such file or directory";
                    writeErrorLine(redir, error);
                }
            }

            case "type" -> {
                if (redir.commandTokens.size() < 2) {
                    return;
                }
                String cmd = redir.commandTokens.get(1);
                String output;
                if (BUILTINS.contains(cmd)) {
                    output = cmd + " is a shell builtin";
                } else {
                    String executablePath = findExecutable(cmd);
                    output = executablePath != null
                            ? cmd + " is " + executablePath
                            : cmd + ": not found";
                }
                stdoutTarget.println(output);
            }

            default -> System.err.println(command + ": command not found");
        }

        // Flush and close if we opened a file stream
        if (stdoutTarget != captureOut && stdoutTarget != System.out) {
            stdoutTarget.flush();
            stdoutTarget.close();
        }
    }

    // -------------------------------------------------------------------------
    // External command execution
    // -------------------------------------------------------------------------

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

            // stdout
            if (redir.stdoutFile != null) {
                File f = new File(redir.stdoutFile);
                pb.redirectOutput(redir.stdoutAppend
                        ? ProcessBuilder.Redirect.appendTo(f)
                        : ProcessBuilder.Redirect.to(f));
            }

            // stderr
            if (redir.stderrFile != null) {
                File f = new File(redir.stderrFile);
                pb.redirectError(redir.stderrAppend
                        ? ProcessBuilder.Redirect.appendTo(f)
                        : ProcessBuilder.Redirect.to(f));
            }

            Process process = pb.start();

            // Stream stderr to console if not redirected
            if (redir.stderrFile == null) {
                BufferedReader errReader =
                        new BufferedReader(new InputStreamReader(process.getErrorStream()));
                String line;
                while ((line = errReader.readLine()) != null) {
                    System.err.println(line);
                }
            }

            // Stream stdout to console if not redirected
            if (redir.stdoutFile == null) {
                BufferedReader outReader =
                        new BufferedReader(new InputStreamReader(process.getInputStream()));
                String line;
                while ((line = outReader.readLine()) != null) {
                    System.out.println(line);
                }
            }

            process.waitFor();

        } catch (Exception e) {
            System.err.println(command + ": " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Redirection parsing
    // -------------------------------------------------------------------------

    /**
     * Immutable record holding a fully parsed redirection state for one command.
     */
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
            this.stdoutFile = stdoutFile;
            this.stdoutAppend = stdoutAppend;
            this.stderrFile = stderrFile;
            this.stderrAppend = stderrAppend;
        }
    }

    private static Redirection parseRedirection(List<String> tokens) {
        List<String> commandTokens = new ArrayList<>();
        String stdoutFile = null;
        boolean stdoutAppend = false;
        String stderrFile = null;
        boolean stderrAppend = false;

        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);

            if ((token.equals(">>") || token.equals("1>>")) && i + 1 < tokens.size()) {
                stdoutFile = tokens.get(++i);
                stdoutAppend = true;
            } else if ((token.equals(">") || token.equals("1>")) && i + 1 < tokens.size()) {
                stdoutFile = tokens.get(++i);
                stdoutAppend = false;
            } else if (token.equals("2>>") && i + 1 < tokens.size()) {
                stderrFile = tokens.get(++i);
                stderrAppend = true;
            } else if (token.equals("2>") && i + 1 < tokens.size()) {
                stderrFile = tokens.get(++i);
                stderrAppend = false;
            } else {
                commandTokens.add(token);
            }
        }

        // POSIX: redirection operators must create/truncate the target file
        // immediately, even if nothing is ever written to it.
        try {
            if (stdoutFile != null && !stdoutAppend) {
                Files.newOutputStream(
                        Path.of(stdoutFile),
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING
                ).close();
            }
            if (stderrFile != null && !stderrAppend) {
                Files.newOutputStream(
                        Path.of(stderrFile),
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING
                ).close();
            }
        } catch (IOException e) {
            // Ignore: if the file can't be created, subsequent writes will also fail
        }

        return new Redirection(commandTokens, stdoutFile, stdoutAppend, stderrFile, stderrAppend);
    }

    // -------------------------------------------------------------------------
    // Pipe splitting
    // -------------------------------------------------------------------------

    /**
     * Split a flat token list on unquoted {@code |} tokens.
     * The tokenizer already handled quotes, so bare {@code |} in tokens means pipe.
     */
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

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Open a PrintStream for file-redirected stdout, or return the passed-in stream. */
    private static PrintStream resolveStdoutTarget(Redirection redir, PrintStream fallback)
            throws IOException {
        if (redir.stdoutFile != null) {
            OpenOption[] opts = redir.stdoutAppend
                    ? new OpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.APPEND}
                    : new OpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING};
            return new PrintStream(
                    Files.newOutputStream(Path.of(redir.stdoutFile), opts));
        }
        return fallback;
    }

    /** Write an error line either to the redirected stderr file or to System.err. */
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

    /** Write raw bytes to a file (used in pipeline final-segment flushing). */
    private static void writeToFile(String path, byte[] data, boolean append) throws IOException {
        OpenOption[] opts = append
                ? new OpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.APPEND}
                : new OpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING};
        Files.write(Path.of(path), data, opts);
    }

    // -------------------------------------------------------------------------
    // PATH lookup
    // -------------------------------------------------------------------------

    private static String findExecutable(String command) {
        String pathEnv = System.getenv("PATH");

        if (pathEnv == null || pathEnv.isEmpty()) {
            return null;
        }

        for (String dir : pathEnv.split(File.pathSeparator)) {
            Path filePath = Paths.get(dir, command);
            if (Files.exists(filePath)
                    && Files.isRegularFile(filePath)
                    && Files.isExecutable(filePath)) {
                return filePath.toString();
            }
        }

        return null;
    }

    // -------------------------------------------------------------------------
    // Tokenizer / parser
    // -------------------------------------------------------------------------

    /**
     * Parse a raw shell input line into a list of tokens, respecting:
     *   - single quotes  (no escaping inside)
     *   - double quotes  (backslash escapes \", \\, \$, \`)
     *   - backslash outside quotes (escape the next character)
     *   - pipe character |  is emitted as its own token
     */
    private static List<String> parseCommand(String input) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        boolean tokenStarted = false;  // tracks whether we have started a token (for empty-quote case)

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);

            if (inSingleQuote) {
                if (c == '\'') {
                    inSingleQuote = false;
                } else {
                    current.append(c);
                }
            }

            else if (inDoubleQuote) {
                if (c == '"') {
                    inDoubleQuote = false;
                } else if (c == '\\' && i + 1 < input.length()) {
                    char next = input.charAt(i + 1);
                    if (next == '"' || next == '\\' || next == '$' || next == '`') {
                        current.append(next);
                        i++;
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
                    tokenStarted = true;
                }

                else if (c == '"') {
                    inDoubleQuote = true;
                    tokenStarted = true;
                }

                else if (c == '\\') {
                    if (i + 1 < input.length()) {
                        current.append(input.charAt(i + 1));
                        i++;
                        tokenStarted = true;
                    }
                }

                else if (c == '|') {
                    // Flush current token before pipe
                    if (current.length() > 0 || tokenStarted) {
                        tokens.add(current.toString());
                        current.setLength(0);
                        tokenStarted = false;
                    }
                    tokens.add("|");
                }

                else if (Character.isWhitespace(c)) {
                    if (current.length() > 0 || tokenStarted) {
                        tokens.add(current.toString());
                        current.setLength(0);
                        tokenStarted = false;
                    }
                }

                else {
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