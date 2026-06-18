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

            String command = tokens.get(0);

            if (command.equals("exit")) {
                break;
            }

            else if (command.equals("echo")) {
                for (int i = 1; i < tokens.size(); i++) {
                    if (i > 1) System.out.print(" ");
                    System.out.print(tokens.get(i));
                }
                System.out.println();
            }

            else if (command.equals("pwd")) {
                System.out.println(currentDirectory);
            }

            else if (command.equals("cd")) {
                if (tokens.size() < 2) {
                    continue;
                }

                String pathArg = tokens.get(1);
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
                    System.out.println("cd: " + pathArg + ": No such file or directory");
                }
            }

            else if (command.equals("type")) {
                if (tokens.size() < 2) {
                    continue;
                }

                String cmd = tokens.get(1);

                if (BUILTINS.contains(cmd)) {
                    System.out.println(cmd + " is a shell builtin");
                } else {
                    String executablePath = findExecutable(cmd);

                    if (executablePath != null) {
                        System.out.println(cmd + " is " + executablePath);
                    } else {
                        System.out.println(cmd + ": not found");
                    }
                }
            }

            else {
                String executablePath = findExecutable(command);

                if (executablePath != null) {
                    try {
                        List<String> processCommand = new ArrayList<>();
                        processCommand.add(command);

                        for (int i = 1; i < tokens.size(); i++) {
                            processCommand.add(tokens.get(i));
                        }

                        ProcessBuilder pb = new ProcessBuilder(processCommand);
                        pb.directory(currentDirectory.toFile());
                        pb.inheritIO();

                        Process process = pb.start();
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

            if (Files.exists(filePath) && Files.isRegularFile(filePath)
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