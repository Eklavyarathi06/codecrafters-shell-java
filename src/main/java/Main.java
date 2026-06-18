import java.io.*;
import java.nio.file.*;
import java.util.*;

public class Main {

    private static Path currentDirectory =
            Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();

    private static final Set<String> BUILTINS =
            Set.of("echo", "exit", "type", "pwd", "cd");

    public static void main(String[] args) throws Exception {
        Scanner scanner = new Scanner(System.in);

        while (true) {
            System.out.print("$ ");

            if (!scanner.hasNextLine()) {
                break;
            }

            String input = scanner.nextLine();
            List<String> tokens = parseCommand(input);

            if (tokens.isEmpty()) {
                continue;
            }

            String stdoutFile = null;
            String stderrFile = null;

            List<String> commandTokens = new ArrayList<>();

            for (int i = 0; i < tokens.size(); i++) {
                String token = tokens.get(i);

                if ((token.equals(">") || token.equals("1>")) && i + 1 < tokens.size()) {
                    stdoutFile = tokens.get(i + 1);
                    i++;
                } else if (token.equals("2>") && i + 1 < tokens.size()) {
                    stderrFile = tokens.get(i + 1);
                    i++;
                } else {
                    commandTokens.add(token);
                }
            }
            if (stderrFile != null) {
    Files.writeString(
            Path.of(stderrFile),
            "",
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING);
}

            if (commandTokens.isEmpty()) {
                continue;
            }

            String command = commandTokens.get(0);

            if (command.equals("exit")) {
                break;
            }

            else if (command.equals("echo")) {
                String output = "";

                if (commandTokens.size() > 1) {
                    output = String.join(" ", commandTokens.subList(1, commandTokens.size()));
                }

                if (stdoutFile != null) {
                    Files.writeString(
                            Path.of(stdoutFile),
                            output + System.lineSeparator(),
                            StandardOpenOption.CREATE,
                            StandardOpenOption.TRUNCATE_EXISTING);
                } else {
                    System.out.println(output);
                }
            }

            else if (command.equals("pwd")) {
                String output = currentDirectory.toString();

                if (stdoutFile != null) {
                    Files.writeString(
                            Path.of(stdoutFile),
                            output + System.lineSeparator(),
                            StandardOpenOption.CREATE,
                            StandardOpenOption.TRUNCATE_EXISTING);
                } else {
                    System.out.println(output);
                }
            }

            else if (command.equals("cd")) {
                if (commandTokens.size() < 2) {
                    continue;
                }

                String pathArg = commandTokens.get(1);
                Path target;

                if (pathArg.equals("~")) {
                    target = Paths.get(System.getenv("HOME"));
                } else {
                    Path path = Paths.get(pathArg);

                    if (path.isAbsolute()) {
                        target = path.normalize();
                    } else {
                        target = currentDirectory.resolve(path).normalize();
                    }
                }

                if (Files.exists(target) && Files.isDirectory(target)) {
                    currentDirectory = target;
                } else {
                    String error = "cd: " + pathArg + ": No such file or directory";

                    if (stderrFile != null) {
                        Files.writeString(
                                Path.of(stderrFile),
                                error + System.lineSeparator(),
                                StandardOpenOption.CREATE,
                                StandardOpenOption.TRUNCATE_EXISTING);
                    } else {
                        System.out.println(error);
                    }
                }
            }

            else if (command.equals("type")) {
                if (commandTokens.size() < 2) {
                    continue;
                }

                String cmd = commandTokens.get(1);
                String output;

                if (BUILTINS.contains(cmd)) {
                    output = cmd + " is a shell builtin";
                } else {
                    String executablePath = findExecutable(cmd);

                    if (executablePath != null) {
                        output = cmd + " is " + executablePath;
                    } else {
                        output = cmd + ": not found";
                    }
                }

                if (stdoutFile != null) {
                    Files.writeString(
                            Path.of(stdoutFile),
                            output + System.lineSeparator(),
                            StandardOpenOption.CREATE,
                            StandardOpenOption.TRUNCATE_EXISTING);
                } else {
                    System.out.println(output);
                }
            }

            else {
                String executablePath = findExecutable(command);

                if (executablePath != null) {
                    try {
                        ProcessBuilder pb = new ProcessBuilder(commandTokens);
                        pb.directory(currentDirectory.toFile());

                        if (stdoutFile != null) {
                            pb.redirectOutput(new File(stdoutFile));
                        }

                        if (stderrFile != null) {
                            pb.redirectError(new File(stderrFile));
                        }

                        Process process = pb.start();

                        String line;

                        if (stderrFile == null) {
                            BufferedReader errorReader =
                                    new BufferedReader(
                                            new InputStreamReader(process.getErrorStream()));

                            while ((line = errorReader.readLine()) != null) {
                                System.out.println(line);
                            }
                        }

                        if (stdoutFile == null) {
                            BufferedReader outputReader =
                                    new BufferedReader(
                                            new InputStreamReader(process.getInputStream()));

                            while ((line = outputReader.readLine()) != null) {
                                System.out.println(line);
                            }
                        }

                        process.waitFor();

                    } catch (Exception e) {
                        System.out.println(command + ": command not found");
                    }
                } else {
                    System.out.println(command + ": command not found");
                }
            }
        }
    }

    private static String findExecutable(String command) {
        String pathEnv = System.getenv("PATH");

        if (pathEnv == null || pathEnv.isEmpty()) {
            return null;
        }

        String[] paths = pathEnv.split(File.pathSeparator);

        for (String dir : paths) {
            Path filePath = Paths.get(dir, command);

            if (Files.exists(filePath)
                    && Files.isRegularFile(filePath)
                    && Files.isExecutable(filePath)) {
                return filePath.toString();
            }
        }

        return null;
    }

    private static List<String> parseCommand(String input) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;

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
                }

                else if (c == '"') {
                    inDoubleQuote = true;
                }

                else if (c == '\\') {
                    if (i + 1 < input.length()) {
                        current.append(input.charAt(i + 1));
                        i++;
                    }
                }

                else if (Character.isWhitespace(c)) {
                    if (current.length() > 0) {
                        tokens.add(current.toString());
                        current.setLength(0);
                    }
                }

                else {
                    current.append(c);
                }
            }
        }

        if (current.length() > 0) {
            tokens.add(current.toString());
        }

        return tokens;
    }
}