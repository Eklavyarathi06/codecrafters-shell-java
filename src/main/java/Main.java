import java.util.Scanner;
import java.io.File;

public class Main {

    public static void main(String[] args) throws Exception {

        Scanner scanner = new Scanner(System.in);

        while (true) {

            System.out.print("$ ");

            String input = scanner.nextLine();

            if (input.equals("exit")) {
                break;
            }

            else if (input.startsWith("echo ")) {
                System.out.println(input.substring(5));
            }

            else if (input.startsWith("type ")) {

                String command = input.substring(5);

                if (command.equals("echo")
                        || command.equals("exit")
                        || command.equals("type")) {

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
                System.out.println(input + ": command not found");
            }
        }
    }
}