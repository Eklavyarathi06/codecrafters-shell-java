import java.util.Scanner;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.nio.file.Path;
import java.nio.file.Paths;

public class Main {

    private static List<String> parseCommand(String input) {

        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        boolean inSingleQuotes = false;
        boolean inDoubleQuotes = false;

        for (int i = 0; i < input.length(); i++) {

            char c = input.charAt(i);

            if (c == '\\' && !inSingleQuotes && !inDoubleQuotes) {

                if (i + 1 < input.length()) {
                    current.append(input.charAt(i + 1));
                    i++;
                }
            }

            else if (c == '\'' && !inDoubleQuotes) {
                inSingleQuotes = !inSingleQuotes;
            }

            else if (c == '"' && !inSingleQuotes) {
                inDoubleQuotes = !inDoubleQuotes;
            }

            else if (Character.isWhitespace(c)
                    && !inSingleQuotes
                    && !inDoubleQuotes) {

                if (current.length() > 0) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
            }

            else {
                current.append(c);
            }
        }

        if (current.length() > 0) {
            tokens.add(current.toString());
        }

        return tokens;
    }

    public static void main(String[] args) throws Exception {

        Scanner scanner = new Scanner(System.in);

        Path currentDirectory = Paths.get("").toAbsolutePath().normalize();

        while (true) {

            System.out.print("$ ");

            String input = scanner.nextLine();

            List<String> parts = parseCommand(input);

            if (parts.isEmpty()) {
                continue;
            }

            String command = parts.get(0);

            if (command.equals("exit")) {
                break;
            }

            else if (command.equals("pwd")) {
                System.out.println(currentDirectory);
            }

            else if (command.equals("cd")) {

                if (parts.size() < 2) {
                    continue;
                }

                String directoryName = parts.get(1);

                Path targetPath;

                if (directoryName.equals("~")) {

                    targetPath = Paths.get(System.getenv("HOME"))
                            .normalize();

                } else if (Paths.get(directoryName).isAbsolute()) {

                    targetPath = Paths.get(directoryName)
                            .normalize();

                } else {

                    targetPath = currentDirectory
                            .resolve(directoryName)
                            .normalize();
                }

                File directory = targetPath.toFile();

                if (directory.exists() && directory.isDirectory()) {

                    currentDirectory = targetPath;

                } else {

                    System.out.println(
                            "cd: "
                                    + directoryName
                                    + ": No such file or directory");
                }
            }

            else if (command.equals("echo")) {

                StringBuilder output = new StringBuilder();

                for (int i = 1; i < parts.size(); i++) {

                    if (i > 1) {
                        output.append(" ");
                    }

                    output.append(parts.get(i));
                }

                System.out.println(output);
            }

            else if (command.equals("type")) {

                if (parts.size() < 2) {
                    continue;
                }

                String typeCommand = parts.get(1);

                if (typeCommand.equals("echo")
                        || typeCommand.equals("exit")
                        || typeCommand.equals("type")
                        || typeCommand.equals("pwd")
                        || typeCommand.equals("cd")) {

                    System.out.println(typeCommand + " is a shell builtin");
                }

                else {

                    String pathEnv = System.getenv("PATH");

                    String[] directories =
                            pathEnv.split(File.pathSeparator);

                    boolean found = false;

                    for (String directory : directories) {

                        File file =
                                new File(directory, typeCommand);

                        if (file.exists()
                                && file.isFile()
                                && file.canExecute()) {

                            System.out.println(
                                    typeCommand + " is "
                                            + file.getAbsolutePath());

                            found = true;
                            break;
                        }
                    }

                    if (!found) {
                        System.out.println(typeCommand + ": not found");
                    }
                }
            }

            else {

                String pathEnv = System.getenv("PATH");

                String[] directories =
                        pathEnv.split(File.pathSeparator);

                File executable = null;

                for (String directory : directories) {

                    File file =
                            new File(directory, command);

                    if (file.exists()
                            && file.isFile()
                            && file.canExecute()) {

                        executable = file;
                        break;
                    }
                }

                if (executable != null) {

                    ProcessBuilder processBuilder =
                            new ProcessBuilder(parts);

                    processBuilder.directory(currentDirectory.toFile());

                    processBuilder.inheritIO();

                    Process process =
                            processBuilder.start();

                    process.waitFor();

                } else {

                    System.out.println(
                            input + ": command not found");
                }
            }
        }
    }
}