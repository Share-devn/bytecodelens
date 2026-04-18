package dev.share.bytecodelens;

import javafx.application.Application;

/**
 * Fat-jar entry point.
 *
 * <p>JavaFX 17+ refuses to start when the declared main class extends
 * {@link Application} and the process was launched from a classpath (as opposed to a
 * module-path). The workaround — documented in OpenJFX's own packaging notes — is to
 * point the jar's {@code Main-Class} at a plain launcher that calls
 * {@code Application.launch(App.class, args)} itself. That path is "trusted" by the
 * JavaFX runtime and initialises the toolkit without requiring modulepath.</p>
 *
 * <p>CLI subcommands (decompile / analyze / mappings) are short-circuited here before
 * {@code launch()} is called, so headless callers never load JavaFX.</p>
 */
public final class Launcher {

    private Launcher() {}

    public static void main(String[] args) {
        if (args.length > 0 && isCliCommand(args[0])) {
            int code = new dev.share.bytecodelens.cli.Cli().run(args);
            System.exit(code);
        }
        Application.launch(App.class, args);
    }

    private static boolean isCliCommand(String first) {
        return switch (first) {
            case "decompile", "analyze", "mappings", "help", "-h", "--help" -> true;
            default -> false;
        };
    }
}
