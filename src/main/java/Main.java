import java.io.*;
import java.nio.file.*;
import java.util.*;

public class Main {

    private static Path currentDirectory =
            Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();

    private static final Set<String> BUILTINS =
            Set.of("echo", "exit", "type", "pwd", "cd", "jobs");

    static class Job {
        final int number;
        final Process process;
        final String displayCommand;       

        Job(int number, Process process, String displayCommand) {
            this.number = number;
            this.process = process;
            this.displayCommand = displayCommand;
        }

        boolean canReap() {
            return !process.isAlive();
        }
    }

    private static final List<Job> jobTable = new ArrayList<>();

    private static int allocateJobNumber() {
        Set<Integer> used = new HashSet<>();
        for (Job j : jobTable) used.add(j.number);
        for (int i = 1; ; i++) {
            if (!used.contains(i)) return i;
        }
    }

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

    public static void main(String[] args) throws Exception {
        Scanner scanner = new Scanner(System.in);

        while (true) {
            reapCompletedJobs();

            System.out.print("$ ");
            System.out.flush();

            if (!scanner.hasNextLine()) break;

            String input = scanner.nextLine();
            List<String> tokens = parseCommand(input);
            if (tokens.isEmpty()) continue;

            boolean background = false;
            if (!tokens.isEmpty() && tokens.get(tokens.size() - 1).equals("&")) {
                background = true;
                tokens = new ArrayList<>(tokens.subList(0, tokens.size() - 1));
                if (tokens.isEmpty()) continue;
            }

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

    private static void startBackground(List<List<String>> segments) throws Exception {
        StringBuilder displayCmd = new StringBuilder();
        for (int i = 0; i < segments.size(); i++) {
            if (i > 0) displayCmd.append(" | ");
            Redirection r = parseRedirection(segments.get(i));
            displayCmd.append(String.join(" ", r.commandTokens));
        }

        if (segments.size() == 1) {
            Redirection redir = parseRedirection(segments.get(0));
            if (redir.commandTokens.isEmpty()) return;

            String command = redir.commandTokens.get(0);

            if (command.equals("exit")) { System.exit(0); }

            if (BUILTINS.contains(command)) {
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
            String display = displayCmd.toString();
            List<List<String>> segCopy = segments;
            Thread pipeThread = new Thread(() -> {
                try { executePipeline(segCopy); }
                catch (Exception ignored) {}
            });
            pipeThread.setDaemon(true);

            pipeThread.start();
        }
    }

    private static void executePipeline(List<List<String>> segments) throws Exception {
        List<ProcessBuilder> builders = new ArrayList<>();
        byte[] firstBuiltinOutput = null;
        byte[] lastBuiltinOutput = null;
        Redirection lastBuiltinRedir = null;
        boolean lastSegmentIsBuiltin = false;

        for (int i = 0; i < segments.size(); i++) {
            boolean isLast = (i == segments.size() - 1);
            Redirection redir = parseRedirection(segments.get(i));

            if (redir.commandTokens.isEmpty()) continue;

            String command = redir.commandTokens.get(0);
            if (command.equals("exit")) { System.exit(0); }

            if (BUILTINS.contains(command)) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                PrintStream ps = new PrintStream(baos);
                executeBuiltin(redir, ps);
                ps.flush();
                
                if (i == 0) {
                    firstBuiltinOutput = baos.toByteArray();
                }
                if (isLast) {
                    lastSegmentIsBuiltin = true;
                    lastBuiltinOutput = baos.toByteArray();
                    lastBuiltinRedir = redir;
                }
                continue;
            }

            String executablePath = findExecutable(command);
            if (executablePath == null) {
                System.err.println(command + ": command not found");
                return;
            }

            List<String> finalCmd = new ArrayList<>();
            finalCmd.add(executablePath);
            finalCmd.addAll(redir.commandTokens.subList(1, redir.commandTokens.size()));

            ProcessBuilder pb = new ProcessBuilder(finalCmd);
            pb.directory(currentDirectory.toFile());

            if (redir.stderrFile != null) {
                File sf = new File(redir.stderrFile);
                pb.redirectError(redir.stderrAppend
                        ? ProcessBuilder.Redirect.appendTo(sf)
                        : ProcessBuilder.Redirect.to(sf));
            } else {
                pb.redirectError(ProcessBuilder.Redirect.INHERIT);
            }

            if (redir.stdoutFile != null) {
                File of = new File(redir.stdoutFile);
                pb.redirectOutput(redir.stdoutAppend
                        ? ProcessBuilder.Redirect.appendTo(of)
                        : ProcessBuilder.Redirect.to(of));
            }

            builders.add(pb);
        }

        if (!builders.isEmpty()) {
            ProcessBuilder lastPb = builders.get(builders.size() - 1);
            if (lastPb.redirectOutput() == ProcessBuilder.Redirect.PIPE) {
                if (lastSegmentIsBuiltin) {
                    lastPb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
                } else {
                    lastPb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
                }
            }
        }

        if (builders.isEmpty()) {
            if (lastSegmentIsBuiltin && lastBuiltinOutput != null) {
                if (lastBuiltinRedir.stdoutFile != null) {
                    writeToFile(lastBuiltinRedir.stdoutFile, lastBuiltinOutput, lastBuiltinRedir.stdoutAppend);
                } else {
                    System.out.write(lastBuiltinOutput);
                    System.out.flush();
                }
            }
            return;
        }

        try {
            List<Process> processes;
            if (builders.size() == 1) {
                processes = List.of(builders.get(0).start());
            } else {
                processes = ProcessBuilder.startPipeline(builders);
            }
            
            Process first = processes.get(0);
            if (firstBuiltinOutput != null && firstBuiltinOutput.length > 0) {
                first.getOutputStream().write(firstBuiltinOutput);
                first.getOutputStream().flush();
            }
            first.getOutputStream().close();
            
            Process lastProcess = processes.get(processes.size() - 1);
            lastProcess.waitFor();
            
            if (lastSegmentIsBuiltin && lastBuiltinOutput != null) {
                if (lastBuiltinRedir.stdoutFile != null) {
                    writeToFile(lastBuiltinRedir.stdoutFile, lastBuiltinOutput, lastBuiltinRedir.stdoutAppend);
                } else {
                    System.out.write(lastBuiltinOutput);
                    System.out.flush();
                }
            }
            
        } catch (Exception e) {
            System.err.println(e.getMessage());
        }
    }

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