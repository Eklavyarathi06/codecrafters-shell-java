import java.util.Scanner;
import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.nio.file.Path;
import java.nio.file.Paths;

public class Main {

    public static void main(String[] args) throws Exception {

        Scanner scanner = new Scanner(System.in);

        Path currentDirectory = Paths.get("").toAbsolutePath().normalize();

        while (true) {

            System.out.print("$ ");

            String input = scanner.nextLine();

            if (input.equals("exit")) {
                break;
            }

            else if (input.equals("pwd")) {
                System.out.println(currentDirectory);
            }

            else if (input.startsWith("cd ")) {

                String directoryName = input.substring(3);

                File directory = new File(directoryName);

                if (directory.exists() && directory.isDirectory()) {

                    currentDirectory =
                            directory.toPath()
                                     .toAbsolutePath()
                                     .normalize();

                } else {

                    System.out.println(
                            "cd: "
                                    + directoryName
                                    + ": No such file or directory");
                }
            }

            else if (input.startsWith("echo ")) {
                System.out.println(input.substring(5));
            }

            else if (input.startsWith("type ")) {

                String command = input.substring(5);

                if (command.equals("echo")
                        || command.equals("exit")
                        || command.equals("type")
                        || command.equals("pwd")
                        || command.equals("cd")) {

                    System.out.println(command + " is a shell builtin");
                }

                else {

                    String pathEnv = System.getenv("PATH");

                    String[] directories =
                            pathEnv.split(File.pathSeparator);

                    boolean found = false;

                    for (String directory : directories) {

                        File file =
                                new File(directory, command);

                        if (file.exists()
                                && file.isFile()
                                && file.canExecute()) {

                            System.out.println(
                                    command + " is "
                                            + file.getAbsolutePath());

                            found = true;
                            break;
                        }
                    }

                    if (!found) {
                        System.out.println(command + ": not found");
                    }
                }
            }

            else {

                String[] parts = input.split(" ");

                String command = parts[0];

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

                    List<String> commandWithArgs =
                            Arrays.asList(parts);

                    ProcessBuilder processBuilder =
                            new ProcessBuilder(commandWithArgs);

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